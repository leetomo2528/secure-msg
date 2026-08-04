package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test
    fun stripsSeparators() {
        assertEquals("01012345678", PhoneNumberNormalizer.normalize("010-1234-5678"))
        assertEquals("+821012345678", PhoneNumberNormalizer.normalize("+82 10-1234-5678"))
        assertEquals("01012345678", PhoneNumberNormalizer.normalize("(010) 1234.5678"))
    }

    @Test
    fun convertsInternationalDialPrefix() {
        assertEquals("+82101234567", PhoneNumberNormalizer.normalize("0082101234567"))
    }

    @Test
    fun keepsValidShortcodesAndStarHash() {
        assertTrue(Regex("^\\+?[0-9*#]{3,24}$").matches(PhoneNumberNormalizer.normalize("*1234#")))
    }

    @Test
    fun redactsAllButLastFour() {
        assertEquals("***5678", PhoneNumberNormalizer.redact("010-1234-5678"))
        assertEquals("***", PhoneNumberNormalizer.redact("1234"))
    }
}
