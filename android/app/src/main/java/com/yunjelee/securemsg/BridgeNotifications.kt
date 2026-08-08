package com.yunjelee.securemsg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Foreground-service notification plumbing for the SMS bridge. */
object BridgeNotifications {
    const val CHANNEL_ID = "securemsg_bridge"
    const val NOTIF_ID = 1

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SecureMsg Bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "SMS 동기화 백그라운드 서비스" },
        )
    }

    fun build(context: Context): Notification {
        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("SecureMsg SMS Bridge")
            .setContentText("다기기 SMS 동기화 활성")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
