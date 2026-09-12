package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outgoing half of the size story: what this phone hands the carrier.
 *
 * [MmsSender.plan] is the whole decision, and it is pure by construction so it
 * can be pinned here. The two things it is not — the carrier-config read and
 * the bitmap re-encoder — enter through parameters, and the fallback of the
 * first is tested directly below.
 */
class MmsSenderTest {
    /** Records what the re-encoder was asked for, so "never called" is assertable. */
    private class Shrinks(
        private val answer: (Int) -> MmsSender.ReEncoded?,
    ) {
        val budgets = mutableListOf<Int>()
        val types = mutableListOf<String>()

        fun fn(): (ByteArray, String, Int) -> MmsSender.ReEncoded? = { _, type, budget ->
            types += type
            budgets += budget
            answer(budget)
        }
    }

    /** A re-encoder that always lands exactly on its budget. */
    private fun fits(type: String = "image/jpeg") = Shrinks { budget ->
        MmsSender.ReEncoded(ByteArray(budget) { 9 }, type)
    }

    private fun attachment(
        name: String,
        contentType: String,
        size: Int,
        fill: Byte = 7,
    ): RelayAttachment = RelayAttachment(
        name = name,
        contentType = contentType,
        data = RelayContentCodec.encodeBytes(ByteArray(size) { fill }),
        size = size,
    )

    private fun mms(
        text: String = "사진 보냅니다",
        subject: String? = null,
        attachments: List<RelayAttachment>,
    ) = RelayContent(
        type = RelayContentCodec.TYPE_MMS,
        text = text,
        subject = subject,
        attachments = attachments,
    )

    private fun budgetFor(content: RelayContent, maxMessageSize: Int): Int =
        MmsAttachmentBudget.forCarrier(
            maxMessageSize = maxMessageSize,
            textBytes = content.text.toByteArray(Charsets.UTF_8).size,
            subjectBytes = content.subject?.takeIf { it.isNotBlank() }
                ?.take(120)?.toByteArray(Charsets.UTF_8)?.size ?: 0,
            partCount = content.attachments.size + 1,
        )

    @Test
    fun carrierCeilingFallsBackToTheAospDefaultWhenTheFrameworkThrows() {
        assertEquals(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            MmsSender.carrierMaxMessageSize { throw IllegalStateException("no telephony") },
        )
    }

    @Test
    fun carrierCeilingFallsBackWhenTheFrameworkIsMissingEntirely() {
        // A stripped OEM build, or a device whose SmsManager class will not
        // load at all, arrives as an Error rather than an Exception. Falling
        // back matters more here than anywhere: every MMS on that handset
        // would otherwise fail before it was composed.
        assertEquals(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            MmsSender.carrierMaxMessageSize { throw NoClassDefFoundError("android.telephony.SmsManager") },
        )
    }

    @Test
    fun carrierCeilingIsUsedWhenTheFrameworkReportsOne() {
        assertEquals(614_400, MmsSender.carrierMaxMessageSize { 614_400 })
    }

    @Test
    fun aMessageThatAlreadyFitsIsHandedToTheComposerUntouched() {
        val content = mms(attachments = listOf(attachment("photo.jpg", "image/jpeg", 120 * 1024)))
        val shrinks = fits()

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            shrinks.fn(),
        ) as MmsSender.Fit.Ready

        // Identical instance: the common path must not decode, re-encode, or
        // even rebuild the list.
        assertSame(content.attachments, fit.attachments)
        assertTrue("nothing may be re-encoded when it already fits", shrinks.budgets.isEmpty())
    }

    @Test
    fun aPhotoUnderOurWireCapButOverTheCarrierCeilingIsShrunk() {
        // The defect in one test: 400 KiB is legal for RelayContentCodec
        // (512 KiB) and legal for the composer, and the AOSP-default MMSC
        // refuses it. Before this change it was dispatched anyway.
        val original = attachment("photo.jpg", "image/jpeg", 400 * 1024)
        assertTrue(original.size < RelayContentCodec.MAX_ATTACHMENT_BYTES)
        val content = mms(attachments = listOf(original))
        val shrinks = fits()

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            shrinks.fn(),
        ) as MmsSender.Fit.Ready

        assertEquals(1, shrinks.budgets.size)
        assertEquals("image/jpeg", shrinks.types.single())
        val budget = budgetFor(content, MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE)
        assertEquals(budget, shrinks.budgets.single())
        assertEquals(budget, fit.attachments.single().size)
        assertTrue(fit.attachments.single().size < original.size)
    }

    @Test
    fun aGenerousCarrierCeilingLeavesTheSamePhotoAlone() {
        val content = mms(attachments = listOf(attachment("photo.jpg", "image/jpeg", 400 * 1024)))
        val shrinks = fits()

        val fit = MmsSender.plan(content, 2 * 1024 * 1024, shrinks.fn()) as MmsSender.Fit.Ready

        assertSame(content.attachments, fit.attachments)
        assertTrue(shrinks.budgets.isEmpty())
    }

    @Test
    fun theStoredContentIsNotDegradedByWhatTheCarrierAllows() {
        val original = attachment("photo.jpg", "image/jpeg", 400 * 1024)
        val content = mms(attachments = listOf(original))
        val before = content.attachments.single()

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.Ready

        // The Room row and the outbox payload are built from `content`, and
        // they are what this account's other devices decrypt. Only the PDU may
        // carry the smaller copy.
        assertEquals(1, content.attachments.size)
        assertSame(before, content.attachments.single())
        assertEquals(400 * 1024, content.attachments.single().size)
        assertEquals(original.data, content.attachments.single().data)
        assertTrue(fit.attachments.single().size < content.attachments.single().size)
    }

    @Test
    fun aNonShrinkableAttachmentOverTheCeilingIsRejectedWithAKoreanReason() {
        val content = mms(attachments = listOf(attachment("clip.mp4", "video/mp4", 400 * 1024)))
        val shrinks = fits()

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            shrinks.fn(),
        ) as MmsSender.Fit.TooLarge

        // Never a bare false and never a bare integer: this string is written
        // to the receipt's carrier_error and rendered by the web.
        assertTrue(fit.reason.isNotBlank())
        assertTrue("reason must be Korean", fit.reason.any { it in '가'..'힣' })
        assertTrue("reason must name the limit", fit.reason.contains("KB"))
        assertTrue("a video may not be re-encoded", shrinks.budgets.isEmpty())
    }

    @Test
    fun anAnimatedGifTravelsWholeOrNotAtAll() {
        // Flattening a GIF to one still frame is not shrinking it, so an
        // over-ceiling GIF is a rejection rather than a silent still image.
        val content = mms(attachments = listOf(attachment("loop.gif", "image/gif", 400 * 1024)))
        val shrinks = fits()

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            shrinks.fn(),
        )

        assertTrue(fit is MmsSender.Fit.TooLarge)
        assertTrue(shrinks.budgets.isEmpty())
    }

    @Test
    fun aReEncoderThatCannotReachTheBudgetIsRejectedRatherThanDispatched() {
        val content = mms(attachments = listOf(attachment("photo.jpg", "image/jpeg", 400 * 1024)))
        val gaveUp = Shrinks { null }

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            gaveUp.fn(),
        ) as MmsSender.Fit.TooLarge

        assertEquals(1, gaveUp.budgets.size)
        assertTrue(fit.reason.contains("사진"))
    }

    @Test
    fun aReEncoderThatOvershootsItsBudgetIsNotTrusted() {
        val content = mms(attachments = listOf(attachment("photo.jpg", "image/jpeg", 400 * 1024)))
        val overshoots = Shrinks { budget ->
            MmsSender.ReEncoded(ByteArray(budget + 1) { 9 }, "image/jpeg")
        }

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            overshoots.fn(),
        )

        // ImageShrinker is the only impure thing in this path, and the cost of
        // trusting it wrongly is the carrier rejection this class prevents.
        assertTrue(fit is MmsSender.Fit.TooLarge)
    }

    @Test
    fun unreadableAttachmentDataResolvesInsteadOfFreezingTheCursor() {
        // The composer decodes the same base64 and would throw, and a throw in
        // MmsSender.send returns a bare false that strands the receipt at
        // 'attempting' — a cursor the owner cannot clear from the web.
        val content = mms(
            attachments = listOf(
                RelayAttachment(
                    name = "photo.jpg",
                    contentType = "image/jpeg",
                    data = "not base64 at all ***",
                    size = 400 * 1024,
                ),
            ),
        )

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        )

        assertTrue(fit is MmsSender.Fit.TooLarge)
        assertTrue((fit as MmsSender.Fit.TooLarge).reason.any { it in '가'..'힣' })
    }

    @Test
    fun severalPhotosSplitTheBudgetAndTheirTotalStillFitsIt() {
        val content = mms(
            attachments = listOf(
                attachment("a.jpg", "image/jpeg", 400 * 1024),
                attachment("b.jpg", "image/jpeg", 400 * 1024),
                attachment("c.jpg", "image/jpeg", 20 * 1024),
            ),
        )
        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.Ready

        val budget = budgetFor(content, MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE)
        assertEquals(3, fit.attachments.size)
        assertTrue(fit.attachments.sumOf { it.size } <= budget)
        // The 20 KiB thumbnail needed none of its even share and kept its own
        // bytes; ImageShrinkPolicy.allocate recredits the surplus to the two
        // photos beside it.
        assertSame(content.attachments[2], fit.attachments[2])
    }

    @Test
    fun aSmallFileBesideABigPhotoIsReservedWholeAndThePhotoPaysForIt() {
        val pdf = attachment("note.pdf", "application/pdf", 40 * 1024)
        val content = mms(attachments = listOf(pdf, attachment("photo.jpg", "image/jpeg", 400 * 1024)))

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.Ready

        val budget = budgetFor(content, MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE)
        assertSame(pdf, fit.attachments[0])
        assertEquals(budget - pdf.size, fit.attachments[1].size)
        assertTrue(fit.attachments.sumOf { it.size } <= budget)
    }

    @Test
    fun aFileTooBigToShrinkAroundRejectsTheWholeMessageRatherThanDropIt() {
        // Sending a subset of what the owner attached is the same silent loss
        // the incoming half of this change exists to end.
        val content = mms(
            attachments = listOf(
                attachment("note.pdf", "application/pdf", 400 * 1024),
                attachment("photo.jpg", "image/jpeg", 100 * 1024),
            ),
        )

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        )

        assertTrue(fit is MmsSender.Fit.TooLarge)
    }

    @Test
    fun aReEncodeThatChangesTheMediaTypeChangesTheExtensionWithIt() {
        // MmsPduComposer writes the name into both the Content-Type name
        // parameter and the Content-Location, so a receiver that saves the part
        // by name would otherwise write JPEG bytes into a file called .heic.
        val content = mms(attachments = listOf(attachment("여행.heic", "image/heic", 400 * 1024)))

        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.Ready

        assertEquals("여행.jpg", fit.attachments.single().name)
        assertEquals("image/jpeg", fit.attachments.single().contentType)
    }

    @Test
    fun aKoreanCaptionIsChargedInBytesAgainstTheSameCeiling() {
        val photo = attachment("photo.jpg", "image/jpeg", 400 * 1024)
        val plain = MmsSender.plan(
            mms(text = "", attachments = listOf(photo)),
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.Ready
        val captioned = MmsSender.plan(
            mms(text = "가".repeat(1000), attachments = listOf(photo)),
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.Ready

        // 3 bytes per Korean character, not one.
        assertEquals(3000, plain.attachments.single().size - captioned.attachments.single().size)
    }

    @Test
    fun aSubjectIsChargedTooAndTruncatedTheWayTheComposerTruncatesIt() {
        val photo = attachment("photo.jpg", "image/jpeg", 400 * 1024)
        val short = MmsSender.plan(
            mms(text = "", subject = "제목", attachments = listOf(photo)),
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.Ready
        val overLong = MmsSender.plan(
            mms(text = "", subject = "제".repeat(400), attachments = listOf(photo)),
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.Ready

        // The composer writes subject.take(120), so nothing past 120 characters
        // may be charged against the photo's budget.
        assertEquals(
            (120 - 2) * 3,
            short.attachments.single().size - overLong.attachments.single().size,
        )
    }

    @Test
    fun theFittedCeilingTravelsWithTheDecisionThatChoseIt() {
        val fit = MmsSender.plan(
            mms(attachments = listOf(attachment("photo.jpg", "image/jpeg", 10 * 1024))),
            614_400,
            fits().fn(),
        ) as MmsSender.Fit.Ready

        // MmsSender.send measures the composed PDU against this.
        assertEquals(614_400, fit.maxMessageSize)
    }

    @Test
    fun aFittedMessageReallyComposesUnderTheCarrierCeiling() {
        // The end of the chain: MmsAttachmentBudget's PDU overhead is an
        // estimate, and this is the only place it meets the composer that has
        // to live inside it. A fitted set that composes over the ceiling would
        // be the same carrier rejection with extra steps.
        val content = mms(
            text = "가족 사진 보냅니다. 확인해 주세요.",
            subject = "주말 사진",
            attachments = listOf(
                attachment("a.jpg", "image/jpeg", 400 * 1024),
                attachment("b.png", "image/png", 400 * 1024),
                attachment("c.webp", "image/webp", 400 * 1024),
            ),
        )
        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.Ready

        val pdu = MmsPduComposer.compose(
            to = "+821012345678",
            subject = content.subject,
            text = content.text,
            attachments = fit.attachments,
        )
        assertTrue(
            "composed ${pdu.size}B must fit ${fit.maxMessageSize}B",
            pdu.size <= fit.maxMessageSize,
        )
        // And the estimate must not be so loose that it wastes a quarter of the
        // message on margin the composer never uses.
        assertTrue(pdu.size > fit.maxMessageSize - 2048)
    }

    @Test
    fun aTextOnlyMmsIsNeverRejectedForSize() {
        val fit = MmsSender.plan(
            mms(text = "제목만 있는 메시지", subject = "제목", attachments = emptyList()),
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        )

        assertTrue(fit is MmsSender.Fit.Ready)
        assertTrue((fit as MmsSender.Fit.Ready).attachments.isEmpty())
    }

    @Test
    fun aBogusCarrierReportCannotMakeEveryPhotoUnsendable() {
        // An absent carrier-config key reads back as 0. MmsAttachmentBudget
        // clamps it; this pins that the clamp survives the whole path, because
        // a budget of zero here would reject every MMS on the handset.
        val content = mms(attachments = listOf(attachment("photo.jpg", "image/jpeg", 400 * 1024)))
        val fit = MmsSender.plan(content, 0, fits().fn())

        assertTrue(fit is MmsSender.Fit.Ready)
        val ready = fit as MmsSender.Fit.Ready
        assertTrue(ready.attachments.single().size > 0)
        assertTrue(ready.attachments.single().size < MmsAttachmentBudget.MIN_MAX_MESSAGE_SIZE)
    }

    @Test
    fun theRejectionReasonStaysInsideTheRelayCarrierErrorColumn() {
        // The server truncates carrier_error at 300 characters; a reason cut in
        // half there would reach the web as a fragment.
        val content = mms(
            text = "가".repeat(5_000),
            attachments = listOf(attachment("clip.mp4", "video/mp4", 400 * 1024)),
        )
        val fit = MmsSender.plan(
            content,
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            fits().fn(),
        ) as MmsSender.Fit.TooLarge

        assertNotNull(fit.reason)
        assertTrue("reason is ${fit.reason.length} characters", fit.reason.length <= 300)
        assertFalse(fit.reason.contains("\n"))
    }
}
