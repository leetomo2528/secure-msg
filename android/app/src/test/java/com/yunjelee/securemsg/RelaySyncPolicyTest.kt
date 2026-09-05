package com.yunjelee.securemsg

import org.json.JSONObject
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

    /** Exactly what syncConversation reads out of one row of a history page. */
    private fun serverRowAction(row: JSONObject, cursor: Int = 7) = RelaySyncPolicy.rowAction(
        expectedCid = "sms_1",
        rowCid = row.optString("cid"),
        seq = row.optInt("seq", -1),
        cursor = cursor,
        senderSid = row.optString("sender_sid"),
        payloadIsObject = row.optJSONObject("payload") != null,
    )

    /**
     * The exact column set store.fetch_messages_since selects, in the shape
     * GET /conversation/<cid>/messages wraps it in. There is no per-row `cid`:
     * the response carries it once.
     */
    private fun serverRow() = JSONObject(
        """
        {"id": 41, "seq": 8, "conv_id": 3, "sender_id": 2, "sender_sid": "device_2",
         "sender_pub_key": "cHVi", "payload": {"ct": "Y3Q", "nonce": "bg", "keys": {}},
         "created_at": 1757030400, "carrier_status": "none", "carrier_error": null,
         "carrier_updated_at": null}
        """.trimIndent(),
    )

    @Test
    fun `a history row as the relay actually sends it is processed`() {
        assertEquals(RelaySyncPolicy.RowAction.PROCESS, serverRowAction(serverRow()))
    }

    @Test
    fun `a history row naming another conversation still stops the batch`() {
        val foreign = serverRow().put("cid", "sms_other")
        assertEquals(RelaySyncPolicy.RowAction.RETRY_BATCH, serverRowAction(foreign))
    }

    @Test
    fun `self echo requires durable local acknowledgement evidence`() {
        assertFalse(RelaySyncPolicy.canConsumeSelfEcho(false, false))
        assertTrue(RelaySyncPolicy.canConsumeSelfEcho(true, false))
        assertTrue(RelaySyncPolicy.canConsumeSelfEcho(false, true))
    }

    @Test
    fun `an undispatched row from another device kind is a send request`() {
        assertTrue(RelaySyncPolicy.isCarrierSendRequest("web", "none"))
        assertTrue(RelaySyncPolicy.isCarrierSendRequest("web", ""))
    }

    @Test
    fun `a row a gateway uploaded is never resent to the carrier`() {
        // Incoming SMS and this phone's own outbound copies both arrive with a
        // gateway as sender; a replacement gateway must not send them again.
        assertFalse(RelaySyncPolicy.isCarrierSendRequest("android_gateway", "none"))
        assertFalse(RelaySyncPolicy.isCarrierSendRequest("android_gateway", ""))
    }

    @Test
    fun `a row a gateway already dispatched is never resent`() {
        for (status in listOf("queued", "dispatched", "sent", "delivered", "failed", "unknown")) {
            assertFalse(status, RelaySyncPolicy.isCarrierSendRequest("web", status))
        }
    }

    @Test
    fun `a carrier status the relay can never accept is not waited on`() {
        // The unsynced queue is oldest-first and shared by every conversation:
        // waiting on a permanent refusal stops the web from ever seeing
        // sent/delivered again.
        assertFalse(RelayReceiptRetryPolicy.isRetryableAckError("message not found"))
        assertFalse(
            RelayReceiptRetryPolicy.isRetryableAckError(
                "carrier status requires an SMS conversation",
            ),
        )
        assertFalse(RelayReceiptRetryPolicy.isRetryableAckError("invalid carrier status"))
    }

    @Test
    fun `a carrier status that failed in transit is retried`() {
        assertTrue(RelayReceiptRetryPolicy.isRetryableAckError("relay disconnected"))
        assertTrue(
            RelayReceiptRetryPolicy.isRetryableAckError("carrier status acknowledgement timeout"),
        )
        assertTrue(RelayReceiptRetryPolicy.isRetryableAckError("no carrier status ack"))
        assertTrue(RelayReceiptRetryPolicy.isRetryableAckError("unauthenticated"))
        // An ack with no reason at all is not evidence the relay refused it.
        assertTrue(RelayReceiptRetryPolicy.isRetryableAckError(""))
    }
}
