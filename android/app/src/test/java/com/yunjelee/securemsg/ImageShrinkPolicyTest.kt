package com.yunjelee.securemsg

import com.yunjelee.securemsg.ImageShrinkPolicy.Candidate
import com.yunjelee.securemsg.ImageShrinkPolicy.Rung
import com.yunjelee.securemsg.ImageShrinkPolicy.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageShrinkPolicyTest {
    /**
     * The ladder is written out here instead of being derived from
     * [ImageShrinkPolicy.SHRINK_RUNGS] on purpose: this literal is the copy the
     * web table is diffed against, so editing one platform's ladder alone fails
     * the build rather than shipping two of them.
     */
    @Test
    fun rungTableMatchesTheWebLadder() {
        assertEquals(
            listOf(
                Rung(1600, 82),
                Rung(1600, 68),
                Rung(1280, 72),
                Rung(1280, 58),
                Rung(1024, 66),
                Rung(1024, 50),
                Rung(800, 58),
                Rung(640, 50),
            ),
            ImageShrinkPolicy.SHRINK_RUNGS,
        )
    }

    @Test
    fun cameraSourceStartsAtTheTopRung() {
        assertEquals(ImageShrinkPolicy.SHRINK_RUNGS, ImageShrinkPolicy.rungsFor(4000))
        assertEquals(Rung(1600, 82), ImageShrinkPolicy.rungsFor(4000).first())
        assertEquals(Size(1600, 1200), ImageShrinkPolicy.targetSize(4000, 3000, 1600))
    }

    @Test
    fun neverUpscalesASmallSource() {
        val rungs = ImageShrinkPolicy.rungsFor(900)
        assertTrue(rungs.none { it.edge > 900 })
        assertEquals(Rung(900, 82), rungs.first())
        assertEquals(Rung(640, 50), rungs.last())
        assertEquals(Size(900, 600), ImageShrinkPolicy.targetSize(900, 600, 1600))
    }

    @Test
    fun collapsesRungsThatClampOntoAnEarlierOne() {
        val rungs = ImageShrinkPolicy.rungsFor(700)
        assertEquals(rungs.distinct(), rungs)
        // (800, 58) clamps onto the already-present (700, 58) and disappears.
        assertEquals(7, rungs.size)
        assertEquals(Rung(640, 50), rungs.last())

        val tiny = ImageShrinkPolicy.rungsFor(600)
        assertEquals(tiny.distinct(), tiny)
        assertEquals(Rung(600, 50), tiny.last())
        assertTrue(tiny.isNotEmpty())
    }

    @Test
    fun targetSizeKeepsAspectAndFloorsTheShortSide() {
        assertEquals(Size(640, 426), ImageShrinkPolicy.targetSize(1000, 667, 640))
        assertEquals(Size(426, 640), ImageShrinkPolicy.targetSize(667, 1000, 640))
        // A panorama's short side floors to 0 and must still be allocatable.
        assertEquals(Size(640, 1), ImageShrinkPolicy.targetSize(1000, 1, 640))
    }

    @Test
    fun sampleSizeNeverUndershootsTheTarget() {
        assertEquals(2, ImageShrinkPolicy.sampleSizeFor(4000, 3000, 1600))
        assertEquals(4, ImageShrinkPolicy.sampleSizeFor(4000, 3000, 640))
        assertEquals(1, ImageShrinkPolicy.sampleSizeFor(800, 600, 1600))
        assertEquals(1, ImageShrinkPolicy.sampleSizeFor(0, 0, 1600))
        assertEquals(1, ImageShrinkPolicy.sampleSizeFor(1, 1, 640))
        val sample = ImageShrinkPolicy.sampleSizeFor(4000, 3000, 1600)
        val target = ImageShrinkPolicy.targetSize(4000, 3000, 1600)
        assertTrue(4000 / sample >= target.width && 3000 / sample >= target.height)
    }

    @Test
    fun smilCostsNoBudgetAndIsNeverAnOmission() {
        assertTrue(ImageShrinkPolicy.isIgnorable("application/smil"))
        assertTrue(ImageShrinkPolicy.isIgnorable("application/smil+xml; charset=utf-8"))
        assertFalse(ImageShrinkPolicy.isShrinkable("application/smil"))
        assertFalse(ImageShrinkPolicy.isPassThrough("application/smil"))
        assertNull(IncomingOmissionNotice.omissionFor("application/smil"))

        val budgets = ImageShrinkPolicy.allocate(
            listOf(Candidate("application/smil", 2_048), Candidate("image/jpeg", 3_000_000)),
            ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET,
        )
        assertEquals(0, budgets[0])
        assertEquals(ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET, budgets[1])
    }

    @Test
    fun gifPassesThroughWhileVideoAndAudioAreOmitted() {
        assertTrue(ImageShrinkPolicy.isPassThrough("image/gif"))
        assertFalse(ImageShrinkPolicy.isShrinkable("image/gif"))
        listOf("video/mp4", "audio/amr", "application/pdf").forEach {
            assertFalse(it, ImageShrinkPolicy.isShrinkable(it))
            assertFalse(it, ImageShrinkPolicy.isPassThrough(it))
            assertFalse(it, ImageShrinkPolicy.isIgnorable(it))
        }
        assertEquals(IncomingOmissionNotice.Kind.VIDEO, ImageShrinkPolicy.omissionKind("video/mp4"))
        assertEquals(IncomingOmissionNotice.Kind.AUDIO, ImageShrinkPolicy.omissionKind("audio/amr"))
        assertEquals(IncomingOmissionNotice.Kind.IMAGE, ImageShrinkPolicy.omissionKind("image/jpeg"))
        assertEquals(IncomingOmissionNotice.Kind.FILE, ImageShrinkPolicy.omissionKind("application/pdf"))

        val budgets = ImageShrinkPolicy.allocate(
            listOf(Candidate("image/gif", 100_000), Candidate("video/mp4", 4_000_000)),
            ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET,
        )
        assertEquals(100_000, budgets[0])
        assertEquals(0, budgets[1])
    }

    @Test
    fun aGifTooBigToReserveTakesNothingFromThePhotoBesideIt() {
        val budgets = ImageShrinkPolicy.allocate(
            listOf(Candidate("image/gif", 600_000), Candidate("image/jpeg", 3_000_000)),
            ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET,
        )
        assertEquals(0, budgets[0])
        assertEquals(ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET, budgets[1])
    }

    @Test
    fun incomingBudgetSplitsAcrossTwoImagesAndNeverExceedsTheWireCap() {
        // The budget IS the wire cap. A reserve below it would only refuse
        // files that fit, and materialize's accept() is what actually bounds
        // the relayed total.
        assertEquals(
            RelayContentCodec.MAX_ATTACHMENT_BYTES,
            ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET,
        )
        val budgets = ImageShrinkPolicy.allocate(
            listOf(Candidate("image/jpeg", 3_000_000), Candidate("image/heic", 4_000_000)),
            ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET,
        )
        assertEquals(listOf(262_144, 262_144), budgets)
        assertTrue(budgets.sum() <= RelayContentCodec.MAX_ATTACHMENT_BYTES)
    }

    @Test
    fun recreditsWhatASmallImageDoesNotNeed() {
        val budgets = ImageShrinkPolicy.allocate(
            listOf(Candidate("image/png", 20_000), Candidate("image/jpeg", 3_000_000)),
            ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET,
        )
        assertEquals(listOf(20_000, 504_288), budgets)
        assertEquals(ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET, budgets.sum())
    }

    @Test
    fun budgetsNothingTheCodecWouldRefuseToEncode() {
        val nine = List(9) { Candidate("image/jpeg", 3_000_000) }
        val budgets = ImageShrinkPolicy.allocate(nine, ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET)
        assertEquals(RelayContentCodec.MAX_ATTACHMENTS, budgets.count { it > 0 })
        assertEquals(0, budgets.last())
        assertTrue(budgets.sum() <= ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET)

        // A layout script ahead of them takes no slot, so all eight still fit.
        val withSmil = listOf(Candidate("application/smil", 2_048)) +
            List(RelayContentCodec.MAX_ATTACHMENTS) { Candidate("image/jpeg", 3_000_000) }
        val withSmilBudgets =
            ImageShrinkPolicy.allocate(withSmil, ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET)
        assertEquals(0, withSmilBudgets.first())
        assertTrue(withSmilBudgets.drop(1).all { it > 0 })
    }

    @Test
    fun predictRungSkipsRungsThatCannotFit() {
        // 1600x1200 at quality 82 measured 900 KB: 1280/58 is the first rung
        // the model puts under 90% of the budget.
        assertEquals(
            3,
            ImageShrinkPolicy.predictRung(
                probePixels = 1_920_000,
                probeQuality = 82,
                probeBytes = 900_000,
                budget = 384 * 1024,
                rungs = ImageShrinkPolicy.SHRINK_RUNGS,
            ),
        )
    }

    @Test
    fun predictRungNeverJumpsBackwards() {
        // Probed at the (800, 58) rung with budget to spare: every earlier rung
        // fits the model, and returning one would walk the loop back up.
        assertEquals(
            6,
            ImageShrinkPolicy.predictRung(
                probePixels = 480_000,
                probeQuality = 58,
                probeBytes = 120_000,
                budget = 10_000_000,
                rungs = ImageShrinkPolicy.SHRINK_RUNGS,
            ),
        )
        val rungs = ImageShrinkPolicy.SHRINK_RUNGS
        rungs.forEachIndexed { i, rung ->
            val pixels = rung.edge * rung.edge * 3 / 4
            val landed = ImageShrinkPolicy.predictRung(
                probePixels = pixels,
                probeQuality = rung.quality,
                probeBytes = 700_000,
                budget = ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET,
                rungs = rungs,
            )
            assertTrue("rung $i landed at $landed", landed in i..rungs.lastIndex)
        }
    }

    @Test
    fun predictRungClampsToTheFloor() {
        assertEquals(
            ImageShrinkPolicy.SHRINK_RUNGS.lastIndex,
            ImageShrinkPolicy.predictRung(
                probePixels = 1_920_000,
                probeQuality = 82,
                probeBytes = 50_000_000,
                budget = 384 * 1024,
                rungs = ImageShrinkPolicy.SHRINK_RUNGS,
            ),
        )
        // An unmeasurable probe drops to the floor rather than guessing high.
        assertEquals(
            ImageShrinkPolicy.SHRINK_RUNGS.lastIndex,
            ImageShrinkPolicy.predictRung(0, 82, 0, 0, ImageShrinkPolicy.SHRINK_RUNGS),
        )
    }

    @Test
    fun predictRungStaysInsideAClampedLadder() {
        val rungs = ImageShrinkPolicy.rungsFor(900)
        val landed = ImageShrinkPolicy.predictRung(
            probePixels = 607_500,
            probeQuality = 82,
            probeBytes = 400_000,
            budget = 384 * 1024,
            rungs = rungs,
        )
        assertEquals(1, landed)
        assertTrue(landed <= rungs.lastIndex)
    }

    @Test
    fun partReadSeparatesDeferralFromLoss() {
        val read: ImageShrinkPolicy.PartRead = ImageShrinkPolicy.PartRead.Ok(byteArrayOf(1, 2, 3))
        assertEquals(3, (read as ImageShrinkPolicy.PartRead.Ok).bytes.size)
        // A part still downloading must not be mistaken for one that is merely
        // oversized: only TooLarge may be announced to the user as omitted.
        assertFalse(ImageShrinkPolicy.PartRead.Failed == ImageShrinkPolicy.PartRead.TooLarge)
    }
}
