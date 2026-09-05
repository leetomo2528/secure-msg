package com.yunjelee.securemsg

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        BridgeGate.start(context) {
            // The user can reopen the app to restart the bridge if the OEM
            // blocks foreground-service startup during boot.
        }
    }
}

/**
 * The gate every unattended bridge start runs first, and the start itself.
 *
 * Kept beside [BootReceiver] because that copy was already the reference one:
 * [PackageReplacedReceiver] said "same guard as BootReceiver" while spelling
 * the whole thing out again, and MainActivity carried a third. The list below
 * is a gate, not a hint — the platform refuses the remoteMessaging
 * foreground-service type unless every one of them is held, so a permission
 * added to one copy only would have left the other paths starting a service
 * Android then kills.
 */
object BridgeGate {
    /** Requested together, and all of them required before [start] will fire. */
    val SMS_PERMISSIONS = listOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.RECEIVE_WAP_PUSH,
        Manifest.permission.READ_SMS,
    )

    fun hasSmsPermissions(context: Context): Boolean = SMS_PERMISSIONS.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether a bridge start would be accepted right now.
     *
     * Keeps the receivers' `isRoleAvailable` test on top of `isRoleHeld` rather
     * than MainActivity's bare `isRoleHeld`: the two had drifted, and the gate
     * they share has to be the stricter of them, because a false pass here is a
     * foreground service the platform rejects on arrival.
     */
    fun canRun(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
            roleManager.isRoleHeld(RoleManager.ROLE_SMS) &&
            hasSmsPermissions(context)
    }

    /**
     * Starts the bridge when [canRun] allows it. [onRejected] stays with the
     * caller because the three disagree on purpose: BootReceiver swallows an
     * OEM refusal (reopening the app restarts the bridge), the other two log.
     */
    fun start(context: Context, onRejected: (RuntimeException) -> Unit) {
        if (!canRun(context)) return
        val svc = Intent(context, SmsBridgeService::class.java).apply {
            action = SmsBridgeService.ACTION_START_BRIDGE
        }
        try {
            context.startForegroundService(svc)
        } catch (e: RuntimeException) {
            onRejected(e)
        }
    }
}
