package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationTargetResolverTest {
    private val local = SmsThread(
        cid = "local_1",
        phoneNumber = "+821012345678",
        serverName = null,
    )
    private val authoritative = SmsThread(
        cid = "server_1",
        phoneNumber = "+821099998888",
        serverName = null,
    )

    @Test
    fun `exact cid wins over matching phone`() {
        val resolved = ConversationTargetResolver.resolve(
            listOf(local, authoritative),
            ConversationTarget(
                cid = "server_1",
                normalizedPhone = "010-1234-5678",
                requestId = "message-1",
            ),
        )

        assertEquals(authoritative, resolved)
    }

    @Test
    fun `canonical phone survives local to server cid replacement`() {
        val migrated = local.copy(cid = "server_migrated")
        val resolved = ConversationTargetResolver.resolve(
            listOf(migrated),
            ConversationTarget(
                cid = "local_1",
                normalizedPhone = "010-1234-5678",
                requestId = "message-2",
            ),
        )

        assertEquals(migrated, resolved)
    }

    @Test
    fun `missing destination waits for a later Room emission`() {
        assertNull(
            ConversationTargetResolver.resolve(
                emptyList(),
                ConversationTarget("local_1", "+821012345678", "message-3"),
            ),
        )
    }
}
