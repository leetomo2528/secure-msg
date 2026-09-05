package com.yunjelee.securemsg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Supports the default SMS role's quick reply contract. */
class SmsRespondService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val phone = intent.data?.schemeSpecificPart.orEmpty()
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (phone.isNotBlank() && text.isNotBlank()) {
                LocalSmsBridge.send(this, phone, text)
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * How an outgoing SMS composed outside the app's own UI reaches the network:
 * the bridge runs OutgoingSmsDispatcher (carrier send + relay to web + thread
 * insert), so sending is never reimplemented at a call site.
 */
internal object LocalSmsBridge {
    private const val TAG = "LocalSmsBridge"

    fun send(context: Context, phone: String, text: String) {
        val bridge = Intent(context, SmsBridgeService::class.java)
            .setAction(SmsBridgeService.ACTION_SEND_LOCAL_SMS)
            .putExtra(SmsBridgeService.EXTRA_PHONE, phone)
            .putExtra(SmsBridgeService.EXTRA_BODY, text)
        try {
            ContextCompat.startForegroundService(context, bridge)
        } catch (e: RuntimeException) {
            // Preserve carrier functionality if an OEM blocks the bridge FGS;
            // only cross-device relay is deferred/lost.
            Log.e(TAG, "Bridge start rejected; sending carrier-only", e)
            SmsSender.send(context, phone, text)
        }
    }
}
