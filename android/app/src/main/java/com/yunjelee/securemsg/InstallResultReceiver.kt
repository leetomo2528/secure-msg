package com.yunjelee.securemsg

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local feed of terminal PackageInstaller statuses from
 * [InstallResultReceiver] to whatever UI happens to be alive. Consumers re-read
 * the persisted pending update rather than trusting the status value: the
 * receiver has already reconciled it, and a replayed emission then merely
 * re-syncs the banner to prefs instead of rewinding it.
 */
object InstallEvents {
    // replay = 1: the terminal status usually lands while the user is looking at
    // the system installer, i.e. with MainActivity stopped and not collecting.
    private val terminal = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 4)

    val statuses: SharedFlow<Int> = terminal.asSharedFlow()

    fun emit(status: Int) {
        terminal.tryEmit(status)
    }
}

/**
 * PackageInstaller session status endpoint for the in-app updater.
 *
 * A receiver rather than a PendingIntent.getActivity into MainActivity: on
 * One UI (issue #5, re-reported) the activity delivery of PENDING_USER_ACTION
 * never surfaced the system confirm dialog, leaving a buttonless "waiting"
 * banner until the app was killed. A broadcast always arrives; from here the
 * confirm dialog is launched directly when the app is foreground, and a
 * notification tap — a user-initiated launch that background-activity-launch
 * blocking cannot drop — covers every other case.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val updater = AppUpdater(context.applicationContext, AppUpdater.buildHttp())
        val pending = updater.pendingUpdate()
        if (pending == null) {
            // The flow this callback belongs to was closed/dismissed; a confirm
            // notification it may have posted has no live flow left to clear it.
            cancelConfirmNotification(context)
            Log.w(TAG, "PackageInstaller callback for a cleared update; dropped")
            return
        }
        if (!UpdateValidation.isAuthorizedInstallCallback(
                intent.getStringExtra(EXTRA_CALLBACK_TOKEN),
                pending.callbackToken,
            )
        ) {
            // An abandoned prior session reporting in while a new attempt runs —
            // the new attempt may own the posted confirm notification, keep it.
            Log.w(TAG, "Ignoring unauthenticated PackageInstaller callback")
            return
        }
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, InstallResults.FAILURE)) {
            InstallResults.PENDING_USER_ACTION ->
                handlePendingUserAction(context, updater, pending, intent)
            InstallResults.SUCCESS -> {
                // Usually the process is replaced before this runs; restore-time
                // reconciliation in MainActivity remains the backstop.
                pending.file.delete()
                updater.clearPendingUpdate()
                cancelConfirmNotification(context)
                InstallEvents.emit(status)
            }
            else -> {
                updater.setPendingInstallFailure(InstallResults.guidance(status))
                cancelConfirmNotification(context)
                InstallEvents.emit(status)
            }
        }
    }

    private fun handlePendingUserAction(
        context: Context,
        updater: AppUpdater,
        pending: PendingUpdate,
        intent: Intent,
    ) {
        if (!UpdateValidation.shouldHonorPendingUserAction(pending.state)) {
            // The platform redelivers the status intent; a confirm request for a
            // session that is no longer submitted is a stale replay, and
            // launching it opens a dead installer page.
            Log.w(TAG, "Ignoring stale PENDING_USER_ACTION replay")
            return
        }
        val confirmation = confirmationIntent(intent)
        if (confirmation == null) {
            updater.setPendingInstallFailure(
                "시스템 설치 확인 정보를 받지 못했습니다. 다시 시도해 주세요.",
            )
            InstallEvents.emit(InstallResults.FAILURE)
            return
        }
        // Foreground path: launched off a copy so the notification below keeps
        // wrapping the system's intent exactly as it was delivered.
        try {
            context.startActivity(Intent(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "confirm dialog launch failed; notification is the remaining path", e)
        }
        // The startActivity above is dropped *silently* when the app is not
        // foreground (background-activity-launch blocking), so the notification
        // is posted unconditionally: tapping it is a user-initiated launch the
        // platform always honours. Terminal statuses take it down.
        postConfirmNotification(context, pending.info.versionName, confirmation)
    }

    /** The system confirm dialog carried by a PENDING_USER_ACTION callback. */
    @Suppress("DEPRECATION")
    private fun confirmationIntent(callback: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            callback.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            callback.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun postConfirmNotification(context: Context, version: String, confirmation: Intent) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Without this line a revoked permission is indistinguishable from
            // "the confirm dialog never appeared" in a bug report.
            Log.w(TAG, "POST_NOTIFICATIONS not granted; confirm only reachable in-app")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "업데이트",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "앱 업데이트 설치 확인" },
        )
        // IMMUTABLE is correct even though the session callback itself is
        // mutable: this wraps the system's own confirmation intent verbatim,
        // and nothing may be filled into it at send time.
        val tap = PendingIntent.getActivity(
            context,
            0,
            confirmation,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("업데이트 설치 확인 필요")
            .setContentText("눌러서 v$version 설치를 계속하세요")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        // Fixed tag/id: a re-committed session replaces the previous prompt
        // instead of stacking a second one.
        manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.yunjelee.securemsg.INSTALL_STATUS"
        const val EXTRA_CALLBACK_TOKEN = "install_callback_token"

        private const val CHANNEL_ID = "securemsg_update"
        private const val NOTIFICATION_TAG = "update_install"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "InstallResult"

        /** Takes down the confirm prompt when the flow ends outside the receiver
         * (user-cancelled from the banner); terminal statuses cancel it here. */
        fun cancelConfirmNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
        }
    }
}
