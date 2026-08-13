package com.yunjelee.securemsg

/**
 * Fail-closed decisions for advancing the durable relay history cursor.
 * RETRY_BATCH means the current row and every later row must remain pending.
 */
object RelaySyncPolicy {
    enum class RowAction { SKIP_ALREADY_CONSUMED, PROCESS, RETRY_BATCH }

    fun rowAction(
        expectedCid: String,
        rowCid: String,
        seq: Int,
        cursor: Int,
        senderSid: String,
        payloadIsObject: Boolean,
    ): RowAction = when {
        rowCid != expectedCid -> RowAction.RETRY_BATCH
        seq <= 0 -> RowAction.RETRY_BATCH
        seq <= cursor -> RowAction.SKIP_ALREADY_CONSUMED
        senderSid.isBlank() -> RowAction.RETRY_BATCH
        !payloadIsObject -> RowAction.RETRY_BATCH
        else -> RowAction.PROCESS
    }

    /** A self echo is consumable only when durable local ACK evidence exists. */
    fun canConsumeSelfEcho(hasLocalServerKey: Boolean, hasAcknowledgedOutbox: Boolean): Boolean =
        hasLocalServerKey || hasAcknowledgedOutbox
}
