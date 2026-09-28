package com.yunjelee.securemsg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SM-2: after a logout and a re-login with the same sid, the SMS pull stopped
 * at the phone's own acknowledged upload, and every web message queued behind
 * it never reached the carrier.
 *
 * The ack writes run through the production [UploadAck.commit]; the store
 * below mirrors the SQL of the DAOs it stands for (Room behavior itself is
 * covered by SelfEchoRecoveryInstrumentedTest).
 */
class SelfEchoRecoveryTest {
    private class Store : UploadAckWrites {
        val outbox = linkedMapOf<Long, RelayOutbox>()
        val serverKeys = mutableSetOf<String>()
        val relayAcked = mutableSetOf<Pair<String, Int>>()

        fun insert(row: RelayOutbox) {
            outbox[row.id] = row
        }

        // RelayOutboxDao.markRelaySent
        override suspend fun markRelaySent(row: RelayOutbox, seq: Int) {
            outbox[row.id] = outbox.getValue(row.id).copy(
                relayState = "sent",
                serverSeq = seq,
                payload = "",
                plaintext = "",
                subject = null,
                attachmentsJson = null,
                lastError = null,
            )
        }

        // RelayAckDao.record
        override suspend fun recordAck(cid: String, seq: Int) {
            relayAcked += cid to seq
        }

        // IncomingMessageRepository.acknowledgeIncoming: ledgers, then delete.
        override suspend fun acknowledgeIncoming(row: RelayOutbox) {
            outbox.remove(row.id)
        }

        /** MainActivity's logout: messages, sms_threads, sent plaintext. */
        fun logout() {
            serverKeys.clear()
            outbox.replaceAll { _, row -> if (row.relayState == "sent") row.copy(plaintext = "") else row }
        }

        /** What processRelayEnvelope reads for an own-sid row at (cid, seq). */
        fun evidence(cid: String, seq: Int) = RelaySyncPolicy.SelfEchoEvidence(
            // MessageDao.hasServerKey
            hasLocalServerKey = "$cid:$seq" in serverKeys,
            // RelayOutboxDao.hasAcknowledgedSequence
            hasAcknowledgedOutbox = outbox.values.any {
                it.cid == cid && it.serverSeq == seq && it.relayState == "sent"
            },
            // RelayAckDao.contains
            hasDurableAck = (cid to seq) in relayAcked,
            // RelayOutboxDao.hasUnackedUpload
            hasUnackedUpload = outbox.values.any {
                it.cid == cid && it.relayState != "sent" && it.relayState != "unsendable"
            },
        )

        fun consumes(cid: String, seq: Int) = RelaySyncPolicy.canConsumeSelfEcho(evidence(cid, seq))
    }

    private fun row(id: Long, cid: String, direction: String = "incoming_sms", state: String = "pending") =
        RelayOutbox(
            id = id,
            mid = "mid-$id",
            cid = cid,
            payload = "{\"ct\":\"sealed\"}",
            plaintext = RelayContentCodec.encode(RelayContentCodec.text("본문 $id")),
            phoneNumber = "+821012345678",
            direction = direction,
            relayState = state,
        )

    /** flushOutbox's ack transaction for [row] at [seq]. */
    private suspend fun ack(store: Store, row: RelayOutbox, seq: Int) {
        store.serverKeys += "${row.cid}:$seq" // MessageDao.updateRelayResult
        UploadAck.commit(row, seq, store)
    }

    @Test
    fun `an upload acked before logout is still consumed as an echo after re-login`() = runBlocking {
        val store = Store()
        val uploaded = row(1, "sms_a")
        store.insert(uploaded)
        ack(store, uploaded, 12)
        // The incoming row is gone with its ack; only evidence remains.
        assertFalse(1L in store.outbox)

        store.logout()
        // Re-login: another SMS from the same number is prepared and on its
        // way up, so the legacy fallback alone would make the pull wait here.
        store.insert(row(2, "sms_a"))

        val evidence = store.evidence("sms_a", 12)
        assertFalse("serverKey is cleared by logout", evidence.hasLocalServerKey)
        assertFalse("the incoming outbox row was deleted", evidence.hasAcknowledgedOutbox)
        assertTrue(evidence.hasDurableAck)
        assertTrue(store.consumes("sms_a", 12))
    }

    @Test
    fun `an outgoing upload keeps its outbox evidence through logout`() = runBlocking {
        val store = Store()
        val sent = row(1, "sms_a", direction = "outgoing_sms")
        store.insert(sent)
        ack(store, sent, 30)
        store.logout()
        store.insert(row(2, "sms_a", direction = "outgoing_sms"))

        assertTrue(store.evidence("sms_a", 30).hasAcknowledgedOutbox)
        assertTrue(store.consumes("sms_a", 30))
    }

    @Test
    fun `an echo that arrives before its own ack commits waits, then is consumed`() = runBlocking {
        val store = Store()
        val inFlight = row(1, "sms_a")
        store.insert(inFlight)
        // The relay fanned the upload out before answering it.
        assertFalse(store.consumes("sms_a", 12))

        ack(store, inFlight, 12)
        assertTrue(store.consumes("sms_a", 12))
    }

    @Test
    fun `legacy fallback - no evidence and nothing pending in the conversation is history`() {
        // A gateway that logged out on v0.23.1: the ack left no relay_acked
        // record and logout cleared the serverKey.
        val store = Store()
        assertTrue(store.consumes("sms_a", 12))

        // Pending rows elsewhere do not hold this conversation back...
        store.insert(row(1, "sms_b"))
        store.insert(row(2, "local_" + "0".repeat(32)))
        // ...and neither does a row retired before it could ever be sent.
        store.insert(row(3, "sms_a", state = "unsendable"))
        assertTrue(store.consumes("sms_a", 12))
    }

    @Test
    fun `legacy fallback - a pending own upload in the conversation makes the pull wait`() {
        for (direction in listOf("incoming_sms", "incoming_mms", "outgoing_sms", "outgoing_mms")) {
            val store = Store()
            store.insert(row(1, "sms_a", direction = direction))
            assertFalse(direction, store.consumes("sms_a", 12))
        }
    }

    @Test
    fun `evidence is recorded under the cid the upload was acked in`() = runBlocking {
        val store = Store()
        val uploaded = row(1, "sms_a")
        store.insert(uploaded)
        ack(store, uploaded, 7)
        assertEquals(setOf("sms_a" to 7), store.relayAcked)
    }
}
