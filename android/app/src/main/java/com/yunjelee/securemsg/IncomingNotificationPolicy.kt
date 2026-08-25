package com.yunjelee.securemsg

/** Pure notification copy/gating policy shared by non-UI carrier receive paths. */
object IncomingNotificationPolicy {
    /**
     * How stale a provider row may be and still be worth a notification when it is
     * discovered by a rescan rather than by a live broadcast.
     */
    const val RESCAN_NOTIFY_MAX_AGE_MS: Long = 6L * 60 * 60 * 1000

    /**
     * Notification group key for one conversation, or `""` when the conversation
     * cannot be identified.
     *
     * Keyed on the normalized phone number in preference to the cid: the bridge
     * rewrites a `local_…` cid to the server one once the relay assigns it, and a
     * notification posted under the old cid would then survive the cancel that
     * runs when the user opens the conversation. The phone number is stable for
     * the life of the conversation.
     *
     * @param normalizedPhone must already be [PhoneNumberNormalizer.normalize]d.
     */
    fun conversationGroup(cid: String?, normalizedPhone: String): String {
        val key = normalizedPhone.takeIf { it.isNotBlank() }
            ?: cid?.takeIf { it.isNotBlank() }
            ?: return ""
        return "sms_$key"
    }

    fun preview(content: RelayContent): String {
        if (content.type != RelayContentCodec.TYPE_MMS) return content.text

        val details = buildList {
            content.subject?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            content.text.trim().takeIf { it.isNotEmpty() }?.let(::add)
            if (content.attachments.isNotEmpty()) add("첨부파일 ${content.attachments.size}개")
        }
        return details.joinToString(" · ").ifBlank { "MMS 메시지" }
    }

    /**
     * Whether a message found by a provider rescan (startup import, MMS sweep)
     * should raise a notification.
     *
     * The dedupe ledgers normally answer this, but logout clears them, after which
     * every row the sweep sees looks new again — that is how re-login used to fire
     * a burst of notifications for messages the user read days ago. Age is the only
     * signal that survives the wipe, so a rescan notifies only for messages recent
     * enough to plausibly still be unread. Live SMS_DELIVER does not use this gate:
     * a store-and-forward delivery can legitimately carry an hours-old carrier
     * timestamp and must still notify.
     */
    fun shouldNotifyRescan(newlyCreated: Boolean, receivedAt: Long, now: Long): Boolean {
        if (!newlyCreated) return false
        // A future-dated (clock-skewed) row is treated as fresh: dropping a real
        // notification is worse than showing one with an odd timestamp.
        return now - receivedAt <= RESCAN_NOTIFY_MAX_AGE_MS
    }

    /**
     * The single notification gate for both carrier kinds.
     *
     * [rescan] is the whole decision: a row a broadcast told us about is live and
     * notifies on its own merit, however old its carrier timestamp is (a
     * store-and-forward delivery arriving after a night out of coverage is
     * exactly that case), while a row found by sweeping the provider has to
     * clear the age gate. SMS and MMS share this so the two cannot drift apart
     * again.
     */
    fun shouldNotify(
        rescan: Boolean,
        newlyCreated: Boolean,
        receivedAt: Long,
        now: Long,
    ): Boolean = if (rescan) {
        shouldNotifyRescan(newlyCreated, receivedAt, now)
    } else {
        newlyCreated
    }
}
