package com.yunjelee.securemsg

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
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
    private const val CHANNEL_ID = "securemsg_sms_v3"

    /**
     * Catch-up channel for startup/reconnect sweeps. IMPORTANCE_LOW on purpose:
     * a sweep can legitimately post dozens of notifications at once, and doing
     * that on the HIGH channel is exactly the burst that makes One UI's
     * adaptive notifications demote the channel to silent — a demotion the app
     * can never undo. Swept messages appear in the shade; only live arrivals
     * may banner.
     */
    private const val CATCHUP_CHANNEL_ID = "securemsg_sms_catchup"

    /**
     * v1 shipped IMPORTANCE_DEFAULT; v2 was HIGH but its first-login sweep
     * burst plausibly got it demoted to silent on real devices (user-locked,
     * irrecoverable in code). With sweeps moved to the catch-up channel the
     * burst input is gone, so v3 starts clean. Keep this id stable from now on.
     */
    private val RETIRED_CHANNEL_IDS = listOf("securemsg_sms", "securemsg_sms_v2")
    private const val TAG = "SmsNotifier"

    /** ensureChannel is called on the hot receive path; skip the binder round trip after the first. */
    @Volatile
    private var channelsEnsured = false

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

    /** Whether the activity is between onResume and onPause — actually in
     * front, which is stricter than visible; see [setAppForeground]. */
    @Volatile
    private var appForeground: Boolean = false

    /** Whether the activity is between onStart and onStop — possibly visible
     * without being in front (split-screen, multi-window). */
    @Volatile
    private var appVisible: Boolean = false

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
        if (channelsEnsured) return
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
        manager.createNotificationChannel(
            NotificationChannel(
                CATCHUP_CHANNEL_ID,
                "놓친 메시지",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "재시작·재연결 시 가져온 지난 메시지"
                setShowBadge(true)
            },
        )
        channelsEnsured = true
    }

    /** System notification settings for the live-message channel. */
    fun channelSettingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)

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
        RETIRED_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
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

    /** Activity onResume/onPause: an open conversation only counts while it is
     * actually in front — split-screen/paused must not swallow its alerts. */
    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
    }

    /** Activity onStart/onStop. Kept separate from [setAppForeground]:
     * notification suppression must treat a split-screen paused activity as
     * background, while the updater's kill gate must treat it as on screen. */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
    }

    /** The unattended updater's silent commit kills the process; it defers
     * while the UI can be seen at all — resumed or merely started (split-
     * screen) — and gates on this flag, never on [appForeground], to know. */
    fun isAppVisible(): Boolean = appVisible

    fun notifyIncoming(
        context: Context,
        phoneNumber: String,
        body: String,
        date: Long,
        cid: String? = null,
        messageIdentity: String = "$phoneNumber:$date",
        displayName: String? = null,
        liveAlert: Boolean = true,
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
        // MessagingStyle rejects a blank Person name; a cid-only edge case can
        // reach here with an empty number.
        val title = (displayName?.takeIf { it.isNotBlank() } ?: phoneNumber).ifBlank { "알 수 없는 발신자" }
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
        // A sweep import never claims an alert slot: it posts on the silent
        // catch-up channel and leaves the cooldown ledger to live arrivals.
        val alerting = liveAlert && shouldAlert(tag)
        val builder = Notification.Builder(context, if (liveAlert) CHANNEL_ID else CATCHUP_CHANNEL_ID)
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
            // On both channels: replying to a message a sweep caught up on is
            // as legitimate as replying to a live one.
            .setActions(
                replyAction(context, tag, cid, normalizedPhone, title),
                markReadAction(context, tag, cid, normalizedPhone, title),
            )
        // No setGroup: the tag already keeps one notification per conversation,
        // so every group would have exactly one child and no summary — dead
        // weight on AOSP and a bundling wildcard on One UI.

        manager.notify(tag, MESSAGE_ID, builder.build())
    }

    /**
     * The bridge's auth was rejected (token expired or device revoked) and
     * message sync has STOPPED. Silence here cost a day of dead sync once;
     * this is deliberately a plain, loud, tap-to-reopen notification.
     */
    fun notifySessionExpired(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "session expired but POST_NOTIFICATIONS is not granted")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("문자 동기화가 중단되었습니다")
            .setContentText("로그인이 만료되었습니다. 앱을 열어 다시 로그인하세요.")
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify("session_expired", MESSAGE_ID, notification)
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
            if (posted.notification.channelId !in setOf(CHANNEL_ID, CATCHUP_CHANNEL_ID)) return@forEach
            if (posted.notification.group != group) return@forEach
            manager.cancel(tag, posted.id)
        }
    }

    /**
     * Drops the notification posted under a raw tag. Fallback for a shade
     * action whose conversation has neither cid nor phone — there is no group
     * key to sweep by, but the notification must still not outlive "읽음".
     */
    fun cancelByTag(context: Context, tag: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        lastAlertAt.remove(tag)
        manager.cancel(tag, MESSAGE_ID)
    }

    /** Replying from the shade ends a burst the same way opening the app does:
     * the next incoming message in the conversation is news again. */
    fun clearAlertCooldown(tag: String) {
        lastAlertAt.remove(tag)
    }

    /**
     * Resolves a shade inline reply: the platform pins a spinner on the action
     * until a notification is posted again under the same tag/id, so this MUST
     * repost even with nothing to append (`replyText == null`).
     *
     * `recoverBuilder` keeps everything the posted notification carried —
     * channel, content intent, actions, category, visibility — so only the
     * style changes, and only when a reply exists.
     */
    fun notifyReplyPosted(context: Context, tag: String, replyText: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val posted = activeNotifications(manager)
            .firstOrNull { it.tag == tag && it.id == MESSAGE_ID }
            ?.notification
            // Dismissed or cancelled since the reply began: no spinner is left
            // to resolve, and reposting would resurrect a read conversation.
            ?: return
        val builder = Notification.Builder.recoverBuilder(context, posted)
            // The conversation already announced itself; appending the user's
            // own words must not sound or banner.
            .setOnlyAlertOnce(true)
        if (!replyText.isNullOrBlank()) {
            val style = Notification.MessagingStyle(Person.Builder().setName(SELF_NAME).build())
            postedMessages(posted).takeLast(MAX_STYLE_MESSAGES - 1).forEach { style.addMessage(it) }
            // A null sender renders as the style's own user ("나").
            style.addMessage(replyText, System.currentTimeMillis(), null as Person?)
            builder.setStyle(style)
        }
        manager.notify(tag, MESSAGE_ID, builder.build())
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
        postedMessages(posted).takeLast(MAX_STYLE_MESSAGES - 1).forEach { style.addMessage(it) }
        style.addMessage(body, date, sender)
        return style
    }

    /**
     * The messages a posted notification still carries. The posted notification
     * is the only place the earlier messages of a conversation exist — nothing
     * here keeps a copy, and the process may well have been restarted since.
     */
    private fun postedMessages(posted: Notification?): List<Notification.MessagingStyle.Message> {
        val bundles = posted?.extras?.let { extras ->
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            }
        }
        return bundles
            ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
            .orEmpty()
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

    private fun replyAction(
        context: Context,
        tag: String,
        cid: String?,
        normalizedPhone: String,
        title: String,
    ): Notification.Action {
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
            .setLabel("메시지 입력")
            .build()
        // MUTABLE, not IMMUTABLE: the shade must write the typed text into the
        // fired intent. Identity still separates per conversation via the data
        // URI, exactly like conversationIntent's tap target.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            tag.hashCode() and Int.MAX_VALUE,
            actionIntent(
                context, NotificationActionReceiver.ACTION_REPLY, "reply",
                tag, cid, normalizedPhone, title,
            ),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The small icon does double duty: actions render text-only on modern
        // Android, so no dedicated action drawable is worth shipping.
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_message),
            "답장",
            pendingIntent,
        ).addRemoteInput(remoteInput).build()
    }

    private fun markReadAction(
        context: Context,
        tag: String,
        cid: String?,
        normalizedPhone: String,
        title: String,
    ): Notification.Action {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            tag.hashCode() and Int.MAX_VALUE,
            actionIntent(
                context, NotificationActionReceiver.ACTION_MARK_READ, "read",
                tag, cid, normalizedPhone, title,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_message),
            "읽음",
            pendingIntent,
        ).build()
    }

    private fun actionIntent(
        context: Context,
        action: String,
        path: String,
        tag: String,
        cid: String?,
        normalizedPhone: String,
        title: String,
    ): Intent = Intent(context, NotificationActionReceiver::class.java).apply {
        this.action = action
        // Intent data participates in PendingIntent identity; extras do not.
        // The action path keeps reply and read distinct even for one tag.
        data = Uri.Builder()
            .scheme("securemsg")
            .authority("notif-action")
            .appendPath(path)
            .appendPath(tag)
            .build()
        putExtra(NotificationActionReceiver.EXTRA_TAG, tag)
        putExtra(NotificationActionReceiver.EXTRA_CID, cid)
        putExtra(NotificationActionReceiver.EXTRA_PHONE, normalizedPhone)
        putExtra(NotificationActionReceiver.EXTRA_TITLE, title)
    }
}
