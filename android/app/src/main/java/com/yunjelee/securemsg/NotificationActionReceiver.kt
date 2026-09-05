package com.yunjelee.securemsg

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yunjelee.securemsg.ui.LastOpened

/**
 * Shade action endpoints for the conversation notifications: inline reply and
 * mark-as-read, wired up by [SmsNotifier.notifyIncoming].
 *
 * A receiver rather than an activity or service: both actions must work from
 * the shade without unlocking into the app, and everything here — an FGS
 * start, a SharedPreferences stamp, one notification repost — is synchronous
 * and fits comfortably inside onReceive's main-thread window, so no goAsync.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val cid = intent.getStringExtra(EXTRA_CID)
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        when (intent.action) {
            ACTION_REPLY -> handleReply(context, intent, tag, cid, phone)
            ACTION_MARK_READ -> handleMarkRead(context, tag, cid, phone)
        }
    }

    private fun handleReply(
        context: Context,
        intent: Intent,
        tag: String,
        cid: String?,
        phone: String,
    ) {
        val text = NotificationReplyPolicy.replyText(
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY),
        )
        if (text == null || phone.isBlank()) {
            // The shade pins a spinner on the action until the notification is
            // posted again under the same tag/id; even a reply that sends
            // nothing must resolve it.
            if (phone.isBlank()) Log.w(TAG, "reply action without a phone target; nothing sent")
            SmsNotifier.notifyReplyPosted(context, tag, null)
            return
        }
        // Same path as the RESPOND_VIA_MESSAGE contract.
        LocalSmsBridge.send(context, phone, text)
        // Replying implies the conversation was read...
        if (cid != null) LastOpened.set(context, cid, System.currentTimeMillis())
        // ...and ends its alert burst: the next incoming message is news again.
        SmsNotifier.clearAlertCooldown(tag)
        SmsNotifier.notifyReplyPosted(context, tag, text)
    }

    private fun handleMarkRead(context: Context, tag: String, cid: String?, phone: String) {
        if (cid != null) LastOpened.set(context, cid, System.currentTimeMillis())
        if (cid != null || phone.isNotBlank()) {
            SmsNotifier.cancelConversation(context, cid, phone)
        } else {
            // No conversation key to cancel by; the posted tag must still come
            // down rather than stay in the shade after "읽음".
            SmsNotifier.cancelByTag(context, tag)
        }
    }

    companion object {
        const val ACTION_REPLY = "com.yunjelee.securemsg.NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "com.yunjelee.securemsg.NOTIFICATION_MARK_READ"

        /** RemoteInput result key shared with the action built in [SmsNotifier]. */
        const val KEY_REPLY = "key_reply"

        const val EXTRA_TAG = "action_tag"
        const val EXTRA_CID = "action_cid"
        const val EXTRA_PHONE = "action_phone"

        /** Informational: the repost path recovers the posted notification, so
         * the title only travels along for log/debug use. */
        const val EXTRA_TITLE = "action_title"

        private const val TAG = "NotificationAction"
    }
}

/** Pure, so the shade-reply validation is JVM-testable without a RemoteInput. */
internal object NotificationReplyPolicy {
    /** The text worth sending for a raw RemoteInput result, or null when there is none. */
    fun replyText(raw: CharSequence?): String? =
        raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
