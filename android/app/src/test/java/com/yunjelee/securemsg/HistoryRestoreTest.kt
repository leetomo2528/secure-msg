package com.yunjelee.securemsg

import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebuild's judgement calls, with the database and the platform taken out.
 *
 * Getting any of these wrong is worse than the bug they exist to fix: a wrong
 * direction renders a received SMS as one this account sent, a wrong dedupe
 * doubles every conversation, and a colliding key overwrites a relay message.
 */
class HistoryRestoreTest {

    private val incoming = Telephony.Sms.MESSAGE_TYPE_INBOX
    private val outgoing = Telephony.Sms.MESSAGE_TYPE_SENT

    // -- direction --------------------------------------------------------

    @Test
    fun `provider type maps to the direction the store recorded`() {
        assertEquals(HistoryRestorePlan.Direction.INCOMING, HistoryRestorePlan.directionOf(incoming))
        assertEquals(HistoryRestorePlan.Direction.OUTGOING, HistoryRestorePlan.directionOf(outgoing))
    }

    @Test
    fun `a type that is not delivered history has no direction`() {
        // Drafts, outbox, failed and queued rows are composition state. Guessing
        // a direction for them is what mislabels a message in the conversation.
        listOf(
            Telephony.Sms.MESSAGE_TYPE_ALL,
            Telephony.Sms.MESSAGE_TYPE_DRAFT,
            Telephony.Sms.MESSAGE_TYPE_OUTBOX,
            Telephony.Sms.MESSAGE_TYPE_FAILED,
            Telephony.Sms.MESSAGE_TYPE_QUEUED,
            -7,
        ).forEach { assertNull("type=$it", HistoryRestorePlan.directionOf(it)) }
    }

    @Test
    fun `direction decides which side of the conversation a row lands on`() {
        assertTrue(candidate(providerId = 1, type = outgoing).mine)
        assertFalse(candidate(providerId = 1, type = incoming).mine)
    }

    // -- seq / serverKey scheme -------------------------------------------

    @Test
    fun `the restored sequence cannot be mistaken for a relay one`() {
        // The relay assigns from 1 up; a local row awaiting one carries 0.
        assertTrue(HistoryRestorePlan.RESTORED_SEQ < 0)
    }

    @Test
    fun `a restored key cannot collide with a relay key for the same conversation`() {
        val cid = "conv-abcdef123456"
        val relayKeys = (0..5000).map { "$cid:$it" }.toSet()
        val restoredKeys = (0L..5000L)
            .map { HistoryRestorePlan.serverKey(it, fingerprint(body = "본문 $it")) }
            .toSet()

        assertTrue(relayKeys.intersect(restoredKeys).isEmpty())
        assertEquals(5001, restoredKeys.size)
        val fp = fingerprint()
        assertEquals(
            "restore:sms:42:" + fp.take(HistoryRestorePlan.FINGERPRINT_KEY_CHARS),
            HistoryRestorePlan.serverKey(42, fp),
        )
    }

    @Test
    fun `a reused provider id carrying other content is a different message`() {
        // The AOSP sms table reuses rowids and the messages index makes
        // serverKey unique with INSERT OR REPLACE behind it: an id-only key
        // would delete the message restored under the old id, and that message
        // would come back attributed to the new sender. The row's own
        // fingerprint separates the two without waiting for the live ingest
        // path to observe the reuse first — the reusing row may never reach it.
        val old = providerRow(842, "예전 것", 1_000L, incoming)
        val reused = providerRow(842, "새 것", 9_000_000L, incoming, address = "15889999")
        assertFalse(keyOf(old) == keyOf(reused))

        val index = RestoreDedupeIndex(
            listOf(row("c1", mine = false, text = "예전 것", at = 1_000L, serverKey = keyOf(old))),
            SELF_SID,
        )
        assertTrue(index.isDuplicate(candidateOf(old)))
        assertFalse(index.isDuplicate(candidateOf(reused)))
    }

    @Test
    fun `a restored key does not embed the conversation so a thread merge cannot break it`() {
        // A provisional local_ thread is merged into the relay cid later, and
        // the dedupe link has to survive that rewrite.
        val key = HistoryRestorePlan.serverKey(9, fingerprint())
        assertFalse(key.contains("local_"))
        assertTrue(HistoryRestorePlan.isRestored(key))
        assertFalse(HistoryRestorePlan.isRestored("conv-1:9"))
        assertFalse(HistoryRestorePlan.isRestored(null))
    }

    // -- dedupe -----------------------------------------------------------

    @Test
    fun `a message already present from the live receive path is not restored again`() {
        val existing = listOf(row(cid = "c1", mine = false, text = "안녕", at = 1_000L))
        val index = RestoreDedupeIndex(existing, SELF_SID)

        assertTrue(index.isDuplicate(candidate(1, "c1", "안녕", 1_000L, incoming)))
    }

    @Test
    fun `direction is part of identity so an echoed body is not eaten`() {
        val index = RestoreDedupeIndex(listOf(row("c1", mine = false, text = "네", at = 1_000L)), SELF_SID)

        // The reply that says the same thing back is a different message.
        assertFalse(index.isDuplicate(candidate(1, "c1", "네", 1_000L, outgoing)))
    }

    @Test
    fun `the same body in another conversation is a different message`() {
        val index = RestoreDedupeIndex(listOf(row("c1", mine = false, text = "네", at = 1_000L)), SELF_SID)

        assertFalse(index.isDuplicate(candidate(1, "c2", "네", 1_000L, incoming)))
    }

    @Test
    fun `a sent row matches its local copy across relay clock skew`() {
        // A sent message's local row is stamped with the relay's created_at,
        // the provider row with the moment this device wrote it.
        val index = RestoreDedupeIndex(listOf(row("c1", mine = true, text = "출발", at = 100_000L)), SELF_SID)

        assertTrue(index.isDuplicate(candidate(1, "c1", "출발", 100_000L + 4_000L, outgoing)))
    }

    @Test
    fun `the same text sent again much later is its own message`() {
        val index = RestoreDedupeIndex(listOf(row("c1", mine = true, text = "출발", at = 100_000L)), SELF_SID)

        val muchLater = 100_000L + HistoryRestorePlan.SAME_CLOCK_MATCH_WINDOW_MS + 1
        assertFalse(index.isDuplicate(candidate(1, "c1", "출발", muchLater, outgoing)))
    }

    @Test
    fun `a relay send this phone dispatched hours later is not restored twice`() {
        // Composed on another device at 02:00 and stamped with the relay's
        // created_at; this phone was in Doze and only handed it to the carrier
        // at 02:35, which is the date the provider row carries.
        val composed = 1_700_000_000_000L
        val index = RestoreDedupeIndex(
            listOf(row("c1", mine = true, text = "출발", at = composed, senderSid = "sid-tablet")),
            SELF_SID,
        )

        assertTrue(index.isDuplicate(candidate(1, "c1", "출발", composed + 35 * 60_000L, outgoing)))
    }

    @Test
    fun `a send made here does not absorb an identical one from yesterday`() {
        // Both timestamps come from this device's clock, so the wide gateway
        // tolerance must not apply: consuming today's slot for yesterday's row
        // would leave today's message rendered twice.
        val today = 1_700_000_000_000L
        val index = RestoreDedupeIndex(
            listOf(row("c1", mine = true, text = "네", at = today, senderSid = SELF_SID)),
            SELF_SID,
        )

        assertFalse(index.isDuplicate(candidate(1, "c1", "네", today - 14 * 3_600_000L, outgoing)))
        assertTrue(index.isDuplicate(candidate(2, "c1", "네", today, outgoing)))
    }

    @Test
    fun `an incoming row keeps the tight window it can afford`() {
        // The live import stamps createdAt with the same provider date this
        // reads, so a received message 35 minutes off is a different one.
        val at = 1_700_000_000_000L
        val index = RestoreDedupeIndex(listOf(row("c1", mine = false, text = "도착", at = at)), SELF_SID)

        assertFalse(index.isDuplicate(candidate(1, "c1", "도착", at + 35 * 60_000L, incoming)))
        assertTrue(HistoryRestorePlan.SAME_CLOCK_MATCH_WINDOW_MS < HistoryRestorePlan.RELAY_CLOCK_MATCH_WINDOW_MS)
    }

    @Test
    fun `a matched local row is consumed so real repeats still come back`() {
        // Sent "네" three times; only one local copy survived. Two are missing
        // and both must be restored.
        val index = RestoreDedupeIndex(listOf(row("c1", mine = true, text = "네", at = 1_000L)), SELF_SID)

        assertTrue(index.isDuplicate(candidate(1, "c1", "네", 1_000L, outgoing)))
        assertFalse(index.isDuplicate(candidate(2, "c1", "네", 1_500L, outgoing)))
        assertFalse(index.isDuplicate(candidate(3, "c1", "네", 2_000L, outgoing)))
    }

    @Test
    fun `every candidate is new against an empty conversation`() {
        val index = RestoreDedupeIndex(emptyList(), SELF_SID)

        assertFalse(index.isDuplicate(candidate(1, "c1", "안녕", 1_000L, incoming)))
        assertFalse(index.isDuplicate(candidate(2, "c1", "잘 지내", 2_000L, outgoing)))
    }

    // -- idempotency ------------------------------------------------------

    @Test
    fun `a second restore pass adds nothing`() {
        val provider = listOf(
            providerRow(11, "안녕", 1_000L, incoming),
            providerRow(12, "응 안녕", 2_000L, outgoing),
            providerRow(13, "네", 3_000L, outgoing),
            providerRow(14, "네", 3_500L, outgoing),
        )

        val first = pass(existing = emptyList(), provider = provider)
        assertEquals(4, first.restored.size)
        assertEquals(0, first.skipped)

        // Exactly what the first pass wrote to Room is what the second reads.
        val second = pass(existing = first.restored, provider = provider)
        assertEquals(0, second.restored.size)
        assertEquals(4, second.skipped)

        val third = pass(existing = first.restored + second.restored, provider = provider)
        assertEquals(0, third.restored.size)
    }

    @Test
    fun `a pass over a conversation the live path already rebuilt adds only the gap`() {
        // Post-logout reality: the startup inbox import restored the newest
        // incoming rows and nothing else — no sent rows, nothing older.
        val alreadyThere = listOf(
            row("c1", mine = false, text = "안녕", at = 3_000L),
            row("c1", mine = false, text = "잘 지내?", at = 4_000L),
        )
        val provider = listOf(
            providerRow(1, "예전 메시지", 1_000L, incoming),
            providerRow(2, "예전에 보낸 것", 2_000L, outgoing),
            providerRow(3, "안녕", 3_000L, incoming),
            providerRow(4, "잘 지내?", 4_000L, incoming),
        )

        val first = pass(existing = alreadyThere, provider = provider)
        assertEquals(2, first.restored.size)
        assertEquals(2, first.skipped)
        assertEquals(listOf("예전 메시지", "예전에 보낸 것"), first.restored.map { it.plaintext })

        val second = pass(existing = alreadyThere + first.restored, provider = provider)
        assertEquals(0, second.restored.size)
        assertEquals(4, second.skipped)
    }

    @Test
    fun `restored rows carry the local-only shape and never pending outbox work`() {
        val restored = pass(
            existing = emptyList(),
            provider = listOf(
                providerRow(7, "받은 것", 1_000L, incoming),
                providerRow(8, "보낸 것", 2_000L, outgoing),
            ),
        ).restored

        restored.forEach {
            assertEquals(HistoryRestorePlan.RESTORED_SEQ, it.seq)
            assertTrue(HistoryRestorePlan.isRestored(it.serverKey))
            assertFalse(it.blocked)
            assertEquals(RelayContentCodec.TYPE_TEXT, it.contentType)
        }
        // "queued" would invite a retry of a message sent years ago; "none" is
        // what an incoming row carries everywhere else.
        assertEquals("none", restored.single { !it.mine }.carrierStatus)
        assertEquals("sent", restored.single { it.mine }.carrierStatus)
    }

    // -- what a stored row may become -------------------------------------

    @Test
    fun `an alphanumeric sender id is a conversation, exactly as it is when it arrives live`() {
        // The live receive path gates only on a non-blank address, so "Google"
        // is a real thread with real messages. Refusing it here would leave
        // those conversations unrebuildable and count them as failures.
        assertEquals("Google", HistoryRestorePlan.conversationAddress(providerRow(1, "코드 123456", 1_000L, incoming, address = "Google")))
        assertEquals("15881234", HistoryRestorePlan.conversationAddress(providerRow(2, "안내", 1_000L, incoming, address = "15881234")))
        assertEquals("+821012345678", HistoryRestorePlan.conversationAddress(providerRow(3, "안녕", 1_000L, incoming, address = "010-1234-5678")))
    }

    @Test
    fun `a row that cannot become a message has no conversation`() {
        assertNull(HistoryRestorePlan.conversationAddress(providerRow(1, "안녕", 1_000L, incoming, address = "  ")))
        assertNull(HistoryRestorePlan.conversationAddress(providerRow(2, "   ", 1_000L, incoming)))
        assertNull(HistoryRestorePlan.conversationAddress(providerRow(3, "안녕", 0L, incoming)))
        assertNull(HistoryRestorePlan.conversationAddress(providerRow(4, "안녕", 1_000L, Telephony.Sms.MESSAGE_TYPE_DRAFT)))
        // Longer than the content codec accepts; encoding it would throw and
        // roll the whole conversation back.
        val huge = "가".repeat(HistoryRestorePlan.MAX_BODY_CHARS + 1)
        assertNull(HistoryRestorePlan.conversationAddress(providerRow(5, huge, 1_000L, incoming)))
    }

    // -- what the user is told --------------------------------------------

    @Test
    fun `a store that could not be read is never reported as an empty store`() {
        val unread = HistoryRestore.Outcome(ok = false, partial = true, error = "저장소를 읽지 못했습니다")
        assertTrue(HistoryRestoreRunner.message(unread).startsWith("대화 복원 실패"))

        val truncated = HistoryRestore.Outcome(ok = true, partial = true)
        assertFalse(truncated.let(HistoryRestoreRunner::message).contains("복원할 SMS가 없습니다"))
    }

    @Test
    fun `a truncated read says so instead of implying the rest was not there`() {
        val partial = HistoryRestore.Outcome(
            ok = true, restored = 412, skipped = 0, conversations = 3, partial = true,
        )
        assertTrue(HistoryRestoreRunner.message(partial).contains("끝까지 읽지 못해"))

        val whole = partial.copy(partial = false)
        assertFalse(HistoryRestoreRunner.message(whole).contains("끝까지 읽지 못해"))
        assertTrue(HistoryRestoreRunner.message(whole).contains("412"))
    }

    @Test
    fun `an empty store is only asserted after a complete read`() {
        val empty = HistoryRestore.Outcome(ok = true)
        assertEquals("이 기기의 메시지 저장소에 복원할 SMS가 없습니다.", HistoryRestoreRunner.message(empty))
    }

    // -- helpers ----------------------------------------------------------

    private data class PassResult(val restored: List<MessageRow>, val skipped: Int)

    /**
     * The insert loop of HistoryRestore.restoreConversation with Room removed:
     * one index over what the conversation holds, one decision per stored row.
     */
    private fun pass(existing: List<MessageRow>, provider: List<ProviderSmsRecord>): PassResult {
        val index = RestoreDedupeIndex(existing, SELF_SID)
        val restored = mutableListOf<MessageRow>()
        var skipped = 0
        for (record in provider) {
            val direction = HistoryRestorePlan.directionOf(record.type) ?: continue
            val candidate = RestoreCandidate(
                providerId = record.id,
                fingerprint = HistoryRestorePlan.fingerprintOf(record),
                cid = "c1",
                body = record.body,
                at = record.date,
                direction = direction,
            )
            if (index.isDuplicate(candidate)) {
                skipped += 1
                continue
            }
            restored += MessageRow(
                cid = candidate.cid,
                seq = HistoryRestorePlan.RESTORED_SEQ,
                senderSid = if (candidate.mine) "sid-self" else "",
                plaintext = candidate.body,
                createdAt = candidate.at,
                mine = candidate.mine,
                contentType = RelayContentCodec.TYPE_TEXT,
                serverKey = candidate.serverKey,
                carrierStatus = if (candidate.mine) "sent" else "none",
            )
        }
        return PassResult(restored, skipped)
    }

    private fun providerRow(
        id: Long,
        body: String,
        date: Long,
        type: Int,
        address: String = DEFAULT_ADDRESS,
    ) = ProviderSmsRecord(id = id, address = address, body = body, date = date, type = type)

    private fun candidate(
        providerId: Long,
        cid: String = "c1",
        body: String = "안녕",
        at: Long = 1_000L,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        address: String = DEFAULT_ADDRESS,
    ) = RestoreCandidate(
        providerId = providerId,
        fingerprint = fingerprint(body = body, at = at, address = address),
        cid = cid,
        body = body,
        at = at,
        direction = requireNotNull(HistoryRestorePlan.directionOf(type)),
    )

    /** The candidate the restorer itself would build for a stored row. */
    private fun candidateOf(record: ProviderSmsRecord, cid: String = "c1") = RestoreCandidate(
        providerId = record.id,
        fingerprint = HistoryRestorePlan.fingerprintOf(record),
        cid = cid,
        body = record.body,
        at = record.date,
        direction = requireNotNull(HistoryRestorePlan.directionOf(record.type)),
    )

    private fun keyOf(record: ProviderSmsRecord) =
        HistoryRestorePlan.serverKey(record.id, HistoryRestorePlan.fingerprintOf(record))

    /** The ledger's own hash of a row; the half of a restored key the id is not. */
    private fun fingerprint(
        body: String = "안녕",
        at: Long = 1_000L,
        address: String = DEFAULT_ADDRESS,
    ) = HistoryRestorePlan.fingerprintOf(providerRow(0, body, at, incoming, address))

    /**
     * A row already on this device. [senderSid] decides which clock stamped it:
     * this device's sid means the send was made in this app's composer.
     */
    private fun row(
        cid: String,
        mine: Boolean,
        text: String,
        at: Long,
        serverKey: String? = null,
        senderSid: String = if (mine) SELF_SID else "",
    ) = MessageRow(
        cid = cid,
        seq = if (serverKey == null) 0 else 1,
        senderSid = senderSid,
        plaintext = text,
        createdAt = at,
        mine = mine,
        serverKey = serverKey,
    )

    private companion object {
        const val SELF_SID = "sid-self"
        const val DEFAULT_ADDRESS = "+821012345678"
    }
}
