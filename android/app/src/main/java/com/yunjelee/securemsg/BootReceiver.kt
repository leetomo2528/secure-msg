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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
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
            } catch (_: RuntimeException) {
                // The user can reopen the app to restart the bridge if the OEM
                // blocks foreground-service startup during boot.
            }
        }
    }
}
