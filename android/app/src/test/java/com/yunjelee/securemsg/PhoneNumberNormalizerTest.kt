package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test
    fun stripsSeparators() {
        assertEquals("+821012345678", PhoneNumberNormalizer.normalize("010-1234-5678"))
        assertEquals("+821012345678", PhoneNumberNormalizer.normalize("+82 10-1234-5678"))
        assertEquals("+821012345678", PhoneNumberNormalizer.normalize("(010) 1234.5678"))
    }

    @Test
    fun convertsInternationalDialPrefix() {
        assertEquals("+82101234567", PhoneNumberNormalizer.normalize("0082101234567"))
        assertEquals("+12025550123", PhoneNumberNormalizer.normalize("0012025550123"))
    }

    @Test
    fun canonicalizesKoreanLocalAndCountryCodeVariants() {
        val mobile = "+821012345678"
        assertEquals(mobile, PhoneNumberNormalizer.normalize("01012345678"))
        assertEquals(mobile, PhoneNumberNormalizer.normalize("+821012345678"))
        assertEquals(mobile, PhoneNumberNormalizer.normalize("00821012345678"))
        assertEquals(mobile, PhoneNumberNormalizer.normalize("821012345678"))
        assertEquals(mobile, PhoneNumberNormalizer.normalize("+8201012345678"))

        assertEquals("+82212345678", PhoneNumberNormalizer.normalize("02-1234-5678"))
        assertEquals("+823112345678", PhoneNumberNormalizer.normalize("031-1234-5678"))
    }

    @Test
    fun canonicalizesKorean050xVirtualNumbers() {
        assertEquals("+8250712345678", PhoneNumberNormalizer.normalize("0507-1234-5678"))
        assertEquals("+8250712345678", PhoneNumberNormalizer.normalize("+82 507 1234 5678"))
    }

    @Test
    fun keepsValidShortcodesAndStarHash() {
        assertEquals("*1234#", PhoneNumberNormalizer.normalize("*1234#"))
        assertEquals("15881234", PhoneNumberNormalizer.normalize("1588-1234"))
        assertEquals("+12025550123", PhoneNumberNormalizer.normalize("+1 202-555-0123"))
        assertTrue(Regex("^\\+?[0-9*#]{3,24}$").matches(PhoneNumberNormalizer.normalize("*1234#")))
    }

    @Test
    fun redactsAllButLastFour() {
        assertEquals("***5678", PhoneNumberNormalizer.redact("010-1234-5678"))
        assertEquals("***", PhoneNumberNormalizer.redact("1234"))
    }
}
