package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
