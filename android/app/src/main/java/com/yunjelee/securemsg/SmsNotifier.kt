package com.yunjelee.securemsg

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat

object SmsNotifier {
    private const val CHANNEL_ID = "securemsg_sms"
    const val ACTION_OPEN_CONVERSATION = "com.yunjelee.securemsg.OPEN_CONVERSATION"
    const val EXTRA_CID = "conversation_cid"
    const val EXTRA_PHONE = "conversation_phone"
    const val EXTRA_REQUEST_ID = "conversation_request_id"

    fun notifyIncoming(
        context: Context,
        phoneNumber: String,
        body: String,
        date: Long,
        cid: String? = null,
        messageIdentity: String = "$phoneNumber:$date",
    ) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SMS 수신",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "차단되지 않은 SMS 알림" },
        )

        val normalizedPhone = PhoneNumberNormalizer.normalize(phoneNumber)
        val requestId = messageIdentity.ifBlank { "$normalizedPhone:$date" }
        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CONVERSATION
            // Intent data participates in PendingIntent identity; extras do not.
            data = Uri.Builder()
                .scheme("securemsg")
                .authority("conversation")
                .appendPath(requestId)
                .build()
            putExtra(EXTRA_CID, cid)
            putExtra(EXTRA_PHONE, normalizedPhone)
            putExtra(EXTRA_REQUEST_ID, requestId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Data is part of PendingIntent equality, so even the rare case where two
        // requestId hash codes collide still produces independent tap targets.
        val requestCode = requestId.hashCode() and Int.MAX_VALUE
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(phoneNumber)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()

        // A string notification tag avoids the collision limits of Android's Int id.
        manager.notify(requestId, 1, notification)
    }
}
