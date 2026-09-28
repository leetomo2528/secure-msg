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

    /** What the pull knows about one row this device's own sid uploaded. */
    data class SelfEchoEvidence(
        /** `messages.serverKey` "<cid>:<seq>" exists (cleared by logout). */
        val hasLocalServerKey: Boolean,
        /** An outgoing relay_outbox row is 'sent' at this seq (incoming rows are deleted). */
        val hasAcknowledgedOutbox: Boolean,
        /** relay_acked holds (cid, seq): written with the ack, kept through logout. */
        val hasDurableAck: Boolean,
        /** relay_outbox still holds an un-acked upload in this cid (RelayOutboxDao.hasUnackedUpload). */
        val hasUnackedUpload: Boolean,
    )

    /**
     * Whether the pull may consume this device's own echo (advance the cursor
     * and report it delivered) instead of stopping the conversation there.
     *
     * With ack evidence it always may. Without any, it may as long as no
     * upload in this conversation is still waiting for its ack; that is the
     * legacy fallback for a gateway that lost its evidence -- logout clears
     * `messages`, and before relay_acked existed an incoming row's only trace
     * of its ack was its visible row -- and whose pull would otherwise stop at
     * its own upload for good, holding back every web message queued behind
     * it for the carrier.
     *
     * Why consuming without evidence never loses an upload:
     * - Consuming an own echo has no side effect on the upload. It never
     *   reaches the carrier (an own-sid row is never a send request), and it
     *   neither reads nor changes relay_outbox. An upload is completed by the
     *   outbox flush alone: the row stays pending until the relay acks it,
     *   and resending the same prepared envelope under the same mid is
     *   answered with the seq the relay already assigned. The pull cursor
     *   only decides which relay rows this device still has to *process*,
     *   and an own upload needs no processing.
     * - In flight: the relay stores the row and fans message_new out before
     *   it answers the upload, so the pull can meet the echo before
     *   markRelaySent commits. The row is still pending under the cid it was
     *   sent to (markPrepared writes that cid with the payload), so
     *   [SelfEchoEvidence.hasUnackedUpload] holds and the pull waits exactly
     *   as before; the flush resumes it once the ack commits (OutboxAckResume).
     * - Prepared but un-acked (the ack was lost, the process died, the socket
     *   dropped): the same pending row holds, so the pull waits until a later
     *   flush gets the ack.
     * - A row still under a local_ cid was never sent, and an 'unsendable' one
     *   was retired before it could be prepared, so neither can have an echo.
     * - Outgoing rows are never deleted after their ack ('sent' with
     *   serverSeq is itself evidence) and count as pending until then.
     * So an own echo with no evidence and no pending row in its cid is the
     * echo of an upload already acknowledged: history.
     */
    fun canConsumeSelfEcho(evidence: SelfEchoEvidence): Boolean =
        evidence.hasLocalServerKey ||
            evidence.hasAcknowledgedOutbox ||
            evidence.hasDurableAck ||
            !evidence.hasUnackedUpload

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
