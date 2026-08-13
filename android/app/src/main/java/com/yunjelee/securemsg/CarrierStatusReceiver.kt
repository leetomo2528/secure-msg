package com.yunjelee.securemsg

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal data class CommittedCarrierStatus(
    val status: String,
    val error: String?,
    val statusApplied: Boolean,
)

internal object CarrierCallbackPersistence {
    /**
     * Claims a complete callback set and applies every Room-backed status as one unit.
     * A failed transaction leaves the complete part set intact for a later callback/retry.
     */
    suspend fun commitCompleted(
        db: AppDatabase,
        mid: String,
        action: String,
        partCount: Int,
        cid: String,
        seq: Int,
    ): CommittedCarrierStatus? = db.withTransaction {
        val results = db.carrierPartResultDao().getAll(mid, action)
        if (results.map { it.part }.distinct().size < partCount) return@withTransaction null

        // The delete is both the completion claim and part of the same atomic unit as
        // all durable status updates. Rollback therefore restores the complete set.
        if (db.carrierPartResultDao().deleteAll(mid, action) == 0) {
            return@withTransaction null
        }
        val aggregate = CarrierCallbackAggregate.resolve(action, partCount, results)
        var effectiveStatus = aggregate.status
        var statusApplied = false
        var statusTracked = false

        if (cid.isNotBlank() && seq > 0) {
            var receipt = db.relayReceiptDao().get(cid, seq)
            if (receipt == null) {
                db.relayReceiptDao().claim(RelayReceipt(cid, seq))
                receipt = db.relayReceiptDao().get(cid, seq)
            }
            statusTracked = receipt != null
            if (receipt == null || CarrierState.canAdvance(receipt.status, aggregate.status)) {
                db.relayReceiptDao().markStatus(cid, seq, aggregate.status, aggregate.error)
                statusApplied = true
            } else {
                effectiveStatus = receipt.status
            }
        }
        db.relayOutboxDao().getByMid(mid)?.let { row ->
            statusTracked = true
            if (CarrierState.canAdvance(row.carrierState, aggregate.status)) {
                db.relayOutboxDao().markCarrierState(row.id, aggregate.status, aggregate.error)
                row.localMessageId?.let { localId ->
                    val message = db.messageDao().getById(localId)
                    if (message == null || CarrierState.canAdvance(
                            message.carrierStatus,
                            aggregate.status,
                        )
                    ) {
                        db.messageDao().setCarrierStatusById(
                            localId,
                            aggregate.status,
                            aggregate.error,
                        )
                    }
                }
                effectiveStatus = aggregate.status
                statusApplied = true
            } else {
                effectiveStatus = row.carrierState
            }
        }
        if (!statusTracked) statusApplied = true
        CommittedCarrierStatus(
            status = effectiveStatus,
            error = if (effectiveStatus == aggregate.status) aggregate.error else null,
            statusApplied = statusApplied,
        )
    }
}

/** Receives asynchronous carrier SENT/DELIVERED callbacks and persists them. */
class CarrierStatusReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SENT = "com.yunjelee.securemsg.CARRIER_SENT"
        const val ACTION_DELIVERED = "com.yunjelee.securemsg.CARRIER_DELIVERED"
        const val EXTRA_MID = "mid"
        const val EXTRA_CID = "cid"
        const val EXTRA_SEQ = "seq"
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_PART = "part"
        const val EXTRA_PART_COUNT = "part_count"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT && intent.action != ACTION_DELIVERED) return
        val mid = intent.getStringExtra(EXTRA_MID).orEmpty()
        if (!mid.matches(Regex("[A-Za-z0-9_-]{8,80}"))) return
        val cid = intent.getStringExtra(EXTRA_CID).orEmpty()
        val seq = intent.getIntExtra(EXTRA_SEQ, 0)
        val providerId = intent.getLongExtra(EXTRA_PROVIDER_ID, -1L)
        val action = intent.action ?: return
        val part = intent.getIntExtra(EXTRA_PART, 0).coerceAtLeast(0)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1).coerceIn(1, 100)
        if (part !in 0 until partCount) return
        val callbackResult = resultCode
        val partOk = callbackResult == Activity.RESULT_OK
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.get(context)
                db.carrierPartResultDao().deleteOlderThan(
                    System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000,
                )
                db.carrierPartResultDao().upsert(
                    CarrierPartResult(
                        mid = mid,
                        action = action,
                        part = part,
                        partCount = partCount,
                        successful = partOk,
                        resultCode = callbackResult,
                    ),
                )
                val committed = CarrierCallbackPersistence.commitCompleted(
                    db = db,
                    mid = mid,
                    action = action,
                    partCount = partCount,
                    cid = cid,
                    seq = seq,
                ) ?: return@launch

                // These touch state outside Room and must only run after the transaction commits.
                MmsSender.deletePdu(context, intent.getStringExtra("pdu_id"))
                if (providerId > 0 && committed.statusApplied) {
                    val providerStatus = when (committed.status) {
                        "delivered" -> android.provider.Telephony.Sms.STATUS_COMPLETE
                        "sent", "dispatched" -> android.provider.Telephony.Sms.STATUS_PENDING
                        else -> android.provider.Telephony.Sms.STATUS_FAILED
                    }
                    SmsProvider.updateSentStatus(
                        context,
                        providerId,
                        providerStatus,
                        failed = CarrierState.isFailure(committed.status),
                    )
                }
                val service = Intent(context, SmsBridgeService::class.java)
                    .setAction(SmsBridgeService.ACTION_CARRIER_STATUS)
                    .putExtra(EXTRA_MID, mid)
                    .putExtra(EXTRA_CID, cid)
                    .putExtra(EXTRA_SEQ, seq)
                    .putExtra(EXTRA_STATUS, committed.status)
                    .putExtra(EXTRA_ERROR, committed.error)
                ContextCompat.startForegroundService(context, service)
            } catch (e: Exception) {
                Log.e("CarrierStatusReceiver", "failed to persist carrier status", e)
            } finally {
                pending.finish()
            }
        }
    }
}
