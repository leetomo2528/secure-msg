package com.yunjelee.securemsg

/** The durable writes one upload ack makes; Room-backed in the service, faked on the JVM. */
internal interface UploadAckWrites {
    suspend fun markRelaySent(row: RelayOutbox, seq: Int)
    suspend fun recordAck(cid: String, seq: Int)
    suspend fun acknowledgeIncoming(row: RelayOutbox)
}

/**
 * The tail of SmsBridgeService.flushOutbox's ack transaction.
 *
 * The caller runs [commit] inside the same Room transaction as the visible
 * row's serverKey update, so the ack is recorded completely or not at all.
 * The relay_acked record is the one piece of evidence that outlives both
 * acknowledgeIncoming (which deletes an incoming row right here) and logout
 * (which clears `messages`); without it a re-login pulls its own upload back
 * with nothing to say it was acknowledged (RelaySyncPolicy.canConsumeSelfEcho).
 */
internal object UploadAck {
    suspend fun commit(row: RelayOutbox, seq: Int, writes: UploadAckWrites) {
        writes.markRelaySent(row, seq)
        writes.recordAck(row.cid, seq)
        if (row.direction.startsWith("incoming_")) {
            // Tombstone + optional provider ledger + deletion are one
            // transaction, so a crash cannot forget a provider-less event
            // after its relay ACK.
            writes.acknowledgeIncoming(row)
        }
    }
}

internal class RoomUploadAckWrites(
    private val db: AppDatabase,
    private val incoming: IncomingMessageRepository,
) : UploadAckWrites {
    override suspend fun markRelaySent(row: RelayOutbox, seq: Int) =
        db.relayOutboxDao().markRelaySent(row.id, seq)

    override suspend fun recordAck(cid: String, seq: Int) {
        db.relayAckDao().record(RelayAck(cid = cid, seq = seq))
    }

    override suspend fun acknowledgeIncoming(row: RelayOutbox) {
        incoming.acknowledgeIncoming(row)
    }
}

/**
 * How long relay_acked keeps a record. The evidence only has to outlast the
 * window in which a pull can meet an own echo while an upload in the same
 * conversation is still pending; past that, RelaySyncPolicy's fallback
 * consumes the echo without it. Age- and count-bounded so the table cannot
 * grow with the account's lifetime traffic.
 */
internal object RelayAckRetention {
    const val MAX_AGE_MS: Long = 180L * 24 * 60 * 60 * 1000
    const val MAX_ROWS: Int = 20_000

    fun cutoff(now: Long): Long = now - MAX_AGE_MS
}
