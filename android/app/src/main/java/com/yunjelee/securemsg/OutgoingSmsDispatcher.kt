package com.yunjelee.securemsg

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import java.util.UUID

/**
 * Persists an outgoing carrier SMS before invoking SmsManager.
 *
 * Relay preparation is deliberately deferred to SmsBridgeService. This keeps
 * normal SMS and the system quick-reply contract working while Oracle or the
 * network is offline, without losing the later encrypted multi-device sync.
 */
object OutgoingSmsDispatcher {
    private const val TAG = "OutgoingSmsDispatcher"

    suspend fun queueAndSend(
        context: Context,
        credentials: SavedCredentials,
        phoneNumber: String,
        text: String,
    ): Boolean {
        val phone = PhoneNumberNormalizer.normalize(phoneNumber)
        require(PhoneNumberNormalizer.isSmsAddress(phone)) { "invalid phone number" }
        require(text.isNotBlank()) { "SMS body is empty" }
        require(text.length <= 20_000) { "SMS body is too long" }

        val db = AppDatabase.get(context)
        val content = RelayContentCodec.text(text)
        val contentJson = RelayContentCodec.encode(content)
        val mid = UUID.randomUUID().toString()
        // The presentation row and the durable outbox row must appear or fail
        // together; a crash between them would orphan a relay-only phantom.
        // The thread get-or-create belongs inside for the same reason it does in
        // IncomingMessageRepository: sms_threads is keyed on cid alone, so two
        // senders racing the first-ever message to a number would otherwise each
        // insert their own local_ row and split the conversation in two.
        val (cid, localId, outboxId) = db.withTransaction {
            val thread = db.threadDao().getByPhone(phone) ?: SmsThread(
                cid = SmsThread.newLocalCid(),
                phoneNumber = phone,
                serverName = null,
            ).also { db.threadDao().upsert(it) }
            db.threadDao().touch(thread.cid, System.currentTimeMillis())
            val localId = db.messageDao().insert(
                MessageRow(
                    cid = thread.cid,
                    seq = 0,
                    senderSid = credentials.sid,
                    plaintext = text,
                    createdAt = System.currentTimeMillis(),
                    mine = true,
                    contentType = content.type,
                    carrierStatus = "queued",
                ),
            )
            val outboxId = db.relayOutboxDao().insert(
                RelayOutbox(
                    mid = mid,
                    cid = thread.cid,
                    payload = "",
                    plaintext = contentJson,
                    contentType = content.type,
                    phoneNumber = phone,
                    localMessageId = localId,
                    direction = "outgoing_sms",
                    carrierState = "unknown",
                ),
            )
            Triple(thread.cid, localId, outboxId)
        }

        val dispatched = SmsSender.send(context, phone, text, mid, cid, 0)
        if (!dispatched) {
            db.relayOutboxDao().markCarrierState(
                outboxId,
                "failed",
                "carrier dispatch rejected",
            )
            db.messageDao().setCarrierStatusById(
                localId,
                "failed",
                "carrier dispatch rejected",
            )
            return false
        }

        // A very fast carrier callback may already have advanced this row. Only
        // replace the unknown pre-call marker and mirror the resulting state.
        db.relayOutboxDao().markCarrierDispatchedIfUnknown(outboxId)
        val current = db.relayOutboxDao().getByMid(mid)
        val state = current?.carrierState ?: "dispatched"
        db.messageDao().advanceCarrierStatus(localId, state, current?.lastError)
        Log.i(TAG, "Queued encrypted relay for carrier SMS mid=$mid")
        return true
    }
}
