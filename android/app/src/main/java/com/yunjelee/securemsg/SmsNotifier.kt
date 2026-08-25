package com.yunjelee.securemsg

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Incoming-message notifications for every carrier receive path.
 *
 * One active notification per conversation, never one per message: the platform
 * drops `notify()` silently once a package holds MAX_PACKAGE_NOTIFICATIONS (50)
 * active ones, and a per-message posting reaches that from a single chatty
 * sender. The messages of a conversation accumulate inside one
 * [Notification.MessagingStyle] instead, and the notification is cancelled both
 * when its conversation is opened from the shade and when it is opened inside
 * the app.
 */
object SmsNotifier {
    /**
     * The platform ignores importance/sound edits to a channel it has already
     * created — only the user may change those afterwards. The installed base was
     * therefore stuck on IMPORTANCE_DEFAULT (no heads-up banner) and a new channel
     * id is the only way to lift it off. Keep this id stable from now on.
     */
    private const val CHANNEL_ID = "securemsg_sms_v2"
    private const val LEGACY_CHANNEL_ID = "securemsg_sms"
    private const val TAG = "SmsNotifier"

    /** Conversation notifications are distinguished by tag; ids only separate roles. */
    private const val MESSAGE_ID = 1

    /** MessagingStyle needs a "self" Person even for a one-way inbound message. */
    private const val SELF_NAME = "나"

    /**
     * Messages carried by one conversation notification. The shade collapses
     * everything past the last few anyway, and an unbounded style would keep
     * growing a parcel that crosses a binder transaction on every message.
     */
    private const val MAX_STYLE_MESSAGES = 8

    /**
     * How soon after alerting for a conversation a further message updates it
     * silently.
     *
     * The channel is IMPORTANCE_HIGH, so without this a five-message burst
     * throws five full-width heads-up banners in a row. Inside the window the
     * notification still updates (content, badge, sort order) — only the sound
     * and the banner are suppressed, and the window is short enough that a
     * genuinely separate message still announces itself.
     */
    private const val ALERT_COOLDOWN_MS = 10_000L

    /** group key -> last time that conversation was allowed to alert. */
    private val lastAlertAt = ConcurrentHashMap<String, Long>()

    /** Conversation open on screen, as a group key; "" when none is. */
    @Volatile
    private var visibleGroup: String = ""

    /** Whether the activity is between onStart and onStop. */
    @Volatile
    private var appForeground: Boolean = false

    const val ACTION_OPEN_CONVERSATION = "com.yunjelee.securemsg.OPEN_CONVERSATION"
    const val EXTRA_CID = "conversation_cid"
    const val EXTRA_PHONE = "conversation_phone"
    const val EXTRA_REQUEST_ID = "conversation_request_id"

    /**
     * Creates the message channel. Called from the bridge service's `onCreate` as
     * well as from [notifyIncoming] so the channel exists in system settings
     * before the first message arrives — otherwise the user cannot pre-adjust its
     * importance, sound or bubble behaviour.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "메시지 수신",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "차단되지 않은 SMS/MMS 알림"
                enableVibration(true)
                setShowBadge(true)
                setAllowBubbles(true)
            },
        )
    }

    /**
     * Retires the pre-v2 channel; leaving it behind puts a dead entry in the
     * system settings list that the user can configure to no effect.
     *
     * Separate from [ensureChannel] because this is a one-shot migration, and
     * [notifyIncoming] runs inside a `goAsync()` broadcast window where a
     * needless NotificationManager round trip (plus its policy-XML write) spends
     * budget that belongs to persisting the message.
     */
    fun retireLegacyChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    /**
     * Records which conversation the user is looking at, so a message arriving
     * in it is not announced on top of itself. Cleared with `null`.
     */
    fun setVisibleConversation(cid: String?, phoneNumber: String?) {
        visibleGroup = if (phoneNumber == null && cid == null) {
            ""
        } else {
            IncomingNotificationPolicy.conversationGroup(
                cid,
                PhoneNumberNormalizer.normalize(phoneNumber.orEmpty()),
            )
        }
    }

    /** Activity onStart/onStop: an open conversation only counts while visible. */
    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
    }

    fun notifyIncoming(
        context: Context,
        phoneNumber: String,
        body: String,
        date: Long,
        cid: String? = null,
        messageIdentity: String = "$phoneNumber:$date",
        displayName: String? = null,
    ) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Without this line a revoked permission is indistinguishable from
            // "the message never arrived" in a bug report.
            Log.w(TAG, "POST_NOTIFICATIONS not granted; incoming message not notified")
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)

        val normalizedPhone = PhoneNumberNormalizer.normalize(phoneNumber)
        val title = displayName?.takeIf { it.isNotBlank() } ?: phoneNumber
        val requestId = messageIdentity.ifBlank { "$normalizedPhone:$date" }
        val group = IncomingNotificationPolicy.conversationGroup(cid, normalizedPhone)
        // The user is already reading this conversation; the message is on screen
        // by the time this runs, so a banner over it would only hide it.
        if (group.isNotEmpty() && appForeground && group == visibleGroup) {
            Log.i(TAG, "conversation is on screen; notification suppressed")
            return
        }
        val pendingIntent = conversationIntent(context, cid, normalizedPhone, requestId)

        val sender = Person.Builder()
            .setName(title)
            // A stable key lets the platform fold repeated messages from the same
            // sender into one conversation entry even as the display name changes.
            .setKey(normalizedPhone.ifBlank { requestId })
            .build()
        // Tag = conversation: a second message replaces the first notification
        // instead of adding one, and re-posting carries the earlier messages
        // over so nothing that was in the shade disappears from it.
        val tag = group.ifEmpty { requestId }
        // Read once, before building: the call records that this conversation
        // has alerted, and doing it inside the chain would hide that.
        val alerting = shouldAlert(tag)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(conversationStyle(manager, tag, sender, body, date))
            // CATEGORY_MESSAGE is what makes Do Not Disturb's message exception
            // apply; without it a DND profile silences the app completely.
            .setCategory(Notification.CATEGORY_MESSAGE)
            // The carrier timestamp, not build() time: a message processed late
            // must still read and sort as of when it actually arrived.
            .setWhen(date)
            .setShowWhen(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alerting)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (group.isNotEmpty()) builder.setGroup(group)

        manager.notify(tag, MESSAGE_ID, builder.build())
    }

    /**
     * Drops the notifications of one conversation, e.g. because the user just
     * opened it — from the shade or from inside the app. Matching is by group
     * key and tag only: the bridge's foreground notification also uses id 1, and
     * cancelling it would strip the service of its foreground handle.
     */
    fun cancelConversation(context: Context, cid: String?, phoneNumber: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val group = IncomingNotificationPolicy.conversationGroup(
            cid,
            PhoneNumberNormalizer.normalize(phoneNumber),
        )
        if (group.isEmpty()) return
        // Reading the conversation ends the burst: the next message in it is
        // news again and must alert.
        lastAlertAt.remove(group)
        manager.cancel(group, MESSAGE_ID)
        // Per-message notifications posted by an older build (and any group
        // summary it left behind) are keyed on the message id, not the group.
        activeNotifications(manager).forEach { posted ->
            // The foreground notification carries no tag and a different channel.
            val tag = posted.tag ?: return@forEach
            if (posted.notification.channelId != CHANNEL_ID) return@forEach
            if (posted.notification.group != group) return@forEach
            manager.cancel(tag, posted.id)
        }
    }

    /**
     * The style for the re-posted conversation notification: the messages the
     * shade already shows, trimmed to [MAX_STYLE_MESSAGES], plus the new one.
     * Falls back to a fresh style when nothing is posted (or the platform
     * refuses to enumerate), so a message is never lost to this lookup.
     */
    private fun conversationStyle(
        manager: NotificationManager,
        tag: String,
        sender: Person,
        body: String,
        date: Long,
    ): Notification.MessagingStyle {
        val style = Notification.MessagingStyle(Person.Builder().setName(SELF_NAME).build())
        val posted = activeNotifications(manager)
            .firstOrNull { it.tag == tag && it.id == MESSAGE_ID }
            ?.notification
        // The posted notification is the only place the earlier messages of this
        // conversation still exist — nothing here keeps a copy, and the process
        // may well have been restarted since.
        val bundles = posted?.extras?.let { extras ->
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            }
        }
        val previous = bundles
            ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
            .orEmpty()
        previous.takeLast(MAX_STYLE_MESSAGES - 1).forEach { style.addMessage(it) }
        style.addMessage(body, date, sender)
        return style
    }

    private fun activeNotifications(manager: NotificationManager): List<StatusBarNotification> =
        try {
            manager.activeNotifications.orEmpty().toList()
        } catch (e: Exception) {
            Log.w(TAG, "cannot enumerate active notifications", e)
            emptyList()
        }

    /** Whether this conversation may make noise now; see [ALERT_COOLDOWN_MS]. */
    private fun shouldAlert(tag: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastAlertAt[tag]
        // A clock moved backwards must not mute a conversation until it catches up.
        if (previous != null && previous in (now - ALERT_COOLDOWN_MS)..now) return false
        // Bounded: the map only exists to space out bursts, so dropping it whole
        // is harmless — at worst one extra conversation alerts a moment early.
        if (lastAlertAt.size > 128) lastAlertAt.clear()
        lastAlertAt[tag] = now
        return true
    }

    private fun conversationIntent(
        context: Context,
        cid: String?,
        normalizedPhone: String,
        requestId: String,
    ): PendingIntent {
        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CONVERSATION
            // Intent data participates in PendingIntent identity; extras do not.
            data = Uri.Builder()
                .scheme("securemsg")
                .authority("conversation")
                .appendPath(requestId)
                .build()
            putExtra(EXTRA_CID, cid)
            putExtra(EXTRA_PHONE, normalizedPhone)
            putExtra(EXTRA_REQUEST_ID, requestId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Data is part of PendingIntent equality, so even the rare case where two
        // requestId hash codes collide still produces independent tap targets.
        val requestCode = requestId.hashCode() and Int.MAX_VALUE
        return PendingIntent.getActivity(
            context,
            requestCode,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
