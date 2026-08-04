package com.yunjelee.securemsg

import android.app.Service
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
                val bridge = Intent(this, SmsBridgeService::class.java)
                    .setAction(SmsBridgeService.ACTION_SEND_LOCAL_SMS)
                    .putExtra(SmsBridgeService.EXTRA_PHONE, phone)
                    .putExtra(SmsBridgeService.EXTRA_BODY, text)
                try {
                    ContextCompat.startForegroundService(this, bridge)
                } catch (e: RuntimeException) {
                    // The platform invoked this service specifically to satisfy a
                    // quick reply. Preserve carrier functionality if an OEM blocks
                    // the bridge FGS; only cross-device relay is deferred/lost.
                    Log.e("SmsRespondService", "Bridge start rejected; sending carrier-only", e)
                    SmsSender.send(this, phone, text)
                }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
