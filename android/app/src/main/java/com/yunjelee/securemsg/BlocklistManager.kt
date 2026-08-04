package com.yunjelee.securemsg

import java.text.Normalizer
import java.util.Locale

object BlocklistManager {

    data class Decision(
        val blocked: Boolean,
        val reason: String,
    )

    suspend fun evaluate(
        phoneNumber: String,
        plaintext: String,
        db: AppDatabase,
    ): Decision {
        val shared = BlocklistSync.load()
        val normalizedPhone = PhoneNumberNormalizer.normalize(phoneNumber)
        if (normalizedPhone.isNotBlank()) {
            if (db.blockedSenderDao().contains(normalizedPhone)) {
                return Decision(true, "차단한 발신번호")
            }
            if (shared.senders.any { senderMatches(normalizedPhone, it) }) {
                return Decision(true, "차단한 발신번호(동기화)")
            }
        }

        val lower = Normalizer.normalize(plaintext, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val compact = lower.replace(Regex("\\s+"), "")
        val localKeywords = db.blocklistDao().getAll().map { it.keyword }
        val matched = (localKeywords + shared.keywords).firstOrNull { raw ->
            val kw = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
            kw.isNotBlank() && (lower.contains(kw) || compact.contains(kw.replace(Regex("\\s+"), "")))
        }
        if (matched != null) {
            return Decision(true, "사용자 키워드: $matched")
        }

        val spam = SpamClassifier.classify(phoneNumber, plaintext)
        if (spam.isSpam) {
            return Decision(true, "자동 스팸: ${spam.reason ?: "의심 패턴"}")
        }
        return Decision(false, "")
    }

    /** Digit-based match so +82-10-… and 010… forms of the same number hit. */
    private fun senderMatches(incoming: String, blocked: String): Boolean {
        val a = incoming.replace(Regex("[^0-9*#]"), "")
        val b = blocked.replace(Regex("[^0-9*#]"), "")
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val tailA = a.takeLast(9)
        val tailB = b.takeLast(9)
        return tailA.length >= 9 && tailB.length >= 9 && tailA == tailB
    }

    suspend fun shouldBlock(plaintext: String, db: AppDatabase): Boolean {
        return evaluate("", plaintext, db).blocked
    }

    suspend fun applyBlock(
        cid: String,
        seq: Int,
        plaintext: String,
        db: AppDatabase,
    ): Boolean {
        val blocked = shouldBlock(plaintext, db)
        if (blocked) {
            db.messageDao().setBlocked(cid, seq, true)
        }
        return !blocked
    }
}
