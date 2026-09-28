package com.yunjelee.securemsg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carrier half of the pull keyed relay_receipts by the history row's cid,
 * which a history row never has, so every receipt went to ("", seq) and one
 * conversation's receipt answered for another's row at the same seq.
 *
 * [pull] replays processRelayEnvelope's receipt steps for one send-request
 * row with the production pieces -- [RelaySyncPolicy.rowConversation],
 * [LegacyRelayReceipt.carryOver], the INSERT OR IGNORE claim and
 * [RelayReceiptRetryPolicy] -- over a store that mirrors the DAO SQL.
 */
class LegacyRelayReceiptTest {
    private class Store : LegacyRelayReceipt.Store {
        val receipts = linkedMapOf<Pair<String, Int>, RelayReceipt>()
        val rendered = mutableMapOf<String, LegacyRelayReceipt.Rendered>()
        val carrier = mutableListOf<String>()

        /** The system SMS store's sent-direction rows: (address, body, date). */
        val sentBox = mutableListOf<Triple<String, String, Long>>()

        override suspend fun receipt(cid: String, seq: Int) = receipts[cid to seq]
        override suspend fun rendered(serverKey: String) = rendered[serverKey]

        // Mirrors RelayReceiptDao.hasCopyElsewhere.
        override suspend fun adoptedElsewhere(cid: String, seq: Int, claimedAt: Long) =
            receipts.values.any { it.seq == seq && it.cid != cid && it.cid.isNotEmpty() && it.claimedAt == claimedAt }
        // Mirrors SmsProvider.hasSentTo.
        override suspend fun dispatchedTo(phoneNumber: String, text: String, from: Long, until: Long) =
            sentBox.any { (address, body, date) ->
                PhoneNumberNormalizer.normalize(address) == PhoneNumberNormalizer.normalize(phoneNumber) &&
                    body == text && date in from..until
            }

        override suspend fun claim(receipt: RelayReceipt): Long {
            val key = receipt.cid to receipt.seq
            if (key in receipts) return -1
            receipts[key] = receipt
            return receipts.size.toLong()
        }

        /**
         * What v0.23.1 left for a row it dispatched to [to]'s number: the
         * blank-cid receipt, the rendered row and, for an SMS, the system
         * store's sent row SmsSender wrote just after the claim.
         */
        fun legacyDispatch(
            seq: Int,
            text: String,
            createdAtMs: Long,
            claimedAt: Long,
            status: String = "sent",
            to: String = "sms_a",
            type: String = "text",
        ) {
            receipts[LegacyRelayReceipt.CID to seq] =
                RelayReceipt(LegacyRelayReceipt.CID, seq, claimedAt = claimedAt, status = status)
            rendered[LegacyRelayReceipt.serverKey(seq)] = rendering(text, createdAtMs, type = type)
            if (type == "text") sentBox += Triple(phoneOf(to), text, claimedAt + 150L)
        }
    }

    private companion object {
        const val WEB = "web-sid"

        /** Each test thread's number; sms_twin shares sms_a's, written the way a carrier might. */
        fun phoneOf(threadCid: String) = when (threadCid) {
            "sms_a" -> "010-1234-0001"
            "sms_twin" -> "+821012340001"
            "sms_b" -> "010-1234-0002"
            else -> "010-9999-0000"
        }

        fun rendering(text: String, createdAtMs: Long?, type: String = "text", subject: String? = null, sender: String = WEB) =
            LegacyRelayReceipt.Rendered(createdAtMs, text, type, subject, sender)
    }

    private enum class Outcome { DISPATCHED, CONSUMED, WAITS, REFUSED }

    private data class Row(
        val threadCid: String,
        val seq: Int,
        val text: String,
        val createdAtMs: Long,
        val rowCid: String = "",
        val type: String = "text",
        val subject: String? = null,
        val sender: String = WEB,
    )

    private suspend fun pull(store: Store, row: Row, now: Long = 9_000_000L): Outcome {
        val cid = RelaySyncPolicy.rowConversation(row.threadCid, row.rowCid) ?: return Outcome.REFUSED
        val decision = LegacyRelayReceipt.carryOver(
            cid, row.seq, phoneOf(row.threadCid),
            rendering(row.text, row.createdAtMs, row.type, row.subject, row.sender), store, now,
        )
        if (decision == LegacyRelayReceipt.Decision.WAIT) return Outcome.WAITS
        if (store.claim(RelayReceipt(cid, row.seq, claimedAt = now)) == -1L) {
            val receipt = store.receipt(cid, row.seq)!!
            return when (RelayReceiptRetryPolicy.action(receipt.status, false)) {
                RelayReceiptRetryPolicy.Action.CONSUME_RESOLVED -> Outcome.CONSUMED
                else -> Outcome.WAITS
            }
        }
        store.carrier += "$cid/${row.seq}:${row.text}"
        store.receipts[cid to row.seq] = store.receipts.getValue(cid to row.seq).copy(status = "sent")
        return Outcome.DISPATCHED
    }

    @Test
    fun `a history row without a cid is keyed by the pulled thread`() {
        assertEquals("sms_a", RelaySyncPolicy.rowConversation("sms_a", ""))
        assertEquals("sms_a", RelaySyncPolicy.rowConversation("sms_a", "sms_a"))
        assertNull(RelaySyncPolicy.rowConversation("sms_a", "sms_b"))
        assertNull(RelaySyncPolicy.rowConversation("", ""))
    }

    @Test
    fun `two conversations at the same seq each reach the carrier once`() = runBlocking {
        val store = Store()
        val a = Row("sms_a", 5, "to A", createdAtMs = 1_000_000L)
        val b = Row("sms_b", 5, "to B", createdAtMs = 2_000_000L)
        assertEquals(Outcome.DISPATCHED, pull(store, a))
        // Before: b's claim of ("", 5) collided with a's and was consumed unsent.
        assertEquals(Outcome.DISPATCHED, pull(store, b))
        // Re-walks (a reconnect, a re-login) send neither again.
        assertEquals(Outcome.CONSUMED, pull(store, a))
        assertEquals(Outcome.CONSUMED, pull(store, b))
        assertEquals(listOf("sms_a/5:to A", "sms_b/5:to B"), store.carrier)
        assertTrue((LegacyRelayReceipt.CID to 5) !in store.receipts)
    }

    @Test
    fun `a row already sent under the blank cid is not sent again, and the one it hid is`() = runBlocking {
        val store = Store()
        store.legacyDispatch(seq = 3, text = "to A", createdAtMs = 1_000_000L, claimedAt = 1_001_000L)
        val a = Row("sms_a", 3, "to A", createdAtMs = 1_000_000L)
        val b = Row("sms_b", 3, "to B", createdAtMs = 1_000_500L)

        assertEquals(Outcome.CONSUMED, pull(store, a))
        val adopted = store.receipts.getValue("sms_a" to 3)
        assertEquals("sent", adopted.status)
        assertEquals(1_001_000L, adopted.claimedAt)
        // Reported to the relay under a cid it accepts, which "" never was.
        assertEquals(false, adopted.statusSynced)

        // Created even before a's claim, but the rendered row says whose it was.
        assertEquals(Outcome.DISPATCHED, pull(store, b))
        assertEquals(listOf("sms_b/3:to B"), store.carrier)
        assertEquals(Outcome.CONSUMED, pull(store, b))
        assertEquals(listOf("sms_b/3:to B"), store.carrier)
    }

    @Test
    fun `without the rendered row nothing is sent again, whatever the two clocks say`() = runBlocking {
        val store = Store()
        // Dispatched by v0.23.1 on a phone whose clock ran 11 minutes slow:
        // claimed "before" the relay even created the row.
        val createdAt = 1_000_000_000L
        store.legacyDispatch(seq = 4, text = "to A", createdAtMs = createdAt, claimedAt = createdAt - 11 * 60_000L)
        store.rendered.clear() // logout cleared `messages`

        // The very row it dispatched: not sent a second time, shown as failed.
        assertEquals(Outcome.CONSUMED, pull(store, Row("sms_a", 4, "to A", createdAtMs = createdAt)))
        val receipt = store.receipts.getValue("sms_a" to 4)
        assertEquals("failed", receipt.status)
        assertEquals(LegacyRelayReceipt.UNCERTAIN_ERROR, receipt.lastError)

        // A row created days after the legacy claim is not told apart by time
        // either: failed and visible, never an automatic send.
        val later = Row("sms_b", 4, "to B", createdAtMs = createdAt + 3 * 24 * 3_600_000L)
        assertEquals(Outcome.CONSUMED, pull(store, later))
        assertEquals("failed", store.receipts.getValue("sms_b" to 4).status)
        assertEquals(emptyList<String>(), store.carrier)
    }

    @Test
    fun `a rendered-row match is sent only where the sent box names the thread, whichever is pulled first`() = runBlocking {
        // Same seq, same second, same text, same web device, two threads: the
        // rendered row cannot say which of them v0.23.1 dispatched. It went to A.
        for (bFirst in listOf(true, false)) {
            val store = Store()
            store.legacyDispatch(seq = 8, text = "on my way", createdAtMs = 2_000_000L, claimedAt = 2_001_000L, to = "sms_a")
            val a = Row("sms_a", 8, "on my way", createdAtMs = 2_000_000L)
            val b = Row("sms_b", 8, "on my way", createdAtMs = 2_000_000L)

            val order = if (bFirst) listOf(b, a) else listOf(a, b)
            for (row in order) assertEquals(Outcome.CONSUMED, pull(store, row))
            // B never left: never 'sent', shown as failed, and not sent now.
            val second = store.receipts.getValue("sms_b" to 8)
            assertEquals("bFirst=$bFirst", "failed", second.status)
            assertEquals(LegacyRelayReceipt.UNCERTAIN_ERROR, second.lastError)
            // A did: the legacy outcome is carried over.
            assertEquals("bFirst=$bFirst", "sent", store.receipts.getValue("sms_a" to 8).status)
            assertEquals(emptyList<String>(), store.carrier)
            // Re-walks keep both answers.
            assertEquals(Outcome.CONSUMED, pull(store, a))
            assertEquals(Outcome.CONSUMED, pull(store, b))
            assertEquals("sent", store.receipts.getValue("sms_a" to 8).status)
            assertEquals("failed", store.receipts.getValue("sms_b" to 8).status)
        }
    }

    @Test
    fun `a match without its sent-box row, or with one outside the claim window, is not marked sent`() = runBlocking {
        val store = Store()
        store.legacyDispatch(seq = 9, text = "see you", createdAtMs = 2_000_000L, claimedAt = 2_001_000L, to = "sms_a")
        store.sentBox.clear() // the owner deleted it from the system store
        assertEquals(Outcome.CONSUMED, pull(store, Row("sms_a", 9, "see you", createdAtMs = 2_000_000L)))
        assertEquals("failed", store.receipts.getValue("sms_a" to 9).status)

        // The same text to the same number, but not this dispatch: a minute later.
        store.legacyDispatch(seq = 10, text = "see you", createdAtMs = 2_000_000L, claimedAt = 2_001_000L, to = "sms_a")
        store.sentBox.clear()
        store.sentBox += Triple(phoneOf("sms_a"), "see you", 2_001_000L + LegacyRelayReceipt.DISPATCH_WINDOW_MS + 1)
        store.sentBox += Triple(phoneOf("sms_a"), "see you", 2_001_000L - LegacyRelayReceipt.DISPATCH_SLACK_MS - 1)
        assertEquals(Outcome.CONSUMED, pull(store, Row("sms_a", 10, "see you", createdAtMs = 2_000_000L)))
        assertEquals("failed", store.receipts.getValue("sms_a" to 10).status)

        // Another body to that number inside the window is not it either.
        store.legacyDispatch(seq = 11, text = "see you", createdAtMs = 2_000_000L, claimedAt = 2_001_000L, to = "sms_a")
        store.sentBox.clear()
        store.sentBox += Triple(phoneOf("sms_a"), "see you!", 2_001_200L)
        assertEquals(Outcome.CONSUMED, pull(store, Row("sms_a", 11, "see you", createdAtMs = 2_000_000L)))
        assertEquals("failed", store.receipts.getValue("sms_a" to 11).status)
        assertEquals(emptyList<String>(), store.carrier)
    }

    @Test
    fun `two conversations with the same number adopt the legacy receipt once`() = runBlocking {
        val store = Store()
        store.legacyDispatch(seq = 12, text = "ok", createdAtMs = 2_000_000L, claimedAt = 2_001_000L, to = "sms_a")
        // sms_twin's number normalizes to sms_a's, so the sent box names both.
        assertEquals(Outcome.CONSUMED, pull(store, Row("sms_twin", 12, "ok", createdAtMs = 2_000_000L)))
        assertEquals("sent", store.receipts.getValue("sms_twin" to 12).status)
        assertEquals(Outcome.CONSUMED, pull(store, Row("sms_a", 12, "ok", createdAtMs = 2_000_000L)))
        val second = store.receipts.getValue("sms_a" to 12)
        assertEquals("failed", second.status)
        assertEquals(LegacyRelayReceipt.UNCERTAIN_ERROR, second.lastError)
        assertEquals(emptyList<String>(), store.carrier)
    }

    @Test
    fun `an MMS match is never carried over as sent, whatever its attachments were`() = runBlocking {
        val store = Store()
        // v0.23.1 dispatched an MMS at seq 13 to A; the rendered row does not
        // cover attachments, so B's MMS with other photos matches it field for field.
        store.legacyDispatch(seq = 13, text = "photos", createdAtMs = 2_000_000L, claimedAt = 2_001_000L, to = "sms_a", type = "mms")
        // Even a coincidental SMS of the same text to each number is no evidence for an MMS.
        store.sentBox += Triple(phoneOf("sms_a"), "photos", 2_001_100L)
        store.sentBox += Triple(phoneOf("sms_b"), "photos", 2_001_100L)
        for (thread in listOf("sms_b", "sms_a")) {
            assertEquals(Outcome.CONSUMED, pull(store, Row(thread, 13, "photos", createdAtMs = 2_000_000L, type = "mms")))
            val receipt = store.receipts.getValue(thread to 13)
            assertEquals(thread, "failed", receipt.status)
            assertEquals(LegacyRelayReceipt.UNCERTAIN_ERROR, receipt.lastError)
        }
        assertEquals(emptyList<String>(), store.carrier)
    }

    @Test
    fun `a rendered row that differs in type, subject or sender is another message`() {
        val legacy = LegacyRelayReceipt.Dispatch("sent", claimedAt = 3_001_000L)
        val rendered = rendering("hi", 3_000_000L)
        assertEquals(
            LegacyRelayReceipt.Decision.ADOPT,
            LegacyRelayReceipt.decide(legacy, rendered, rendering("hi", 3_000_000L), dispatchedHere = true),
        )
        // A field-for-field match alone names no recipient.
        assertEquals(LegacyRelayReceipt.Decision.UNCERTAIN, LegacyRelayReceipt.decide(legacy, rendered, rendering("hi", 3_000_000L)))
        assertEquals(
            LegacyRelayReceipt.Decision.UNRELATED,
            LegacyRelayReceipt.decide(legacy, rendered, rendering("hi", 3_000_000L, type = "mms")),
        )
        assertEquals(
            LegacyRelayReceipt.Decision.UNRELATED,
            LegacyRelayReceipt.decide(legacy, rendered, rendering("hi", 3_000_000L, subject = "s")),
        )
        assertEquals(
            LegacyRelayReceipt.Decision.UNRELATED,
            LegacyRelayReceipt.decide(legacy, rendered, rendering("hi", 3_000_000L, sender = "other-web")),
        )
        assertEquals(
            LegacyRelayReceipt.Decision.UNCERTAIN,
            LegacyRelayReceipt.decide(
                legacy, rendered, rendering("hi", 3_000_000L), adoptedElsewhere = true, dispatchedHere = true,
            ),
        )
    }

    @Test
    fun `an unresolved legacy dispatch holds the row it may be, and a bare claim holds nothing`() = runBlocking {
        val store = Store()
        store.legacyDispatch(seq = 6, text = "to A", createdAtMs = 1_000_000L, claimedAt = 1_001_000L, status = "attempting")
        assertEquals(Outcome.WAITS, pull(store, Row("sms_a", 6, "to A", 1_000_000L)))
        assertTrue(("sms_a" to 6) !in store.receipts)
        // Another conversation's row at that seq is not held by it.
        assertEquals(Outcome.DISPATCHED, pull(store, Row("sms_b", 6, "to B", 1_000_200L)))

        // Its callback resolves the legacy key; the next pull adopts it.
        store.receipts[LegacyRelayReceipt.CID to 6] = store.receipts.getValue(LegacyRelayReceipt.CID to 6).copy(status = "sent")
        assertEquals(Outcome.CONSUMED, pull(store, Row("sms_a", 6, "to A", 1_000_000L)))

        // 'claimed' never reached the carrier API.
        store.receipts[LegacyRelayReceipt.CID to 7] = RelayReceipt(LegacyRelayReceipt.CID, 7, claimedAt = 1_001_000L)
        assertEquals(Outcome.DISPATCHED, pull(store, Row("sms_a", 7, "to A 7", 1_000_000L)))
        assertEquals(listOf("sms_b/6:to B", "sms_a/7:to A 7"), store.carrier)
    }

    @Test
    fun `a row without created_at is never matched away from a legacy dispatch`() {
        val legacy = LegacyRelayReceipt.Dispatch("sent", claimedAt = 1_001_000L)
        val rendered = rendering("to A", 1_000_000L)
        assertEquals(LegacyRelayReceipt.Decision.UNCERTAIN, LegacyRelayReceipt.decide(legacy, rendered, rendering("to B", null)))
        assertEquals(LegacyRelayReceipt.Decision.UNRELATED, LegacyRelayReceipt.decide(null, rendered, rendering("to B", null)))
    }
}
