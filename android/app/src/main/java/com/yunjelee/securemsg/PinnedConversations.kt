package com.yunjelee.securemsg

import android.content.Context

/**
 * Conversations the user pinned to the top of the 메시지 list. Device-local
 * only, for the same reason [Favorites] is: the relay never sees the address
 * book, and an ordering preference is not worth widening that boundary.
 *
 * Entries are [PhoneNumberNormalizer.normalize] output rather than cids. A
 * conversation's cid is not stable — an offline send starts in a provisional
 * `local_…` thread that SmsBridgeService later swaps for the relay cid, and
 * HistoryRestore rebuilds threads under fresh cids — so a cid-keyed pin would
 * silently drop off the moment either happened. The number survives both, and
 * it is the same key [Favorites] uses, so a contact stored as `010-1234-5678`
 * and a thread keyed `+821012345678` agree.
 *
 * Entries are never reconciled against the thread table. A pin for a thread
 * that no longer exists is inert — [ConversationOrder] only reorders rows it
 * was handed, so an orphan can never put a conversation back — while pruning
 * would have to run against a thread list that is legitimately empty during
 * cold start and mid-wipe, and would silently drop live pins when it did.
 */
object PinnedConversations {
    private const val PREFS = "pinned_conversations"
    private const val KEY_PHONES = "phones"

    fun load(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PHONES, null)
            // getStringSet hands back its own instance; copy before anyone mutates.
            ?.toSet()
            .orEmpty()

    fun isPinned(context: Context, phone: String): Boolean =
        PhoneNumberNormalizer.normalize(phone) in load(context)

    /** Flips [phone], persists, and returns the new set. */
    fun toggle(context: Context, phone: String): Set<String> {
        val next = toggled(load(context), phone)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            // A fresh HashSet: SharedPreferences skips the write when it is
            // handed the same instance it returned from getStringSet.
            .putStringSet(KEY_PHONES, HashSet(next))
            .apply()
        return next
    }

    /** Forget-device path: the next account on this phone must not inherit pins. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Pure flip, split out so the rule is unit-testable without a Context.
     * Re-normalizes defensively (normalize is idempotent on its own output);
     * a blank key is ignored rather than stored.
     */
    fun toggled(current: Set<String>, phone: String): Set<String> {
        val key = PhoneNumberNormalizer.normalize(phone)
        if (key.isEmpty()) return current
        return if (key in current) current - key else current + key
    }
}
