package com.yunjelee.securemsg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the composer does after a send answers.
 *
 * The number-entry composer used to move into the thread whatever the answer,
 * so a refused photo send to a number that already had a thread opened that
 * thread and left the caption and photos in the hidden composer, cleared on the
 * next compose. A refusal wrote nothing; the draft is all the user has.
 */
class SendResultPolicyTest {
    @Test
    fun aSentMessageOpensTheThreadAndSpendsTheDraft() {
        val after = SendResultPolicy.afterSend(SendResult.Sent)
        assertTrue(after.openThread)
        assertTrue(after.clearDraft)
    }

    @Test
    fun aPersistedFailureOpensTheThreadWhereItsBadgeIsButKeepsTheDraft() {
        val after = SendResultPolicy.afterSend(SendResult.Failed("통신사가 메시지를 받지 않았습니다"))
        assertTrue(after.openThread)
        assertFalse(after.clearDraft)
    }

    @Test
    fun aRefusalStaysInTheComposerAndKeepsEverything() {
        val after = SendResultPolicy.afterSend(SendResult.Refused("움직이는 GIF가 너무 큽니다"))
        assertFalse("a refusal wrote no row, so there is no thread to show it in", after.openThread)
        assertFalse(after.clearDraft)
    }

    @Test
    fun aRefusedPhotoSendStaysARefusalWithTheDispatchersReason() {
        val result = SendResultPolicy.fromPhotos(MmsSendOutcome.Refused("사진은 최대 8장"))
        assertEquals(SendResult.Refused("사진은 최대 8장"), result)
        assertFalse(SendResultPolicy.afterSend(result).openThread)
    }

    @Test
    fun aPersistedPhotoFailureStaysAFailure() {
        val result = SendResultPolicy.fromPhotos(MmsSendOutcome.Failed("통신사가 메시지를 받지 않았습니다"))
        assertEquals(SendResult.Failed("통신사가 메시지를 받지 않았습니다"), result)
        assertTrue(SendResultPolicy.afterSend(result).openThread)
    }

    @Test
    fun aBlankReasonIsNeverShownAsSilence() {
        assertEquals(
            SendResult.Refused(PhotoStaging.PHOTO_SEND_FAILED),
            SendResultPolicy.fromPhotos(MmsSendOutcome.Refused(" ")),
        )
        assertEquals(
            SendResult.Failed(PhotoStaging.PHOTO_SEND_FAILED),
            SendResultPolicy.fromPhotos(MmsSendOutcome.Failed("")),
        )
    }

    @Test
    fun aSentPhotoIsSentAndAMissingMmsPathIsARefusal() {
        assertSame(SendResult.Sent, SendResultPolicy.fromPhotos(MmsSendOutcome.Sent))
        assertEquals(
            SendResult.Refused(PhotoStaging.PHOTOS_UNSUPPORTED),
            SendResultPolicy.fromPhotos(null),
        )
    }

    @Test
    fun aTextSendKeepsItsBooleanContract() {
        assertSame(SendResult.Sent, SendResultPolicy.fromText(true, "x"))
        // A false SMS may follow its own write; it keeps the old behaviour.
        assertEquals(SendResult.Failed("x"), SendResultPolicy.fromText(false, "x"))
    }

    @Test
    fun onlyASentResultCarriesNoFailureLine() {
        assertEquals(null, SendResult.Sent.failure)
        assertEquals("a", SendResult.Refused("a").failure)
        assertEquals("b", SendResult.Failed("b").failure)
    }
}
