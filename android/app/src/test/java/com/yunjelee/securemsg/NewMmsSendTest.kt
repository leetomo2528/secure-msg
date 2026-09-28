package com.yunjelee.securemsg

import com.yunjelee.securemsg.ui.MmsSendOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * MainActivity's photo send: the dispatcher's answer decides whether the
 * composer treats the send as written, and nothing after that answer (the
 * bridge start) may rewrite it — a persisted photo read as Refused keeps the
 * draft up and invites a duplicate send.
 */
class NewMmsSendTest {
    private val logged = mutableListOf<String>()
    private val log: (String, Throwable) -> Unit = { message, _ -> logged += message }

    private fun run(
        answer: OutgoingSmsDispatcher.MmsSend,
        startBridge: () -> Unit,
    ): MmsSendOutcome = runBlocking {
        NewMmsSend.run(hasPhotos = true, send = { answer }, startBridge = startBridge, log = log)
    }

    @Test
    fun aBridgeStartThatThrowsAfterASentPhotoLeavesItSent() {
        val outcome = run(OutgoingSmsDispatcher.MmsSend.Sent) { throw IllegalStateException("fgs") }
        assertEquals(MmsSendOutcome.Sent, outcome)
        assertEquals(listOf("Bridge service start after MMS send failed"), logged)
    }

    @Test
    fun aBridgeStartThatThrowsAfterAPersistedFailureLeavesItPersisted() {
        val outcome = run(OutgoingSmsDispatcher.MmsSend.Failed("통신사 거절")) {
            throw SecurityException("role revoked")
        }
        assertEquals(MmsSendOutcome.Failed("통신사 거절"), outcome)
    }

    @Test
    fun aBridgeStartThatThrowsAfterARefusalLeavesItRefused() {
        val outcome = run(OutgoingSmsDispatcher.MmsSend.Refused("너무 큽니다")) {
            throw IllegalStateException("fgs")
        }
        assertEquals(MmsSendOutcome.Refused("너무 큽니다"), outcome)
    }

    @Test
    fun theBridgeIsStartedOnceForEveryAnswer() {
        val answers = listOf(
            OutgoingSmsDispatcher.MmsSend.Sent,
            OutgoingSmsDispatcher.MmsSend.Refused("r"),
            OutgoingSmsDispatcher.MmsSend.Failed("f"),
        )
        for (answer in answers) {
            var starts = 0
            run(answer) { starts++ }
            assertEquals(1, starts)
        }
        assertTrue(logged.isEmpty())
    }

    @Test
    fun aDispatcherThrowWithPhotosIsARefusalAndStartsNothing() = runBlocking {
        var starts = 0
        val outcome = NewMmsSend.run(
            hasPhotos = true,
            send = { throw IllegalArgumentException("unreadable") },
            startBridge = { starts++ },
            log = log,
        )
        assertEquals(MmsSendOutcome.Refused(NewMmsSend.SEND_FAILED), outcome)
        assertEquals(0, starts)
    }

    @Test
    fun aDispatcherLinkageErrorWithPhotosIsACryptoRefusal() = runBlocking {
        val outcome = NewMmsSend.run(
            hasPhotos = true,
            send = { throw UnsatisfiedLinkError("sodium") },
            startBridge = {},
            log = log,
        )
        assertEquals(MmsSendOutcome.Refused(NewMmsSend.CRYPTO_UNAVAILABLE), outcome)
    }

    @Test
    fun aPhotolessDispatcherThrowMayHaveWritten() = runBlocking {
        val outcome = NewMmsSend.run(
            hasPhotos = false,
            send = { throw IllegalStateException("after insert") },
            startBridge = {},
            log = log,
        )
        assertEquals(MmsSendOutcome.Failed(NewMmsSend.SEND_FAILED), outcome)
    }

    @Test
    fun cancellationIsNotAnAnswer() {
        try {
            runBlocking {
                NewMmsSend.run(
                    hasPhotos = true,
                    send = { throw CancellationException("pane gone") },
                    startBridge = {},
                    log = log,
                )
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }
}
