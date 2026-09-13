package com.yunjelee.securemsg.ui

import com.yunjelee.securemsg.RelayContentCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's staging and send decisions.
 *
 * Everything here is deliberately reachable without android.net.Uri: the rules
 * are generic over the staged item and take counts, so the host suite exercises
 * the same functions the phone runs rather than a paraphrase of them.
 */
class PhotoStagingTest {
    private fun merge(staged: List<String>, picked: List<String>) =
        PhotoStaging.merge(staged, picked) { it }

    @Test
    fun theCapIsTheCodecsAndIsNotRestatedHere() {
        // A cap raised on one side alone makes attachments vanish silently on
        // the other device, so this must never become a second literal.
        assertEquals(RelayContentCodec.MAX_ATTACHMENTS, PhotoStaging.MAX)
    }

    @Test
    fun picksAppendInOrderAfterWhatIsAlreadyStaged() {
        assertEquals(listOf("a", "b", "c"), merge(listOf("a"), listOf("b", "c")))
    }

    @Test
    fun anEmptyPickLeavesTheStagedListUntouched() {
        val staged = listOf("a", "b")
        // Same instance, not just an equal one: the row is keyed on the list and
        // a fresh copy would re-run every thumbnail decode for nothing.
        assertSame(staged, merge(staged, emptyList()))
    }

    @Test
    fun aPictureAlreadyStagedIsNotStagedTwice() {
        assertEquals(listOf("a", "b"), merge(listOf("a", "b"), listOf("a")))
        assertEquals(listOf("a", "b", "c"), merge(listOf("a"), listOf("b", "a", "c")))
    }

    @Test
    fun duplicatesInsideOnePickCollapse() {
        assertEquals(listOf("a"), merge(emptyList(), listOf("a", "a", "a")))
    }

    @Test
    fun theCapHoldsAndKeepsThePicturesAlreadyStaged() {
        val staged = (1..PhotoStaging.MAX).map { "s$it" }
        assertEquals(staged, merge(staged, listOf("new")))

        val nearlyFull = (1 until PhotoStaging.MAX).map { "s$it" }
        val merged = merge(nearlyFull, listOf("x", "y", "z"))
        assertEquals(PhotoStaging.MAX, merged.size)
        assertEquals(nearlyFull + "x", merged)
    }

    @Test
    fun aPickThatAllFittedSaysNothing() {
        assertNull(PhotoStaging.pickNotice(stagedBefore = 1, pickedCount = 2, stagedAfter = 3))
        assertNull(PhotoStaging.pickNotice(stagedBefore = 0, pickedCount = 0, stagedAfter = 0))
    }

    @Test
    fun aPickTrimmedByTheCapNamesTheLimitAndTheLoss() {
        val notice = PhotoStaging.pickNotice(
            stagedBefore = PhotoStaging.MAX - 1,
            pickedCount = 3,
            stagedAfter = PhotoStaging.MAX,
        )
        assertNotNull(notice)
        assertTrue(notice!!, notice.contains("${PhotoStaging.MAX}장"))
        assertTrue(notice, notice.contains("2장"))
    }

    @Test
    fun aPickThatOnlyLostDuplicatesSaysSoInsteadOfBlamingTheCap() {
        val notice = PhotoStaging.pickNotice(stagedBefore = 2, pickedCount = 2, stagedAfter = 3)
        assertNotNull(notice)
        assertTrue(notice!!, notice.contains("이미 첨부"))
        assertTrue(notice, notice.contains("1장"))
    }

    @Test
    fun theCountLabelNamesWhatIsStagedAndWhatIsLeft() {
        val label = PhotoStaging.countLabel(3)
        assertTrue(label, label.contains("3장"))
        assertTrue(label, label.contains("${PhotoStaging.MAX}장"))
        // Never negative, whatever a caller hands it.
        assertTrue(PhotoStaging.countLabel(-1), PhotoStaging.countLabel(-1).contains("0장"))
    }

    @Test
    fun anEmptyComposerSendsNothingAndSaysNothing() {
        assertNull(PhotoStaging.plan("", 0, photosSupported = true))
        assertNull(PhotoStaging.plan("   \n ", 0, photosSupported = true))
    }

    @Test
    fun textAloneStaysOnTheSmsPath() {
        assertEquals(SendPlan.Text, PhotoStaging.plan("안녕", 0, photosSupported = true))
        // No MMS handler wired changes nothing for a plain text message.
        assertEquals(SendPlan.Text, PhotoStaging.plan("안녕", 0, photosSupported = false))
    }

    @Test
    fun aStagedPictureTakesTheMmsPathWithOrWithoutACaption() {
        assertEquals(SendPlan.Photos, PhotoStaging.plan("", 1, photosSupported = true))
        assertEquals(SendPlan.Photos, PhotoStaging.plan("사진", 2, photosSupported = true))
        assertEquals(
            SendPlan.Photos,
            PhotoStaging.plan("사진", PhotoStaging.MAX, photosSupported = true),
        )
    }

    @Test
    fun aPictureWithNoMmsPathIsRefusedInKoreanRatherThanSentAsText() {
        val plan = PhotoStaging.plan("사진", 1, photosSupported = false)
        assertTrue(plan.toString(), plan is SendPlan.Refused)
        assertEquals(PhotoStaging.PHOTOS_UNSUPPORTED, (plan as SendPlan.Refused).message)
    }

    @Test
    fun morePicturesThanTheCapIsRefusedBeforeTheCarrierSeesIt() {
        val plan = PhotoStaging.plan("", PhotoStaging.MAX + 1, photosSupported = true)
        assertTrue(plan.toString(), plan is SendPlan.Refused)
        assertEquals(PhotoStaging.FULL_NOTICE, (plan as SendPlan.Refused).message)
    }

    @Test
    fun theDispatchersOwnReasonIsShownWhenItGaveOne() {
        assertEquals("첨부 용량이 커서 보낼 수 없습니다.", PhotoStaging.failureLine("첨부 용량이 커서 보낼 수 없습니다."))
    }

    @Test
    fun aFailureWithNothingToSayStillShowsALine() {
        assertEquals(PhotoStaging.PHOTO_SEND_FAILED, PhotoStaging.failureLine(null))
        assertEquals(PhotoStaging.PHOTO_SEND_FAILED, PhotoStaging.failureLine("   "))
    }

    @Test
    fun everyLineTheComposerCanShowIsKorean() {
        val lines = listOf(
            PhotoStaging.FULL_NOTICE,
            PhotoStaging.PICKER_UNAVAILABLE,
            PhotoStaging.PHOTOS_UNSUPPORTED,
            PhotoStaging.PHOTO_SEND_FAILED,
            PhotoStaging.countLabel(1),
            PhotoStaging.pickNotice(0, PhotoStaging.MAX + 1, PhotoStaging.MAX).orEmpty(),
            PhotoStaging.pickNotice(1, 1, 1).orEmpty(),
            composerQueuedNotice(0),
            composerQueuedNotice(2),
        )
        for (line in lines) {
            assertTrue(line, line.isNotBlank())
            assertTrue(line, line.any { it in '가'..'힣' })
        }
    }

    @Test
    fun theQueuedNoticeStopsCallingAPhotoMessageAnSms() {
        assertTrue(composerQueuedNotice(0), composerQueuedNotice(0).contains("SMS"))
        val photos = composerQueuedNotice(1)
        assertTrue(photos, photos.contains("사진"))
        assertTrue(photos, !photos.contains("SMS"))
    }
}
