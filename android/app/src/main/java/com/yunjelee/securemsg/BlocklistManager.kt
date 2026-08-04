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
        val normalizedPhone = PhoneNumberNormalizer.normalize(phoneNumber)
        if (normalizedPhone.isNotBlank() && db.blockedSenderDao().contains(normalizedPhone)) {
            return Decision(true, "차단한 발신번호")
        }

        val lower = Normalizer.normalize(plaintext, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val compact = lower.replace(Regex("\\s+"), "")
        val keyword = db.blocklistDao().getAll().firstOrNull { row ->
            val kw = Normalizer.normalize(row.keyword.trim(), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
            kw.isNotBlank() && (lower.contains(kw) || compact.contains(kw.replace(Regex("\\s+"), "")))
        }
        if (keyword != null) {
            return Decision(true, "사용자 키워드: ${keyword.keyword}")
        }

        val spam = SpamClassifier.classify(phoneNumber, plaintext)
        if (spam.isSpam) {
            return Decision(true, "자동 스팸: ${spam.reason ?: "의심 패턴"}")
        }
        return Decision(false, "")
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
