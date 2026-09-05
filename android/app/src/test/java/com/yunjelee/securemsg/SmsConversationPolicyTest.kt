package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsConversationPolicyTest {
    @Test
    fun `accepts a self-only phone conversation`() {
        assertEquals(
            "+821012345678",
            SmsConversationPolicy.ownedPhone("+82 10-1234-5678", listOf("alice"), "alice"),
        )
        assertEquals(
            "+821012345678",
            SmsConversationPolicy.ownedPhone("01012345678", listOf("alice"), "alice"),
        )
    }

    @Test
    fun `rejects a conversation containing another user`() {
        assertNull(
            SmsConversationPolicy.ownedPhone(
                "+821012345678",
                listOf("alice", "mallory"),
                "alice",
            ),
        )
    }

    @Test
    fun `rejects another user's phone conversation`() {
        assertNull(
            SmsConversationPolicy.ownedPhone("+821012345678", listOf("mallory"), "alice"),
        )
    }

    @Test
    fun `applies the shared address rule to the conversation name`() {
        assertEquals("*1234#", SmsConversationPolicy.ownedPhone("*1234#", listOf("a"), "a"))
        assertNull(SmsConversationPolicy.ownedPhone("Google", listOf("a"), "a"))
        assertNull(SmsConversationPolicy.ownedPhone("news@bank.co.kr", listOf("a"), "a"))
    }

    /**
     * The gateway-ownership gate, OutgoingSmsDispatcher, SmsBridgeService's relay
     * filters and 설정's sender rules all read this one pattern, so the accepted
     * shapes are pinned in one place: a later tightening has to break this test
     * rather than move one gate and leave the security one on the old rule. The
     * predicate judges an already-normalized value, hence the spaced form fails.
     */
    @Test
    fun `pins the shared carrier-address shape`() {
        assertTrue(PhoneNumberNormalizer.isSmsAddress("+821012345678"))
        assertTrue(PhoneNumberNormalizer.isSmsAddress("15881588"))
        assertTrue(PhoneNumberNormalizer.isSmsAddress("*1234#"))
        assertTrue(PhoneNumberNormalizer.isSmsAddress("+" + "1".repeat(24)))
        assertFalse(PhoneNumberNormalizer.isSmsAddress("+" + "1".repeat(25)))
        assertFalse(PhoneNumberNormalizer.isSmsAddress("12"))
        assertFalse(PhoneNumberNormalizer.isSmsAddress("Google"))
        assertFalse(PhoneNumberNormalizer.isSmsAddress("news@bank.co.kr"))
        assertFalse(PhoneNumberNormalizer.isSmsAddress("+82 10 1234 5678"))
        assertFalse(PhoneNumberNormalizer.isSmsAddress(""))
    }
}
