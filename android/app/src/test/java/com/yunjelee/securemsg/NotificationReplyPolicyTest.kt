package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationReplyPolicyTest {
    @Test
    fun `surrounding whitespace is trimmed before sending`() {
        assertEquals("안녕", NotificationReplyPolicy.replyText("  안녕 \n"))
    }

    @Test
    fun `interior whitespace survives the trim`() {
        assertEquals("two words", NotificationReplyPolicy.replyText(" two words "))
    }

    @Test
    fun `a missing RemoteInput result sends nothing`() {
        assertNull(NotificationReplyPolicy.replyText(null))
    }

    @Test
    fun `a whitespace-only reply sends nothing`() {
        assertNull(NotificationReplyPolicy.replyText(" \n\t "))
    }
}
