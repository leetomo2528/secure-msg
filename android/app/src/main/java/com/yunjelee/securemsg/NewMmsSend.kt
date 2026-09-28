package com.yunjelee.securemsg

import com.yunjelee.securemsg.ui.MmsSendOutcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * MainActivity's photo send without the Activity: run the dispatcher, then
 * start the bridge, and tell the composer whether a row was written.
 *
 * The bridge start is fenced off from the classification on purpose. It runs
 * after the dispatcher has answered, so whatever it throws says nothing about
 * what is on disk; letting it reach the send's catch would turn a persisted
 * (even delivered) photo into a Refused, and the composer would keep the
 * draft and invite a second, duplicate send. Its failure is logged and the
 * dispatcher's answer stands. An Error out of it is not caught and reaches the
 * composer, which reads any throw as "may have written".
 */
internal object NewMmsSend {
    const val CRYPTO_UNAVAILABLE = "보안 모듈을 불러오지 못해 사진을 보내지 못했습니다"
    const val SEND_FAILED = "사진 메시지를 보내지 못했습니다"

    suspend fun run(
        hasPhotos: Boolean,
        send: suspend () -> OutgoingSmsDispatcher.MmsSend,
        startBridge: () -> Unit,
        log: (String, Throwable) -> Unit,
    ): MmsSendOutcome {
        val result = try {
            send()
        } catch (e: CancellationException) {
            // Not a failed send: whoever cancelled is gone and wants no answer.
            throw e
        } catch (e: LinkageError) {
            log("MMS crypto module unavailable", e)
            return unsent(hasPhotos, CRYPTO_UNAVAILABLE)
        } catch (e: Exception) {
            log("MMS send/sync failed", e)
            return unsent(hasPhotos, SEND_FAILED)
        }
        // Unconditional once the dispatcher answered, exactly as the SMS path
        // starts it: relay preparation is durable and may complete immediately
        // or after a later reconnect, and a refusal here does not mean the
        // outbox is empty of earlier rows waiting for the same service.
        try {
            startBridge()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Bridge service start after MMS send failed", e)
        }
        return when (result) {
            OutgoingSmsDispatcher.MmsSend.Sent -> MmsSendOutcome.Sent
            is OutgoingSmsDispatcher.MmsSend.Refused -> MmsSendOutcome.Refused(result.reason)
            is OutgoingSmsDispatcher.MmsSend.Failed -> MmsSendOutcome.Failed(result.reason)
        }
    }

    /**
     * What a throw out of the dispatcher means for what is on disk.
     *
     * A photo send resolves every post-write failure into a returned Failed
     * (OutgoingMmsCommit), so anything thrown there happened before or inside
     * the rolled-back transaction: nothing was written, and the composer keeps
     * its draft. The photoless path delegates to queueAndSend, which can throw
     * after its own write, so that one is reported as possibly persisted.
     */
    fun unsent(hasPhotos: Boolean, message: String): MmsSendOutcome =
        if (hasPhotos) MmsSendOutcome.Refused(message) else MmsSendOutcome.Failed(message)
}
