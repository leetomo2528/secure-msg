package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsAttachmentBudgetTest {
    @Test
    fun aospDefaultCeilingStillFitsA240KiBPhotoWithACaption() {
        val caption = "사진 보냅니다".toByteArray(Charsets.UTF_8).size
        val budget = MmsAttachmentBudget.forCarrier(
            maxMessageSize = MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            textBytes = caption,
            subjectBytes = 0,
            partCount = 2,
        )
        assertTrue("240 KiB must fit under the AOSP default", budget >= 240 * 1024)
        // Pins the overhead constants: 307200 - 256 - 96*2 - caption.
        assertEquals(307_200 - 256 - 192 - caption, budget)
    }

    @Test
    fun aospDefaultIsBelowOurOwnWireCap() {
        // The reason this object exists at all.
        assertTrue(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE < RelayContentCodec.MAX_ATTACHMENT_BYTES,
        )
    }

    @Test
    fun koreanCaptionCostsUtf8BytesNotCharacters() {
        val caption = "가".repeat(20_000)
        val bytes = caption.toByteArray(Charsets.UTF_8).size
        assertEquals(60_000, bytes)

        val empty = MmsAttachmentBudget.forCarrier(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            textBytes = 0,
            subjectBytes = 0,
            partCount = 2,
        )
        val withCaption = MmsAttachmentBudget.forCarrier(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            textBytes = bytes,
            subjectBytes = 0,
            partCount = 2,
        )
        assertEquals(60_000, empty - withCaption)

        val byCharCount = MmsAttachmentBudget.forCarrier(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            textBytes = caption.length,
            subjectBytes = 0,
            partCount = 2,
        )
        assertEquals(40_000, byCharCount - withCaption)
    }

    @Test
    fun subjectBytesAreChargedSeparatelyFromText() {
        val subject = "사진".toByteArray(Charsets.UTF_8).size
        assertEquals(6, subject)
        val withSubject = MmsAttachmentBudget.forCarrier(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            textBytes = 10,
            subjectBytes = subject,
            partCount = 2,
        )
        val withoutSubject = MmsAttachmentBudget.forCarrier(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            textBytes = 10,
            subjectBytes = 0,
            partCount = 2,
        )
        assertEquals(subject, withoutSubject - withSubject)
    }

    @Test
    fun eachAdditionalPartCostsItsHeaderBlock() {
        val one = MmsAttachmentBudget.forCarrier(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE, 0, 0, partCount = 1,
        )
        val nine = MmsAttachmentBudget.forCarrier(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE, 0, 0,
            partCount = RelayContentCodec.MAX_ATTACHMENTS + 1,
        )
        assertEquals(96 * 8, one - nine)
    }

    @Test
    fun tightCarrierCeilingForcesASmallerBudget() {
        val tight = MmsAttachmentBudget.forCarrier(102_400, textBytes = 20, subjectBytes = 0, partCount = 2)
        val default = MmsAttachmentBudget.forCarrier(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            textBytes = 20,
            subjectBytes = 0,
            partCount = 2,
        )
        assertEquals(102_400 - 256 - 192 - 20, tight)
        assertTrue(tight < default)
        assertTrue("a 100 KiB carrier must not admit a 240 KiB photo", tight < 240 * 1024)
    }

    @Test
    fun absurdCeilingsClampIntoRange() {
        val floor = MmsAttachmentBudget.forCarrier(MmsAttachmentBudget.MIN_MAX_MESSAGE_SIZE, 0, 0, 2)
        // A missing carrier-config key reads back as 0.
        assertEquals(floor, MmsAttachmentBudget.forCarrier(0, 0, 0, 2))
        assertEquals(floor, MmsAttachmentBudget.forCarrier(-1_000_000, 0, 0, 2))
        assertEquals(floor, MmsAttachmentBudget.forCarrier(1_024, 0, 0, 2))
        assertTrue(floor > 0)

        val cap = MmsAttachmentBudget.forCarrier(MmsAttachmentBudget.MAX_MAX_MESSAGE_SIZE, 0, 0, 2)
        assertEquals(cap, MmsAttachmentBudget.forCarrier(Int.MAX_VALUE, 0, 0, 2))
        assertEquals(cap, MmsAttachmentBudget.forCarrier(64 * 1024 * 1024, 0, 0, 2))
    }

    @Test
    fun budgetNeverGoesBelowZero() {
        assertEquals(
            0,
            MmsAttachmentBudget.forCarrier(
                MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
                textBytes = Int.MAX_VALUE,
                subjectBytes = 0,
                partCount = 2,
            ),
        )
        assertEquals(
            0,
            MmsAttachmentBudget.forCarrier(
                MmsAttachmentBudget.MIN_MAX_MESSAGE_SIZE,
                textBytes = 0,
                subjectBytes = Int.MAX_VALUE,
                partCount = 0,
            ),
        )
        assertEquals(
            0,
            MmsAttachmentBudget.forCarrier(
                MmsAttachmentBudget.MIN_MAX_MESSAGE_SIZE,
                textBytes = 0,
                subjectBytes = 0,
                partCount = 100_000,
            ),
        )
    }

    @Test
    fun negativeCountsDoNotBuyBackBudget() {
        val clean = MmsAttachmentBudget.forCarrier(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE, 0, 0, 0,
        )
        assertEquals(
            clean,
            MmsAttachmentBudget.forCarrier(
                MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
                textBytes = -5_000,
                subjectBytes = -5_000,
                partCount = -5,
            ),
        )
        // A negative count must not cancel out a real one either.
        assertEquals(
            MmsAttachmentBudget.forCarrier(MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE, 0, 0, 2),
            MmsAttachmentBudget.forCarrier(
                MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
                textBytes = -400,
                subjectBytes = 0,
                partCount = 2,
            ),
        )
    }

    @Test
    fun budgetNeverExceedsTheCarrierCeiling() {
        listOf(0, 1_024, 51_200, 102_400, 307_200, 2 * 1024 * 1024, Int.MAX_VALUE).forEach { reported ->
            val budget = MmsAttachmentBudget.forCarrier(reported, 0, 0, 1)
            val ceiling = reported.coerceIn(
                MmsAttachmentBudget.MIN_MAX_MESSAGE_SIZE,
                MmsAttachmentBudget.MAX_MAX_MESSAGE_SIZE,
            )
            assertTrue("budget $budget must stay under ceiling $ceiling", budget < ceiling)
        }
    }
}
