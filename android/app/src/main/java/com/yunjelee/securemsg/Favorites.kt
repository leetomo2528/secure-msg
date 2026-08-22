package com.yunjelee.securemsg

import android.content.Context

/**
 * Favourite contacts for the 연락처 tab. Device-local only: the relay never
 * sees the address book, and a star is not worth widening that boundary.
 *
 * Entries are [PhoneNumberNormalizer.normalize] output so a contact stored as
 * `010-1234-5678` and a thread keyed `+821012345678` agree on one key.
 */
object Favorites {
    private const val PREFS = "favorites"
    private const val KEY_PHONES = "phones"

    fun load(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PHONES, null)
            // getStringSet hands back its own instance; copy before anyone mutates.
            ?.toSet()
            .orEmpty()

    fun isFavorite(context: Context, normalizedPhone: String): Boolean =
        PhoneNumberNormalizer.normalize(normalizedPhone) in load(context)

    /** Flips [normalizedPhone], persists, and returns the new set. */
    fun toggle(context: Context, normalizedPhone: String): Set<String> {
        val next = toggled(load(context), normalizedPhone)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            // A fresh HashSet: SharedPreferences skips the write when it is
            // handed the same instance it returned from getStringSet.
            .putStringSet(KEY_PHONES, HashSet(next))
            .apply()
        return next
    }

    /** Forget-device path: the next account on this phone must not inherit stars. */
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
