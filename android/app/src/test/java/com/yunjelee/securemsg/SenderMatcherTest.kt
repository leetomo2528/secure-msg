package com.yunjelee.securemsg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderMatcherTest {
    @Test
    fun exactAndFormattedMatch() {
        assertTrue(SenderMatcher.matches("01012345678", "01012345678"))
        assertTrue(SenderMatcher.matches("010-1234-5678", "01012345678"))
        assertTrue(SenderMatcher.matches("+821012345678", "010-1234-5678"))
    }

    @Test
    fun legacyAndCanonicalKoreanRowsMatchInBothDirections() {
        assertTrue(SenderMatcher.matches("+821012345678", "01012345678"))
        assertTrue(SenderMatcher.matches("01012345678", "+821012345678"))
    }

    @Test
    fun differentNumbersNeverMatch() {
        assertFalse(SenderMatcher.matches("01012345678", "01012345679"))
        assertFalse(SenderMatcher.matches("+821011112222", "+821033334444"))
        assertFalse(SenderMatcher.matches("1234", "5678"))
    }

    @Test
    fun blankOrShortInputsAreSafe() {
        assertFalse(SenderMatcher.matches("", "01012345678"))
        assertFalse(SenderMatcher.matches("01012345678", ""))
        // <9 digits on either side: only exact equality counts.
        assertFalse(SenderMatcher.matches("1234", "91234"))
    }

    @Test
    fun internationalNumbersWithTheSameNineDigitTailDoNotMatch() {
        assertFalse(SenderMatcher.matches("+442025550123", "+12025550123"))
    }

    @Test
    fun alphanumericSenderIdsMatchExactlyWithNfkcAndCaseFolding() {
        assertTrue(SenderMatcher.matches("ＢＡＮＫ０１０", "bank010"))
        assertFalse(SenderMatcher.matches("BANK01012345678", "01012345678"))
        assertFalse(SenderMatcher.matches("MYBANK", "BANK"))
    }
}
