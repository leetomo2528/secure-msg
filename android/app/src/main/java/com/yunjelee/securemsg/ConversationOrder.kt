package com.yunjelee.securemsg

/** Pure ordering for the 메시지 conversation list. */
object ConversationOrder {
    /**
     * [threads] with the pinned ones lifted to the front.
     *
     * The input is already the order the list means to show — ThreadDao emits
     * `lastActivityAt DESC, lastSeq DESC` — so this only partitions it. Both
     * halves keep their incoming relative order, which is what makes the result
     * stable for threads sharing a timestamp: the comparison never looks at
     * lastActivityAt, so it cannot re-break a tie the DAO already broke.
     *
     * An empty [pinned] returns the very same list instance, so the no-pin case
     * is not merely equal to today's ordering but identical to it. A pin whose
     * conversation is absent (deleted thread, another device's number) simply
     * matches nothing here — orphaned entries are inert, never rows.
     */
    fun pinnedFirst(threads: List<SmsThread>, pinned: Set<String>): List<SmsThread> {
        if (pinned.isEmpty()) return threads
        val (top, rest) = threads.partition { isPinned(it, pinned) }
        return if (top.isEmpty()) threads else top + rest
    }

    /** True when [pinned] — normalized keys, as [PinnedConversations] stores them — covers [thread]. */
    fun isPinned(thread: SmsThread, pinned: Set<String>): Boolean =
        pinned.isNotEmpty() && PhoneNumberNormalizer.normalize(thread.phoneNumber) in pinned
}
