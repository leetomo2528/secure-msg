package com.yunjelee.securemsg

import java.text.Normalizer
import java.util.Locale

object BlocklistManager {

    data class Decision(
        val blocked: Boolean,
        val reason: String,
    ) {
        companion object {
            /** Shared "not blocked" verdict; callers that fail open must not re-spell it. */
            val ALLOW = Decision(false, "")
        }
    }

    suspend fun evaluate(
        phoneNumber: String,
        plaintext: String,
        db: AppDatabase,
    ): Decision {
        val shared = BlocklistSync.load()
        val normalizedPhone = PhoneNumberNormalizer.normalize(phoneNumber)
        if (normalizedPhone.isNotBlank()) {
            // Older releases stored Korean numbers as 010..., while current
            // releases store +8210.... Match existing Room rows without a
            // destructive or one-way data migration.
            if (db.blockedSenderDao().getAll().any {
                senderMatches(normalizedPhone, it.phoneNumber)
            }) {
                return Decision(true, "차단한 발신번호")
            }
            if (shared.senders.any { senderMatches(normalizedPhone, it) }) {
                return Decision(true, "차단한 발신번호(동기화)")
            }
        }

        val lower = fold(plaintext)
        val compact = lower.replace(Regex("\\s+"), "")
        val localKeywords = db.blocklistDao().getAll().map { it.keyword }
        val matched = (localKeywords + shared.keywords).firstOrNull { raw ->
            val kw = fold(raw.trim())
            kw.isNotBlank() && (lower.contains(kw) || compact.contains(kw.replace(Regex("\\s+"), "")))
        }
        if (matched != null) {
            return Decision(true, "사용자 키워드: $matched")
        }

        val spam = SpamClassifier.classify(phoneNumber, plaintext)
        if (spam.isSpam) {
            return Decision(true, "자동 스팸: ${spam.reason ?: "의심 패턴"}")
        }
        return Decision.ALLOW
    }

    /** Digit-based match so +82-10-… and 010… forms of the same number hit. */
    fun senderMatches(incoming: String, blocked: String): Boolean =
        SenderMatcher.matches(incoming, blocked)

    /**
     * The stored rule values that cover [phoneNumber], as they are stored.
     *
     * Removal compares rule values exactly — BlocklistSync deletes the Room row
     * whose `phoneNumber ==` the value and looks the server id up under
     * `"sender|$value"` — so an unblock must be driven by these originals. A
     * normalized `+8210…` would leave a legacy `010…` rule (still matched by
     * [senderMatches]) in place and the sender would stay blocked.
     *
     * Pure: no Android dependency, so the UI can call it on any thread.
     */
    fun matchingSenderRules(phoneNumber: String, ruleValues: List<String>): List<String> {
        val normalized = PhoneNumberNormalizer.normalize(phoneNumber)
        if (normalized.isBlank()) return emptyList()
        return ruleValues.filter { senderMatches(normalized, it) }.distinct()
    }

    /** UI-side sender verdict. Same matcher as [evaluate], so the two cannot drift. */
    fun senderBlocked(phoneNumber: String, ruleValues: List<String>): Boolean =
        matchingSenderRules(phoneNumber, ruleValues).isNotEmpty()

    private val zeroWidth = Regex("[\\u200B-\\u200D\\uFEFF]")

    /**
     * The fold [SpamClassifier] applies, zero-width strip included.
     *
     * A keyword rule and the classifier read the same body inside one [evaluate]
     * call, so they have to agree on what it says: without the strip a zero-width
     * joiner planted mid-word slipped a user keyword while the classifier's own
     * term still matched.
     */
    private fun fold(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(zeroWidth, "")
}
