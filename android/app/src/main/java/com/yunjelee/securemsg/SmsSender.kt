package com.yunjelee.securemsg

import android.app.PendingIntent
import android.content.Intent
import android.telephony.SmsManager
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import java.util.UUID

object SmsSender {

    const val MAX_MULTIPART_SEGMENTS = 20

    /**
     * Send an SMS via the carrier network. Returns true on success.
     * This is the ONLY point where plaintext leaves the E2E system onto the
     * carrier SMS network — by design, since SMS itself cannot be E2E encrypted.
     */
    fun send(
        context: Context,
        phoneNumber: String,
        text: String,
        messageId: String = UUID.randomUUID().toString(),
        cid: String? = null,
        seq: Int = 0,
    ): Boolean {
        var providerId: Long? = null
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(text)
            require(parts.isNotEmpty()) { "SMS body is empty" }
            require(parts.size <= MAX_MULTIPART_SEGMENTS) {
                "SMS exceeds $MAX_MULTIPART_SEGMENTS carrier segments"
            }
            providerId = SmsProvider.insertSent(
                context,
                phoneNumber,
                text,
                System.currentTimeMillis(),
                Telephony.Sms.STATUS_PENDING,
            )
            val sentIntents = ArrayList(parts.indices.map { part ->
                statusIntent(
                    context, CarrierStatusReceiver.ACTION_SENT, messageId, cid, seq,
                    providerId, part, parts.size,
                )
            })
            val deliveryIntents = ArrayList(parts.indices.map { part ->
                statusIntent(
                    context, CarrierStatusReceiver.ACTION_DELIVERED, messageId, cid, seq,
                    providerId, part, parts.size,
                )
            })
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, text, sentIntents[0], deliveryIntents[0])
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, deliveryIntents)
            }
            Log.i(
                "SmsSender",
                "SMS dispatch accepted for ${PhoneNumberNormalizer.redact(phoneNumber)} " +
                    "(${text.length} chars), mid=$messageId",
            )
            true
        } catch (e: Exception) {
            providerId?.let {
                SmsProvider.updateSentStatus(
                    context, it, Telephony.Sms.STATUS_FAILED, failed = true,
                )
            }
            Log.e("SmsSender", "SMS send failed", e)
            false
        }
    }

    private fun statusIntent(
        context: Context,
        action: String,
        messageId: String,
        cid: String?,
        seq: Int,
        providerId: Long?,
        part: Int,
        partCount: Int,
    ): PendingIntent {
        val requestCode = (messageId.hashCode() and 0x7fffffff) xor (part shl 8) xor action.hashCode()
        val intent = Intent(context, CarrierStatusReceiver::class.java)
            .setAction(action)
            .setData(
                Uri.Builder()
                    .scheme("securemsg")
                    .authority("carrier-status")
                    .appendPath(messageId)
                    .appendPath(part.toString())
                    .build(),
            )
            .putExtra(CarrierStatusReceiver.EXTRA_MID, messageId)
            .putExtra(CarrierStatusReceiver.EXTRA_CID, cid)
            .putExtra(CarrierStatusReceiver.EXTRA_SEQ, seq)
            .putExtra(CarrierStatusReceiver.EXTRA_PROVIDER_ID, providerId ?: -1L)
            .putExtra(CarrierStatusReceiver.EXTRA_PART, part)
            .putExtra(CarrierStatusReceiver.EXTRA_PART_COUNT, partCount)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
