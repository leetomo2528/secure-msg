package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessageIdentityTest {
    @Test
    fun `provider event identity is stable across retries`() {
        val firstIdentity = ProviderIdentity.snapshot("sms", 0, 42, "+821012345678", 100, "first")
        val retryIdentity = ProviderIdentity.snapshot("sms", 0, 42, "different", 200, "changed")
        val first = IncomingMessageIdentity.mid("incoming_sms", firstIdentity, "+821012345678", 100, "first")
        val retry = IncomingMessageIdentity.mid("incoming_sms", retryIdentity, "different", 200, "changed")

        assertEquals(first, retry)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{16,64}")))
    }

    @Test
    fun `sms and mms provider namespaces cannot collide`() {
        val smsIdentity = ProviderIdentity.snapshot("sms", 0, 42, "+821012345678", 100, "body")
        val mmsIdentity = ProviderIdentity.snapshot("mms", 0, 42, "+821012345678", 100, "body")
        val sms = IncomingMessageIdentity.mid("incoming_sms", smsIdentity, "+821012345678", 100, "body")
        val mms = IncomingMessageIdentity.mid("incoming_mms", mmsIdentity, "+821012345678", 100, "body")

        assertNotEquals(sms, mms)
    }

    @Test
    fun `provider-less broadcasts dedupe exact redelivery only`() {
        val firstIdentity = ProviderIdentity.snapshot("sms", 0, null, "+821012345678", 100, "body")
        val retryIdentity = ProviderIdentity.snapshot("sms", 0, null, "+821012345678", 100, "body")
        val nextIdentity = ProviderIdentity.snapshot("sms", 0, null, "+821012345678", 101, "body")
        val first = IncomingMessageIdentity.mid("incoming_sms", firstIdentity, "+821012345678", 100, "body")
        val retry = IncomingMessageIdentity.mid("incoming_sms", retryIdentity, "+821012345678", 100, "body")
        val next = IncomingMessageIdentity.mid("incoming_sms", nextIdentity, "+821012345678", 101, "body")

        assertEquals(first, retry)
        assertNotEquals(first, next)
    }

    @Test
    fun `new provider epoch separates a reused numeric id while epoch zero stays legacy compatible`() {
        val legacy = ProviderIdentity.snapshot("sms", 0, 42, "+821012345678", 100, "body")
        val reused = ProviderIdentity.snapshot("sms", 1, 42, "+821012345678", 200, "new body")

        val epochZeroMid = IncomingMessageIdentity.mid("incoming_sms", legacy, "ignored", 0, "ignored")
        val epochOneMid = IncomingMessageIdentity.mid("incoming_sms", reused, "ignored", 0, "ignored")
        assertEquals("in_" + sha256("incoming_sms\u0000provider\u000042").take(61), epochZeroMid)
        assertNotEquals(epochZeroMid, epochOneMid)
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
