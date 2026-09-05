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
        // GET /conversation/<cid>/messages carries the conversation id on the
        // response, never on a row. Requiring it per row turned the very first
        // row of every page into RETRY_BATCH, so the whole relay->carrier path
        // was a silent no-op; the caller checks the response-level cid instead.
        rowCid.isNotEmpty() && rowCid != expectedCid -> RowAction.RETRY_BATCH
        seq <= 0 -> RowAction.RETRY_BATCH
        seq <= cursor -> RowAction.SKIP_ALREADY_CONSUMED
        senderSid.isBlank() -> RowAction.RETRY_BATCH
        !payloadIsObject -> RowAction.RETRY_BATCH
        else -> RowAction.PROCESS
    }

    /** A self echo is consumable only when durable local ACK evidence exists. */
    fun canConsumeSelfEcho(hasLocalServerKey: Boolean, hasAcknowledgedOutbox: Boolean): Boolean =
        hasLocalServerKey || hasAcknowledgedOutbox

    /**
     * Whether a relay row from another device is still waiting to be put on the
     * carrier, as opposed to history this gateway is only catching up on.
     *
     * A row whose sender is itself a gateway was uploaded by one — an incoming
     * SMS, or a message composed on that phone — and was never a request to
     * send anything. A row that already carries a carrier status was dispatched
     * by a gateway too: only an approved android_gateway may set that field on
     * the relay. Both matter because `relay_receipts` remembers what THIS
     * install dispatched and nothing more, so a replacement gateway starts with
     * an empty table and a cursor of 0 — without this it would push the
     * account's entire history back onto the carrier, at the user's cost, as
     * soon as a history backfill made those envelopes readable.
     *
     * [senderKind] is the pinned trust-store kind of the sending device; a
     * sender whose kind is unknown is not treated as a gateway, because the
     * caller has already refused to process a row from an unpinned sender.
     */
    fun isCarrierSendRequest(senderKind: String?, carrierStatus: String): Boolean =
        senderKind != "android_gateway" &&
            (carrierStatus.isBlank() || carrierStatus == "none")
}
