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

    private fun ref(id: Long, bytes: Long = 100) = RelayOutboxRef(id, "mid-$id", bytes)

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
            refs = refs,
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
            refs = listOf(ref(1)),
            read = { throw CancellationException("flush cancelled") },
            skip = { _, _ -> fail("a cancelled flush must not record an attempt") },
        )
        try {
            cursor.next()
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }
}
