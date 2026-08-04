package com.yunjelee.securemsg

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                val results = db.carrierPartResultDao().getAll(mid, action)
                if (results.map { it.part }.distinct().size < partCount) return@launch
                // Only one callback may consume a completed part set. The
                // atomic delete acts as the claim: concurrent part callbacks
                // cannot aggregate and dispatch the same result twice.
                if (db.carrierPartResultDao().deleteAll(mid, action) == 0) return@launch
                val failedPart = results.firstOrNull { !it.successful }
                val ok = failedPart == null
                val status = when {
                    action == ACTION_DELIVERED && ok -> "delivered"
                    action == ACTION_DELIVERED -> "delivery_failed"
                    ok -> "sent"
                    else -> "failed"
                }
                val error = failedPart?.let {
                    "carrier result=${it.resultCode} part=${it.part + 1}/$partCount"
                }
                MmsSender.deletePdu(context, intent.getStringExtra("pdu_id"))
                var effectiveStatus = status
                var statusApplied = false
                var statusTracked = false
                if (cid.isNotBlank() && seq > 0) {
                    var receipt = db.relayReceiptDao().get(cid, seq)
                    if (receipt == null) {
                        db.relayReceiptDao().claim(RelayReceipt(cid, seq))
                        receipt = db.relayReceiptDao().get(cid, seq)
                    }
                    statusTracked = receipt != null
                    if (receipt == null || CarrierState.canAdvance(receipt.status, status)) {
                        db.relayReceiptDao().markStatus(cid, seq, status, error)
                        statusApplied = true
                    } else {
                        effectiveStatus = receipt.status
                    }
                }
                db.relayOutboxDao().getByMid(mid)?.let { row ->
                    statusTracked = true
                    if (CarrierState.canAdvance(row.carrierState, status)) {
                        db.relayOutboxDao().markCarrierState(row.id, status, error)
                        row.localMessageId?.let { localId ->
                            val message = db.messageDao().getById(localId)
                            if (message == null || CarrierState.canAdvance(message.carrierStatus, status)) {
                                db.messageDao().setCarrierStatusById(localId, status, error)
                            }
                        }
                        effectiveStatus = status
                        statusApplied = true
                    } else {
                        effectiveStatus = row.carrierState
                    }
                }
                if (!statusTracked) statusApplied = true
                if (providerId > 0 && statusApplied) {
                    val providerStatus = when (effectiveStatus) {
                        "delivered" -> android.provider.Telephony.Sms.STATUS_COMPLETE
                        "sent", "dispatched" -> android.provider.Telephony.Sms.STATUS_PENDING
                        else -> android.provider.Telephony.Sms.STATUS_FAILED
                    }
                    SmsProvider.updateSentStatus(
                        context,
                        providerId,
                        providerStatus,
                        failed = effectiveStatus == "failed",
                    )
                }
                val service = Intent(context, SmsBridgeService::class.java)
                    .setAction(SmsBridgeService.ACTION_CARRIER_STATUS)
                    .putExtra(EXTRA_MID, mid)
                    .putExtra(EXTRA_CID, cid)
                    .putExtra(EXTRA_SEQ, seq)
                    .putExtra(EXTRA_STATUS, effectiveStatus)
                    .putExtra(EXTRA_ERROR, if (effectiveStatus == status) error else null)
                ContextCompat.startForegroundService(context, service)
            } catch (e: Exception) {
                Log.e("CarrierStatusReceiver", "failed to persist carrier status", e)
            } finally {
                pending.finish()
            }
        }
    }
}
