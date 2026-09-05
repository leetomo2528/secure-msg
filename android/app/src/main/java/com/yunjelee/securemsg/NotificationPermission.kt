package com.yunjelee.securemsg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The POST_NOTIFICATIONS gate every notification poster runs first.
 *
 * Its own object rather than a member of [SmsNotifier]: the auto-update
 * receivers post notifications too, and they must not take a dependency on the
 * SMS notifier just to ask a boolean.
 */
object NotificationPermission {
    private const val TAG = "NotifPermission"

    /**
     * Whether a notification posted now would reach the shade at all.
     *
     * [consequence] names what is lost when it would not, and is logged:
     * without that line a revoked permission is indistinguishable from "the
     * thing never happened" in a bug report.
     */
    fun canPost(context: Context, consequence: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; $consequence")
            return false
        }
        return true
    }
}
