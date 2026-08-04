package com.yunjelee.securemsg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamClassifierTest {
    @Test
    fun marketingUrlIsSpam() {
        val result = SpamClassifier.classify(
            "+821012345678",
            "[광고] 무료 당첨 이벤트 https://example.invalid 수신거부",
        )
        assertTrue(result.isSpam)
        assertTrue(result.reason!!.contains("URL"))
    }

    @Test
    fun financialMessageIsSpam() {
        assertTrue(
            SpamClassifier.classify("01012345678", "저금리 대출 상담 가능합니다").isSpam,
        )
    }

    @Test
    fun ordinaryOtpIsNotSpam() {
        assertFalse(
            SpamClassifier.classify("15881234", "인증번호 123456를 입력하세요").isSpam,
        )
    }

    @Test
    fun ordinaryMessageIsNotSpam() {
        assertFalse(
            SpamClassifier.classify("01012345678", "오늘 7시에 만나요").isSpam,
        )
    }
}
