package com.yunjelee.securemsg

import java.text.Normalizer
import java.util.Locale

/**
 * Small, deterministic on-device spam classifier.
 *
 * It deliberately does not upload SMS content or sender addresses. The score is
 * conservative: a single ordinary word is not enough; marketing/financial
 * language combined with a URL (or several strong indicators) is required.
 */
object SpamClassifier {

    data class Result(
        val isSpam: Boolean,
        val score: Int,
        val reason: String? = null,
    )

    private val urlPattern = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val otpPattern = Regex(
        "인증번호|인증 번호|일회용|보안코드|보안 코드|verification code|one[- ]time",
        RegexOption.IGNORE_CASE,
    )
    private val financialTerms = listOf(
        "대출", "저금리", "신용", "투자", "코인", "비트코인", "카지노", "성인",
        "loan", "investment", "bitcoin", "casino",
    )
    private val marketingTerms = listOf(
        "광고", "무료", "당첨", "이벤트", "할인", "쿠폰", "캐시백", "리워드",
        "수신거부", "무료거부", "상담", "선착순", "경품", "winner", "prize",
        "unsubscribe",
    )

    fun classify(sender: String, body: String): Result {
        val normalized = normalize(body)
        val hasUrl = urlPattern.containsMatchIn(normalized)
        val financialHits = financialTerms.count { normalized.contains(it) }
        val marketingHits = marketingTerms.count { normalized.contains(it) }
        val hasOptOut = normalized.contains("수신거부") || normalized.contains("무료거부") ||
            normalized.contains("unsubscribe")
        val otpLike = otpPattern.containsMatchIn(normalized)

        // OTP messages are common and should not be classified as spam merely
        // because the sender or message contains a short verification URL.
        if (otpLike && financialHits == 0 && marketingHits == 0) {
            return Result(false, 0)
        }

        var score = 0
        val reasons = mutableListOf<String>()
        if (hasUrl) {
            score += 2
            reasons += "URL"
        }
        if (financialHits > 0) {
            score += minOf(3, financialHits * 2)
            reasons += "금융/도박 문구"
        }
        if (marketingHits > 0) {
            score += minOf(3, marketingHits)
            reasons += "홍보 문구"
        }
        if (hasOptOut) {
            score += 1
            reasons += "수신거부 문구"
        }
        if (Regex("[!！]{3,}|[☆★]{2,}").containsMatchIn(normalized)) {
            score += 1
            reasons += "과도한 홍보 기호"
        }

        val spam = (hasUrl && (financialHits > 0 || marketingHits > 0) && score >= 3) || score >= 4
        return Result(spam, score, if (spam) reasons.distinct().joinToString(" + ") else null)
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
}
