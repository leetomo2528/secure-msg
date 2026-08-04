package com.yunjelee.securemsg

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Carrier MMS sender using the Android framework's carrier-configured MMSC. */
object MmsSender {
    private const val TAG = "MmsSender"
    private const val AUTHORITY = "com.yunjelee.securemsg.mms"
    private const val EXTRA_PDU_ID = "pdu_id"

    fun send(
        context: Context,
        phoneNumber: String,
        content: RelayContent,
        messageId: String,
        cid: String,
        seq: Int,
    ): Boolean {
        var pduId: String? = null
        return try {
            require(content.type == RelayContentCodec.TYPE_MMS) { "not an MMS content" }
            val id = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
            require(id.length >= 8) { "invalid MMS message id" }
            pduId = id
            val dir = File(context.cacheDir, "mms-pdu").apply { mkdirs() }
            val file = File(dir, "$id.pdu")
            val pdu = MmsPduComposer.compose(
                from = "insert-address-token",
                to = phoneNumber,
                subject = content.subject,
                text = content.text,
                attachments = content.attachments,
            )
            FileOutputStream(file).use { it.write(pdu) }
            val uri = Uri.parse("content://$AUTHORITY/$id")
            listOf("com.android.phone", "com.android.mms.service").forEach { pkg ->
                try {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // OEMs use different package names; the framework may retain
                    // the creator permission while consuming this PendingIntent.
                }
            }
            val status = PendingIntent.getBroadcast(
                context,
                id.hashCode() and 0x7fffffff,
                Intent(context, CarrierStatusReceiver::class.java)
                    .setAction(CarrierStatusReceiver.ACTION_SENT)
                    // Extras are NOT part of PendingIntent equality; the data
                    // URI gives each send its own callback identity so
                    // FLAG_UPDATE_CURRENT cannot merge two overlapping sends.
                    .setData(Uri.parse("securemsg://mms-send/$id"))
                    .putExtra(CarrierStatusReceiver.EXTRA_MID, messageId)
                    .putExtra(CarrierStatusReceiver.EXTRA_CID, cid)
                    .putExtra(CarrierStatusReceiver.EXTRA_SEQ, seq)
                    .putExtra(CarrierStatusReceiver.EXTRA_PART, 0)
                    .putExtra(CarrierStatusReceiver.EXTRA_PART_COUNT, 1)
                    .putExtra(EXTRA_PDU_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val sms = context.getSystemService(SmsManager::class.java)
            sms.sendMultimediaMessage(context, uri, null, Bundle(), status, messageId.hashCode().toLong())
            Log.i(
                TAG,
                "MMS dispatch accepted to ${PhoneNumberNormalizer.redact(phoneNumber)} mid=$messageId",
            )
            true
        } catch (e: Exception) {
            deletePdu(context, pduId)
            Log.e(TAG, "MMS dispatch failed", e)
            false
        }
    }

    fun deletePdu(context: Context, id: String?) {
        if (id.isNullOrBlank() || !id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) return
        try {
            val uri = Uri.parse("content://$AUTHORITY/$id")
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            File(context.cacheDir, "mms-pdu/$id.pdu").delete()
        } catch (e: Exception) {
            Log.w(TAG, "failed to delete temporary MMS PDU", e)
        }
    }
}
