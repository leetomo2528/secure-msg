package com.yunjelee.securemsg

import java.text.Normalizer
import java.util.Locale

/** Safe blocked-sender comparison.
 *
 * Phone numbers match only after full canonicalization; suffix matching is
 * intentionally forbidden because unrelated country codes can share the same
 * national-number tail. Alphanumeric sender IDs use exact NFKC,
 * case-insensitive comparison and are never reduced to their digits.
 */
object SenderMatcher {
    fun matches(incoming: String, blocked: String): Boolean {
        val a = canonicalIdentity(incoming)
        val b = canonicalIdentity(blocked)
        return a.isNotEmpty() && a == b
    }

    private fun canonicalIdentity(value: String): String = Normalizer
        .normalize(PhoneNumberNormalizer.normalize(value), Normalizer.Form.NFKC)
        .trim()
        .lowercase(Locale.ROOT)
}
