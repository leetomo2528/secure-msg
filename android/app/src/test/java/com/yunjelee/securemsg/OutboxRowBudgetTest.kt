package com.yunjelee.securemsg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * SM-1: one relay_outbox row larger than the 2 MiB CursorWindow stopped the
 * whole outbox for good. These pin the row's size and the isolation of a row
 * that still cannot be read; the Room side is OutboxRowBudgetInstrumentedTest.
 */
class OutboxRowBudgetTest {
    private val identity = ProviderIdentity(
        kind = ProviderIdentity.MMS,
        epoch = 0,
        id = 501L,
        fingerprint = "fp",
        eventKey = "ek",
    )

    private fun attachment(bytes: Int) = RelayAttachment(
        name = "photo.jpg",
        contentType = "image/jpeg",
        data = RelayContentCodec.encodeBytes(Random(7).nextBytes(bytes)),
        size = bytes,
    )

    private fun mms(text: String, attachmentBytes: Int) = RelayContent(
        type = RelayContentCodec.TYPE_MMS,
        text = text,
        subject = "제".repeat(120),
        attachments = listOf(attachment(attachmentBytes)),
    )

    /** The incoming outbox row exactly as IncomingMessageRepository writes it. */
    private fun incomingRow(content: RelayContent) = IncomingMessageRepository.outboxRow(
        mid = "in_" + "a".repeat(40),
        cid = "local_" + "b".repeat(32),
        phone = "+821012345678",
        content = content,
        payload = content,
        providerIdentity = identity,
        localMessageId = 1L,
        direction = "incoming_mms",
        receivedAt = 1_757_000_000_000L,
    )

    /** What markPrepared makes of it: the envelope for 16 devices added. */
    private fun prepared(row: RelayOutbox): RelayOutbox = row.copy(
        payload = "x".repeat(
            OutboxRowBudget.envelopeBytes(OutboxRowBudget.utf8Length(row.plaintext), 16).toInt(),
        ),
    )

    private val maxAttachments = RelayContentCodec.MAX_ATTACHMENT_BYTES

    @Test
    fun `a prepared 512 KiB MMS with the longest Korean text fits the cursor window`() {
        val content = mms("가".repeat(20_000), maxAttachments)
        val row = prepared(incomingRow(content))

        assertNull(row.attachmentsJson)
        val bytes = OutboxRowBudget.largeColumnBytes(row)
        assertTrue("row=$bytes", OutboxRowBudget.fits(bytes))
        assertTrue("row=$bytes", bytes < OutboxRowBudget.CURSOR_WINDOW_BYTES)
    }

    @Test
    fun `even text made only of escaped characters fits`() {
        // org.json writes a control character as \u00XX: six bytes per char,
        // the most any of the 20_000 characters can cost in the encoding.
        val content = mms("\u0001".repeat(20_000), maxAttachments)
        val row = prepared(incomingRow(content))

        val bytes = OutboxRowBudget.largeColumnBytes(row)
        assertTrue("row=$bytes", OutboxRowBudget.fits(bytes))
    }

    @Test
    fun `the old layout with a second attachment copy outgrew the window`() {
        // What v0.23.1 wrote: the same row plus attachmentsJson. This is the
        // row that wedged the outbox, from about 461 KiB of attachments on.
        for ((text, bytes) in listOf("가".repeat(20_000) to maxAttachments, "사진" to 461 * 1024)) {
            val content = mms(text, bytes)
            val old = prepared(incomingRow(content)).copy(
                attachmentsJson = RelayContentCodec.attachmentsJson(content),
            )
            val size = OutboxRowBudget.largeColumnBytes(old)
            assertTrue("attachments=$bytes row=$size", size > OutboxRowBudget.CURSOR_WINDOW_BYTES)
            // ...and the same message without the copy reads back fine.
            assertTrue(OutboxRowBudget.fits(OutboxRowBudget.largeColumnBytes(prepared(incomingRow(content)))))
        }
    }

    @Test
    fun `utf8Length is the encoded byte count`() {
        for (value in listOf("", "ascii", "가나다", "é", "😀 emoji", "mixed 가 é 😀 \u0001")) {
            assertEquals(value, value.toByteArray(Charsets.UTF_8).size.toLong(), OutboxRowBudget.utf8Length(value))
        }
        assertEquals(0L, OutboxRowBudget.utf8Length(null))
    }

    @Test
    fun `the envelope estimate bounds the real envelope`() {
        assumeTrue("host libsodium unavailable", HostSodium.available)
        val sender = CryptoUtil.generateKeypair()
        val recipients = (1..5).map { CryptoUtil.Recipient("sid-$it-" + "s".repeat(40), CryptoUtil.generateKeypair().boxPk) }
        for (plaintext in listOf("", "hello", "가".repeat(5_000), "x".repeat(300_001))) {
            val json = CryptoUtil.envelopeToJson(CryptoUtil.encryptMessage(plaintext, recipients, sender)).toString()
            val estimate = OutboxRowBudget.envelopeBytes(OutboxRowBudget.utf8Length(plaintext), recipients.size)
            assertTrue("json=${json.length} estimate=$estimate", json.length <= estimate)
        }
    }

    private fun ref(id: Long, bytes: Long = 100) = RelayOutboxRef(id, "mid-$id", bytes, createdAt = 1_000L + id)

    /** RelayOutboxDao.pendingRefs over an in-memory pending set: key > after, oldest first. */
    private class FakeOutbox(refs: List<RelayOutboxRef>) {
        val pending = refs.sortedWith(compareBy({ it.createdAt }, { it.id })).toMutableList()
        var pageCalls = 0

        fun page(after: OutboxPageKey?, limit: Int): List<RelayOutboxRef> {
            pageCalls += 1
            return pending.filter { after == null || it.pageKey.isAfter(after) }.take(limit)
        }
    }

    /**
     * One flush as SmsBridgeService.flushOutbox drives it: every row handed
     * out is passed to [handle], which returns true when the row left the
     * pending set (acked). Returns the ids handed out and where to resume.
     */
    private suspend fun flush(
        outbox: FakeOutbox,
        start: OutboxPageKey?,
        unreadable: Set<Long> = emptySet(),
        skipped: MutableList<Long> = mutableListOf(),
        handle: (Long) -> Boolean = { true },
    ): Pair<List<Long>, OutboxPageKey?> {
        val cursor = OutboxRowCursor(
            page = outbox::page,
            read = { id ->
                if (id in unreadable) throw IllegalStateException("Row too big to fit into CursorWindow")
                id
            },
            skip = { ref, _ -> skipped += ref.id },
            start = start,
        )
        val handed = mutableListOf<Long>()
        while (true) {
            val id = cursor.next() ?: break
            handed += id
            if (handle(id)) outbox.pending.removeAll { it.id == id }
        }
        return handed to cursor.resumeAfter
    }

    @Test
    fun `an unreadable or oversized row is recorded and the rest of the page still flushes`() = runBlocking {
        val refs = listOf(
            ref(1),
            ref(2),
            ref(3, OutboxRowBudget.CURSOR_WINDOW_BYTES + 1),
            ref(4),
            ref(5),
        )
        val reads = mutableListOf<Long>()
        val skipped = mutableListOf<Pair<Long, String>>()
        val cursor = OutboxRowCursor(
            page = FakeOutbox(refs)::page,
            read = { id ->
                reads += id
                when (id) {
                    // What SQLiteCursor throws for a row over the window.
                    2L -> throw IllegalStateException("Row too big to fit into CursorWindow")
                    4L -> null // deleted since the page was listed
                    else -> "row-$id"
                }
            },
            skip = { ref, reason -> skipped += ref.id to reason },
        )

        val flushed = mutableListOf<String>()
        while (true) flushed += cursor.next() ?: break

        assertEquals(listOf("row-1", "row-5"), flushed)
        // The oversized row is never handed to a cursor at all.
        assertEquals(listOf(1L, 2L, 4L, 5L), reads)
        assertEquals(
            listOf(
                2L to "${OutboxRowCursor.UNREADABLE}: IllegalStateException",
                3L to OutboxRowCursor.OVERSIZED,
            ),
            skipped,
        )
    }

    @Test
    fun `cancellation is not mistaken for an unreadable row`() = runBlocking {
        val cursor = OutboxRowCursor<String>(
            page = FakeOutbox(listOf(ref(1)))::page,
            read = { throw CancellationException("flush cancelled") },
            skip = { _, _ -> fail("a cancelled flush must not record an attempt") },
        )
        try {
            cursor.next()
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `a hundred unreadable rows at the head do not hide the rows behind them`() = runBlocking {
        // The review's reproduction: 100 rows that stay pending after a skip,
        // then good rows. A fixed LIMIT 100 page returned only the 100, on
        // every flush, forever.
        val outbox = FakeOutbox((1L..103L).map { ref(it) })
        val unreadable = (1L..100L).toSet()
        var resume: OutboxPageKey? = null
        repeat(3) { pass ->
            val skipped = mutableListOf<Long>()
            val (handed, next) = flush(outbox, resume, unreadable, skipped)
            if (pass == 0) {
                assertEquals(listOf(101L, 102L, 103L), handed)
                assertEquals((1L..100L).toList(), skipped)
            } else {
                // Only the unreadable rows are left; they are still recorded
                // on every pass, and nothing is handed out twice.
                assertEquals(emptyList<Long>(), handed)
                assertEquals(100, skipped.size)
            }
            // Every pass covered the whole set, so the next starts at the head.
            assertNull(next)
            resume = next
        }
        assertEquals((1L..100L).toList(), outbox.pending.map { it.id })
    }

    @Test
    fun `rows that stay pending cannot fill every flush - the next flush resumes behind them`() = runBlocking {
        // 150 readable rows, the first 100 of which are deferred on every
        // attempt (no relay ack): they used to be the whole page each time.
        val outbox = FakeOutbox((1L..150L).map { ref(it) })
        val deferred = (1L..100L).toSet()
        val (first, resume1) = flush(outbox, null) { it !in deferred }
        assertEquals((1L..100L).toList(), first)
        assertEquals(OutboxPageKey(1_100L, 100L), resume1)

        val (second, resume2) = flush(outbox, resume1) { it !in deferred }
        // 101..150 first, then round to the head for the rest of the budget.
        assertEquals((101L..150L).toList() + (1L..50L).toList(), second)
        assertEquals(OutboxPageKey(1_050L, 50L), resume2)

        // The rotation carries on from 51, round the end and back up to 50,
        // where this round began: every still-pending row got its turn, none
        // twice, and the next flush starts at the head again.
        val (third, resume3) = flush(outbox, resume2) { it !in deferred }
        assertEquals((51L..100L).toList() + (1L..50L).toList(), third)
        assertNull(resume3)
        assertEquals((1L..100L).toList(), outbox.pending.map { it.id })

        val (fourth, _) = flush(outbox, resume3)
        assertEquals((1L..100L).toList(), fourth)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `the refs budget bounds one flush and the next one reaches the rest`() = runBlocking {
        val bad = (1L..1_500L).toSet()
        val outbox = FakeOutbox((1L..1_501L).map { ref(it) })
        val skipped1 = mutableListOf<Long>()
        val (first, resume1) = flush(outbox, null, bad, skipped1)
        assertEquals(emptyList<Long>(), first)
        assertEquals(OutboxRowCursor.MAX_REFS_PER_FLUSH, skipped1.size)
        assertEquals(OutboxPageKey(2_000L, 1_000L), resume1)

        val skipped2 = mutableListOf<Long>()
        val (second, _) = flush(outbox, resume1, bad, skipped2)
        assertEquals(listOf(1_501L), second)
        assertEquals((1_001L..1_500L).toList(), skipped2.take(500))
    }

    @Test
    fun `a round that starts mid-set stops where it began`() = runBlocking {
        val outbox = FakeOutbox((1L..5L).map { ref(it) })
        val (handed, resume) = flush(outbox, OutboxPageKey(1_003L, 3L)) { false }
        assertEquals(listOf(4L, 5L, 1L, 2L, 3L), handed)
        assertNull(resume)
    }
}
