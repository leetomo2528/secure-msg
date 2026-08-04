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
        require(Regex("^\\+?[0-9*#]{3,24}$").matches(phone)) { "invalid phone number" }
        require(text.isNotBlank()) { "SMS body is empty" }
        require(text.length <= 20_000) { "SMS body is too long" }

        val db = AppDatabase.get(context)
        val thread = db.threadDao().getByPhone(phone) ?: SmsThread(
            cid = "local_${UUID.randomUUID().toString().replace("-", "")}",
            phoneNumber = phone,
            contactName = null,
        ).also { db.threadDao().upsert(it) }
        val content = RelayContentCodec.text(text)
        val contentJson = RelayContentCodec.encode(content)
        val mid = UUID.randomUUID().toString()
        // The presentation row and the durable outbox row must appear or fail
        // together; a crash between them would orphan a relay-only phantom.
        val (localId, outboxId) = db.withTransaction {
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
            localId to outboxId
        }

        val dispatched = SmsSender.send(context, phone, text, mid, thread.cid, 0)
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
        val message = db.messageDao().getById(localId)
        if (message == null || CarrierState.canAdvance(message.carrierStatus, state)) {
            db.messageDao().setCarrierStatusById(localId, state, current?.lastError)
        }
        Log.i(TAG, "Queued encrypted relay for carrier SMS mid=$mid")
        return true
    }
}
