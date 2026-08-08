package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessageIdentityTest {
    @Test
    fun `provider event identity is stable across retries`() {
        val first = IncomingMessageIdentity.mid("incoming_sms", 42, "+821012345678", 100, "first")
        val retry = IncomingMessageIdentity.mid("incoming_sms", 42, "different", 200, "changed")

        assertEquals(first, retry)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{16,64}")))
    }

    @Test
    fun `sms and mms provider namespaces cannot collide`() {
        val sms = IncomingMessageIdentity.mid("incoming_sms", 42, "+821012345678", 100, "body")
        val mms = IncomingMessageIdentity.mid("incoming_mms", 42, "+821012345678", 100, "body")

        assertNotEquals(sms, mms)
    }

    @Test
    fun `provider-less broadcasts dedupe exact redelivery only`() {
        val first = IncomingMessageIdentity.mid("incoming_sms", null, "+821012345678", 100, "body")
        val retry = IncomingMessageIdentity.mid("incoming_sms", null, "+821012345678", 100, "body")
        val next = IncomingMessageIdentity.mid("incoming_sms", null, "+821012345678", 101, "body")

        assertEquals(first, retry)
        assertNotEquals(first, next)
    }
}
