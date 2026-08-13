package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySyncPolicyTest {
    private fun action(
        cid: String = "sms_1",
        seq: Int = 8,
        cursor: Int = 7,
        senderSid: String = "device_2",
        payloadIsObject: Boolean = true,
    ) = RelaySyncPolicy.rowAction("sms_1", cid, seq, cursor, senderSid, payloadIsObject)

    @Test
    fun `valid next row is processed`() {
        assertEquals(RelaySyncPolicy.RowAction.PROCESS, action())
    }

    @Test
    fun `already consumed row may be skipped`() {
        assertEquals(RelaySyncPolicy.RowAction.SKIP_ALREADY_CONSUMED, action(seq = 7))
    }

    @Test
    fun `malformed or foreign rows stop batch for retry`() {
        val retry = RelaySyncPolicy.RowAction.RETRY_BATCH
        assertEquals(retry, action(cid = "foreign"))
        assertEquals(retry, action(seq = 0))
        assertEquals(retry, action(senderSid = ""))
        assertEquals(retry, action(payloadIsObject = false))
    }

    @Test
    fun `self echo requires durable local acknowledgement evidence`() {
        assertFalse(RelaySyncPolicy.canConsumeSelfEcho(false, false))
        assertTrue(RelaySyncPolicy.canConsumeSelfEcho(true, false))
        assertTrue(RelaySyncPolicy.canConsumeSelfEcho(false, true))
    }
}
