package com.yunjelee.securemsg

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Runs in the freshly installed version right after a self-update replaced the
 * package — including the silent path, where no activity ever comes up to do
 * the usual restore-time reconciliation.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        restartBridge(context)
        reconcilePendingUpdate(context)
    }

    /** Same guard as [BootReceiver]: the FGS type is rejected without role and
     * permissions. MY_PACKAGE_REPLACED is exempt from background-FGS-start
     * restrictions, so the try/catch only covers OEM oddities. */
    private fun restartBridge(context: Context) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        val hasRole = roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
            roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        val hasPermissions = listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.READ_SMS,
        ).all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (!hasRole || !hasPermissions) return
        val svc = Intent(context, SmsBridgeService::class.java).apply {
            action = SmsBridgeService.ACTION_START_BRIDGE
        }
        try {
            context.startForegroundService(svc)
        } catch (e: RuntimeException) {
            Log.w(TAG, "bridge restart after update rejected", e)
        }
    }

    private fun reconcilePendingUpdate(context: Context) {
        val updater = AppUpdater(context.applicationContext, AppUpdater.buildHttp())
        val pending = updater.pendingUpdate() ?: return
        val packageUpdatedAt = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }
        if (!UpdateValidation.installedTargetSatisfied(
                pending.info.versionName,
                BuildConfig.VERSION_NAME,
                packageUpdatedAt,
                pending.file.lastModified(),
            )
        ) {
            // Replaced by something other than the pending target (sideload,
            // downgrade dev flow): leave the entry for the activity's restore.
            return
        }
        pending.file.delete()
        updater.clearPendingUpdate()
        InstallResultReceiver.cancelConfirmNotification(context)
        // Read off the entry loaded above: clearing dropped the only persisted
        // copy of the notes, and this is the last moment they exist.
        postUpdatedNotification(context, UpdateNotes.format(pending.info.notes))
    }

    /**
     * Fires once per update, not once per broadcast: the manifest filter is
     * MY_PACKAGE_REPLACED alone (no BOOT_COMPLETED), and the caller has already
     * consumed the pending entry — a repeat broadcast finds none and returns
     * before reaching here.
     */
    private fun postUpdatedNotification(context: Context, notes: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Own LOW channel: the confirm channel is IMPORTANCE_HIGH, and a
        // completion notice after a silent install must not heads-up or sound.
        manager.createNotificationChannel(
            NotificationChannel(
                DONE_CHANNEL_ID,
                "업데이트 완료",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val builder = Notification.Builder(context, DONE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(
                "SecureMsg가 v${BuildConfig.VERSION_NAME}(으)로 업데이트되었습니다",
            )
            .setAutoCancel(true)
        if (notes.isNotEmpty()) {
            // Collapsed, the shade has room for one line; the rest sits behind
            // the expander. A release with no usable body keeps the bare title
            // instead — an empty expanded notification says less than none.
            builder.setContentText(notes.substringBefore('\n'))
                .setStyle(Notification.BigTextStyle().bigText(notes))
        }
        if (launch != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE),
            )
        }
        manager.notify(DONE_TAG, DONE_ID, builder.build())
    }

    private companion object {
        const val TAG = "PackageReplaced"
        const val DONE_CHANNEL_ID = "securemsg_update_done"
        const val DONE_TAG = "update_done"
        const val DONE_ID = 3
    }
}
