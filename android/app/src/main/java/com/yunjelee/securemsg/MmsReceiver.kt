package com.yunjelee.securemsg

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

class MmsReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_MMS_DOWNLOAD_COMPLETE = "com.yunjelee.securemsg.MMS_DOWNLOAD_COMPLETE"
        const val EXTRA_MMS_ID = "mms_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MMS_DOWNLOAD_COMPLETE -> {
                val id = intent.getLongExtra(EXTRA_MMS_ID, -1L)
                if (resultCode == Activity.RESULT_OK && id > 0) {
                    startProcessing(context, id)
                } else {
                    Log.e("MmsReceiver", "MMS download failed: result=$resultCode id=$id")
                    if (id > 0) MmsProvider.delete(context, id)
                    startProcessing(context, null)
                }
            }
            "android.provider.Telephony.WAP_PUSH_DELIVER" -> handleWapPush(context, intent)
        }
    }

    private fun handleWapPush(context: Context, intent: Intent) {
        val location = findContentLocation(intent)
        if (location.isNullOrBlank()) {
            // Some carriers insert the downloaded row before dispatching this
            // broadcast; the bridge will inspect recent provider rows.
            startProcessing(context, null)
            return
        }
        val target = MmsProvider.createDownloadTarget(context)
        if (target == null) {
            startProcessing(context, null)
            return
        }
        val (id, uri) = target
        try {
            if (!isReceiverRegistered(context)) {
                // The callback would be dropped (app disabled/reinstalled).
                MmsProvider.delete(context, id)
                startProcessing(context, null)
                return
            }
            val callback = PendingIntent.getBroadcast(
                context,
                (id and 0x7fffffff).toInt(),
                Intent(context, MmsReceiver::class.java)
                    .setAction(ACTION_MMS_DOWNLOAD_COMPLETE)
                    // Data disambiguates the PendingIntent identity: extras are
                    // NOT part of PendingIntent equality, so without it two
                    // overlapping downloads would share one callback whose
                    // EXTRA_MMS_ID gets overwritten by FLAG_UPDATE_CURRENT.
                    .setData(android.net.Uri.parse("securemsg://mms-download/$id"))
                    .putExtra(EXTRA_MMS_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val sms = context.getSystemService(SmsManager::class.java)
            sms.downloadMultimediaMessage(context, location, uri, Bundle(), callback, id)
            Log.i("MmsReceiver", "MMS download started id=$id")
        } catch (e: Exception) {
            Log.e("MmsReceiver", "MMS download start failed", e)
            MmsProvider.delete(context, id)
            startProcessing(context, null)
        }
    }

    private fun startProcessing(context: Context, id: Long?) {
        val service = Intent(context, SmsBridgeService::class.java)
            .setAction(SmsBridgeService.ACTION_INCOMING_MMS)
        if (id != null && id > 0) service.putExtra(EXTRA_MMS_ID, id)
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (e: RuntimeException) {
            Log.e("MmsReceiver", "MMS bridge service start rejected", e)
        }
    }

    private fun isReceiverRegistered(context: Context): Boolean = try {
        context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, MmsReceiver::class.java),
            0,
        )
        true
    } catch (_: Exception) {
        false
    }

    private fun findContentLocation(intent: Intent): String? {
        val direct = intent.getStringExtra("contentLocation")
            ?: intent.getStringExtra("content-location")
        if (!direct.isNullOrBlank()) validHttpUrl(direct)?.let { return it }
        val params = if (Build.VERSION.SDK_INT >= 33) {
            intent.getSerializableExtra("contentTypeParameters", java.util.HashMap::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent.getSerializableExtra("contentTypeParameters") as? java.util.HashMap<*, *>)
        }
        val fromParams = params?.entries?.firstOrNull { (key, _) ->
            key.toString().lowercase().contains("content-location")
        }?.value?.toString()
        if (!fromParams.isNullOrBlank()) validHttpUrl(fromParams)?.let { return it }
        return validHttpUrl(MmsContentLocationParser.find(intent.getByteArrayExtra("data")))
    }

    private fun validHttpUrl(value: String?): String? {
        if (value.isNullOrBlank() || value.length > 2048) return null
        val uri = android.net.Uri.parse(value)
        val supportedScheme = uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)
        return value.takeIf { supportedScheme && !uri.host.isNullOrBlank() }
    }
}

/** Extracts the carrier URL from the MMS notification PDU delivered in `data`.
 * Android already strips the outer WAP push envelope on common OEM builds. A
 * bounded URL scan is intentionally tolerant of carrier-specific MMS headers. */
internal object MmsContentLocationParser {
    private const val MAX_URL_BYTES = 2048

    fun find(data: ByteArray?): String? {
        if (data == null || data.isEmpty()) return null
        val prefixes = listOf("https://", "http://")
        for (start in data.indices) {
            val prefix = prefixes.firstOrNull { candidate ->
                start + candidate.length <= data.size && candidate.indices.all { offset ->
                    data[start + offset].toInt().and(0xff) == candidate[offset].code
                }
            } ?: continue
            var end = start + prefix.length
            val limit = minOf(data.size, start + MAX_URL_BYTES)
            while (end < limit) {
                val value = data[end].toInt() and 0xff
                if (value == 0 || value <= 0x20 || value == 0x7f) break
                end += 1
            }
            if (end > start + prefix.length) {
                return data.copyOfRange(start, end).toString(Charsets.ISO_8859_1)
            }
        }
        return null
    }
}
