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

    /**
     * The channel of the bridge's foreground-service notification.
     *
     * The app cannot make this notification go away. A foreground service must
     * post one, and NotificationManagerService raises an FGS channel an app
     * set to MIN or NONE back to LOW. Only the user can turn it off, and that
     * sticks: once the channel has shown an FGS notification, a user-locked
     * NONE is left alone and the service keeps running with its notification
     * blocked. The name and description are what the user reads in the system
     * settings, so they say it is safe to turn off. The id must never change: a
     * new channel would start unlocked and show the notification again.
     * Re-creating it on every start only updates name and description; the
     * importance the user chose stays.
     */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "상주 알림 (동기화 서비스)",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "백그라운드 동기화 중 Android가 요구하는 알림입니다. 꺼도 동기화는 계속됩니다." },
        )
    }

    /** System notification settings for this channel, where the user can turn it off. */
    fun channelSettingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)

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
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
