package com.yunjelee.securemsg.ui

/**
 * How a tap on 보내기 ended, as the composer sees it.
 *
 * Three answers, not two, because the composer acts differently on each: a
 * [Refused] send wrote nothing and the draft is all the user has, while a
 * [Failed] one already sits in the thread with its failed badge.
 */
internal sealed interface SendResult {
    /** The Korean line shown above the composer; null for a send that went out. */
    val failure: String?

    data object Sent : SendResult {
        override val failure: String? get() = null
    }

    /** Nothing persisted, nothing dispatched. */
    data class Refused(override val failure: String) : SendResult

    /** A row exists (or may exist) and carries its own failed badge. */
    data class Failed(override val failure: String) : SendResult
}

/**
 * What the composer does once a send has answered, as pure functions so the
 * host suite pins it.
 */
internal object SendResultPolicy {
    /**
     * @param openThread the number-entry composer moves into the thread, where
     *   the result is a bubble — queued or failed. Only when a row was written:
     *   opening an existing thread on a refusal is how a caption and its photos
     *   used to vanish into the hidden composer.
     * @param clearDraft the text and staged photos are spent. Only on a send
     *   that went out; a failure keeps them for the retry, as it always has.
     */
    data class AfterSend(val openThread: Boolean, val clearDraft: Boolean)

    fun afterSend(result: SendResult): AfterSend = when (result) {
        SendResult.Sent -> AfterSend(openThread = true, clearDraft = true)
        is SendResult.Failed -> AfterSend(openThread = true, clearDraft = false)
        is SendResult.Refused -> AfterSend(openThread = false, clearDraft = false)
    }

    /**
     * A photo send's outcome in the composer's terms. Null is a host with no
     * MMS path wired, which dispatched nothing.
     */
    fun fromPhotos(outcome: MmsSendOutcome?): SendResult = when (outcome) {
        MmsSendOutcome.Sent -> SendResult.Sent
        is MmsSendOutcome.Refused -> SendResult.Refused(PhotoStaging.failureLine(outcome.message))
        is MmsSendOutcome.Failed -> SendResult.Failed(PhotoStaging.failureLine(outcome.message))
        null -> SendResult.Refused(PhotoStaging.PHOTOS_UNSUPPORTED)
    }

    /**
     * A text send's outcome. The SMS path answers only true/false and a false
     * may follow its own write, so it stays a [SendResult.Failed] — the
     * behaviour this composer has always had for text.
     */
    fun fromText(sent: Boolean, failure: String): SendResult =
        if (sent) SendResult.Sent else SendResult.Failed(failure)
}
