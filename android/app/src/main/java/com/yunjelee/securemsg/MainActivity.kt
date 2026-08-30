package com.yunjelee.securemsg

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yunjelee.securemsg.ui.LastOpened
import com.yunjelee.securemsg.ui.LoginScreen
import com.yunjelee.securemsg.ui.MainScreen
import com.yunjelee.securemsg.ui.Sm
import com.yunjelee.securemsg.ui.UpdateFlow
import com.yunjelee.securemsg.ui.UpdateUiState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-activity host. Owns SMS role/permission state, the in-app update
 * flow, and the login→main navigation; all screens live in `ui/`.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        /**
         * How long a submitted session may sit without its PENDING_USER_ACTION
         * surfacing anything before the banner regains its buttons. Long enough
         * for a slow system installer spin-up, short enough that the One UI
         * stall (issue #5) does not read as a hang.
         */
        const val INSTALL_CONFIRM_WATCHDOG_MS = 25_000L

        /**
         * Process-static: set when this process submits an install session.
         * Rotation and other configuration changes run onCreate again with the
         * submitting process — and its receiver, coroutine and watchdog — all
         * still alive, so restore may only treat SESSION_SUBMITTED as orphaned
         * when this flag says no submission happened in this process.
         */
        @Volatile
        var installSubmittedInThisProcess = false
    }

    private var smsRoleHeld by mutableStateOf(false)
    private var smsPermissionsGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(true)
    private var status by mutableStateOf("연결 확인 중…")
    private var conversationTarget by mutableStateOf<ConversationTarget?>(null)

    // In-app self-update (game-style: detect → download → install prompt)
    private val updater by lazy { AppUpdater(applicationContext, AppUpdater.buildHttp()) }
    private var updateState by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
    private var updateMessage by mutableStateOf<String?>(null)
    private var autoUpdateEnabled by mutableStateOf(true)
    private var pendingInstallFile: File? = null

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        smsPermissionsGranted = hasSmsPerms()
        // Safe to ask now: the SMS permission dialog has already closed.
        requestNotificationPermission()
        if (isDefaultSmsApp() && smsPermissionsGranted) startBridgeService()
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionGranted = hasNotificationPermission()
    }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        smsRoleHeld = isDefaultSmsApp()
        smsPermissionsGranted = hasSmsPerms()
        if (smsRoleHeld && !smsPermissionsGranted) {
            // The SMS permission dialog goes first; the notification prompt is
            // chained off its result because the framework rejects a second
            // permission request while one is still in flight.
            requestPerms()
        } else if (smsRoleHeld) {
            // Becoming the default SMS app is the moment notifications start
            // mattering, so a user who dismissed the startup prompt gets a
            // second, contextual chance here.
            requestNotificationPermission()
            startBridgeService()
        }
    }

    private fun requestPerms() {
        // Restricted SMS permissions must be requested only after the user has
        // granted this app the default SMS role.
        if (!isDefaultSmsApp()) {
            requestSmsRole()
            return
        }
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.READ_SMS,
        )
        permsLauncher.launch(perms.toTypedArray())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestSmsRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        ) {
            roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else if (roleManager.isRoleHeld(RoleManager.ROLE_SMS) && !hasSmsPerms()) {
            requestPerms()
        }
    }

    private fun isDefaultSmsApp(): Boolean {
        return getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onPause() {
        // onPause, not onStop: in split-screen the activity is started but not
        // in front, and treating that as "on screen" swallowed the alerts of
        // the visible conversation entirely.
        SmsNotifier.setAppForeground(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // An open conversation only suppresses its own notifications while it
        // is actually in front; see onPause.
        SmsNotifier.setAppForeground(true)
        smsRoleHeld = isDefaultSmsApp()
        smsPermissionsGranted = hasSmsPerms()
        notificationPermissionGranted = hasNotificationPermission()
        autoUpdateEnabled = updater.autoCheckEnabled()
        // Returning through a notification/system surface must also wake durable
        // provider import and relay flushing; service-side work is idempotent.
        if (smsRoleHeld && smsPermissionsGranted) startBridgeService()
        // The user may have just toggled "install unknown apps" in system settings.
        val pending = updater.pendingUpdate()
        if (pending?.state == PendingInstallState.AWAITING_PERMISSION &&
            updater.canInstallPackages()
        ) {
            pendingInstallFile = pending.file
            startInstall(pending.info, pending.file)
        }
        // Returning from the legacy installer (or a confirm dialog) via recents
        // never recreates this singleTask activity, so onCreate's reconciliation
        // does not run again. Success is the only verdict allowed here: marking
        // a failure while the installer might still be up on screen would race
        // its own callback.
        if (pending != null &&
            (pending.state == PendingInstallState.SESSION_SUBMITTED ||
                pending.state == PendingInstallState.FALLBACK_LAUNCHED)
        ) {
            val packageUpdatedAt = try {
                packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            } catch (_: PackageManager.NameNotFoundException) {
                0L
            }
            if (UpdateValidation.installedTargetSatisfied(
                    pending.info.versionName,
                    BuildConfig.VERSION_NAME,
                    packageUpdatedAt,
                    pending.file.lastModified(),
                )
            ) {
                pending.file.delete()
                updater.clearPendingUpdate()
                InstallResultReceiver.cancelConfirmNotification(this)
                pendingInstallFile = null
                updateState = UpdateUiState.Idle
            }
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (!manual && updater.pendingUpdate() != null) return
        val state = updateState
        if (state is UpdateUiState.Checking || state is UpdateUiState.Downloading ||
            state is UpdateUiState.Installing || state is UpdateUiState.SessionSubmitted
        ) return
        updateState = UpdateUiState.Checking
        if (manual) updateMessage = "GitHub 릴리스 확인 중…"
        lifecycleScope.launch(Dispatchers.IO) {
            val result = updater.check()
            withContext(Dispatchers.Main) {
                when (result) {
                    is UpdateCheckResult.Available -> {
                        updateMessage = "새 버전 v${result.info.versionName} 사용 가능합니다."
                        updateState = UpdateUiState.Available(result.info)
                    }
                    is UpdateCheckResult.UpToDate -> {
                        updateMessage = "최신 버전입니다 (v${BuildConfig.VERSION_NAME})."
                        updateState = UpdateUiState.Idle
                    }
                    is UpdateCheckResult.Failed -> {
                        updateMessage = "업데이트 확인 실패: ${result.message}"
                        updateState = if (manual) {
                            UpdateUiState.Failed(result.message, null)
                        } else {
                            UpdateUiState.Idle
                        }
                    }
                }
            }
        }
    }

    private fun startDownload(info: UpdateInfo) {
        updateState = UpdateUiState.Downloading(info, 0)
        updateMessage = null
        lifecycleScope.launch {
            try {
                val file = updater.download(info) { pct ->
                    withContext(Dispatchers.Main) {
                        updateState = UpdateUiState.Downloading(info, pct)
                    }
                }
                withContext(Dispatchers.Main) { startInstall(info, file) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateMessage = "다운로드 실패: ${e.message}"
                    updateState = UpdateUiState.Failed("다운로드 실패: ${e.message}", info)
                }
            }
        }
    }

    private fun startInstall(info: UpdateInfo, file: File) {
        val existing = updater.pendingUpdate()
        if (!UpdateValidation.shouldStartInstallSession(
                existing?.state,
                // 재시도 on the blocked banner reaches here with the persisted
                // state still SESSION_SUBMITTED (watchdog path); it must
                // resubmit, not just repaint the waiting banner.
                retryingBlockedSession = updateState is UpdateUiState.InstallBlocked,
            )
        ) {
            val submitted = checkNotNull(existing)
            pendingInstallFile = submitted.file
            updateState = UpdateUiState.SessionSubmitted(submitted.info, submitted.file)
            return
        }
        if (updateState is UpdateUiState.Installing) return

        try {
            updater.verifyApkSigningCertificate(file)
        } catch (e: Exception) {
            file.delete()
            updater.clearPendingUpdate()
            pendingInstallFile = null
            val detail = e.message ?: "APK 검증 실패"
            updateMessage = "업데이트 검증 실패: $detail"
            updateState = UpdateUiState.Failed(updateMessage!!, info)
            return
        }

        // A fresh token every attempt, never reused from the persisted entry:
        // installViaSession abandons the previous attempt's session, whose
        // ABORTED (and any late confirm) callback then arrives carrying the
        // old token. A reused token would authenticate those callbacks and let
        // them fail the new attempt before its own confirm is delivered.
        val callbackToken = UUID.randomUUID().toString()
        updater.persistPendingUpdate(
            info,
            file,
            PendingInstallState.READY,
            callbackToken,
        )
        pendingInstallFile = file
        updateState = UpdateUiState.Ready(info, file)
        if (updater.canInstallPackages()) {
            updater.setPendingInstallState(PendingInstallState.SESSION_SUBMITTED)
            installSubmittedInThisProcess = true
            updateState = UpdateUiState.Installing(info, file)
            lifecycleScope.launch {
                val submitted = updater.installViaSession(file, callbackToken)
                withContext(Dispatchers.Main) {
                    if (submitted) {
                        // A terminal callback can arrive before this coroutine resumes.
                        // Never overwrite its FAILED/SUCCESS result with a stale waiting UI.
                        if (updater.pendingUpdate()?.state ==
                            PendingInstallState.SESSION_SUBMITTED
                        ) {
                            updateState = UpdateUiState.SessionSubmitted(info, file)
                        }
                    } else if (updater.claimFallbackLaunch()) {
                        // Returning from the legacy installer must not launch it again from onResume.
                        updateState = UpdateUiState.InstallBlocked(
                            info,
                            file,
                            "시스템 설치 화면으로 전환했습니다. 설치되지 않았다면 재시도해 주세요.",
                        )
                        try {
                            startActivity(updater.installIntent(file))
                        } catch (e: Exception) {
                            updater.setPendingInstallState(PendingInstallState.FAILED)
                            updateState = UpdateUiState.InstallBlocked(
                                info,
                                file,
                                "시스템 설치 화면을 열지 못했습니다: ${e.message ?: "알 수 없는 오류"}",
                            )
                        }
                    }
                }
            }
        } else {
            updater.setPendingInstallState(PendingInstallState.AWAITING_PERMISSION)
            updateState = UpdateUiState.NeedsPermission(info, file)
            updateMessage = "'이 앱의 설치 허용'을 켜면 자동으로 설치가 이어집니다."
            try {
                startActivity(updater.unknownSourcesSettingsIntent())
            } catch (_: Exception) {
                updateMessage = "설정 → 앱 → SecureMsg → '알 수 없는 앱 설치'를 허용해 주세요."
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConversationIntent(intent)
    }

    /**
     * Debug-build hook: fetch the latest release and treat it as newer so the
     * whole download → FileProvider → package-installer path can be exercised
     * end-to-end even when the running build is already up to date.
     */
    private fun testUpdateFlow() {
        val state = updateState
        if (state is UpdateUiState.Checking || state is UpdateUiState.Downloading) return
        updateState = UpdateUiState.Checking
        lifecycleScope.launch(Dispatchers.IO) {
            val result = updater.check(ignoreVersion = true)
            withContext(Dispatchers.Main) {
                when (result) {
                    is UpdateCheckResult.Available -> startDownload(result.info)
                    is UpdateCheckResult.Failed -> {
                        updateMessage = "업데이트 테스트 실패: ${result.message}"
                        updateState = UpdateUiState.Idle
                    }
                    is UpdateCheckResult.UpToDate -> {
                        updateMessage = "GitHub 릴리스에 APK 자산이 없습니다."
                        updateState = UpdateUiState.Idle
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The XML theme covers the launch window; this covers API 35+, where
        // the system draws edge-to-edge regardless and the status-bar icon
        // colour has to be asked for at runtime or white icons land on the
        // light background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)

        smsRoleHeld = isDefaultSmsApp()
        smsPermissionsGranted = hasSmsPerms()
        notificationPermissionGranted = hasNotificationPermission()
        // A messaging app that never asks is a messaging app that never notifies:
        // until now this was only reachable from a settings card the user had to
        // find on their own.
        requestNotificationPermission()

        lifecycleScope.launch(Dispatchers.IO) { updater.cleanupDownloads() }

        restorePendingUpdate()
        handleConversationIntent(intent)

        // Live half of the receiver flow: InstallResultReceiver reconciles the
        // persisted entry, then pings; the UI only ever re-renders prefs. The
        // replayed emission covers a terminal status that landed while this
        // activity was stopped behind the system installer.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallEvents.statuses.collect {
                    val pending = updater.pendingUpdate()
                    when {
                        // The flow is sticky (replay=1) and re-collected on
                        // every foreground entry, so both branches are gated on
                        // the banner still being in the install flow: a replayed
                        // failure must not stomp a Checking/Available/
                        // Downloading banner the user started afterwards.
                        pending?.state == PendingInstallState.FAILED &&
                            isInstallFlowState(updateState) -> {
                            pendingInstallFile = pending.file
                            updateState = pendingUiState(pending)
                            updateMessage = null
                        }
                        // SUCCESS cleared the entry (rare: replacement usually
                        // kills the process first). Guarded so a replayed old
                        // emission cannot reset an unrelated Available/
                        // Downloading banner to Idle.
                        pending == null && isInstallFlowState(updateState) -> {
                            pendingInstallFile = null
                            updateState = UpdateUiState.Idle
                        }
                    }
                }
            }
        }

        setContent { App() }
    }

    private fun isInstallFlowState(state: UpdateUiState): Boolean =
        state is UpdateUiState.Ready || state is UpdateUiState.Installing ||
            state is UpdateUiState.SessionSubmitted || state is UpdateUiState.NeedsPermission ||
            state is UpdateUiState.InstallBlocked

    private fun restorePendingUpdate() {
        val pending = updater.pendingUpdate() ?: return
        val packageUpdatedAt = try {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }
        val handedOff = pending.state == PendingInstallState.SESSION_SUBMITTED ||
            pending.state == PendingInstallState.FALLBACK_LAUNCHED
        if (handedOff &&
            UpdateValidation.installedTargetSatisfied(
                pending.info.versionName,
                BuildConfig.VERSION_NAME,
                packageUpdatedAt,
                pending.file.lastModified(),
            )
        ) {
            pending.file.delete()
            updater.clearPendingUpdate()
            InstallResultReceiver.cancelConfirmNotification(this)
            pendingInstallFile = null
            updateState = UpdateUiState.Idle
            return
        }
        if (pending.state == PendingInstallState.SESSION_SUBMITTED &&
            !installSubmittedInThisProcess
        ) {
            // No submission happened in this process (the flag rules out a mere
            // activity recreation), so the process that submitted is gone. The
            // broadcast receiver outlives it, but a session whose confirm was
            // never granted before the process died is not coming back on its
            // own — this used to be restored as a buttonless banner that also
            // gated every update check: the exact trap of issue #5. Convert it
            // to a failure the user can act on.
            val detail = "시스템 설치가 확인되지 않았습니다. 재시도하거나 닫은 뒤 다시 업데이트할 수 있습니다."
            updater.setPendingInstallFailure(detail)
            pendingInstallFile = pending.file
            updateState = UpdateUiState.InstallBlocked(pending.info, pending.file, detail)
            return
        }
        // A live SESSION_SUBMITTED lands here after a recreation and re-arms
        // the confirm watchdog through the SessionSubmitted state it restores.
        pendingInstallFile = pending.file
        updateState = pendingUiState(pending)
    }

    /** The banner a persisted pending update renders as. Shared by cold-start
     * restore and the live receiver events so both paths agree. */
    private fun pendingUiState(pending: PendingUpdate): UpdateUiState = when (pending.state) {
        PendingInstallState.AWAITING_PERMISSION ->
            UpdateUiState.NeedsPermission(pending.info, pending.file)
        PendingInstallState.FAILED -> UpdateUiState.InstallBlocked(
            pending.info,
            pending.file,
            // The receiver persists the status-specific guidance; the generic
            // line only covers entries written by builds without failureDetail.
            pending.failureDetail?.takeIf { it.isNotBlank() }
                ?: "이전 업데이트 설치에 실패했습니다. APK를 다시 검증한 뒤 재시도할 수 있습니다.",
        )
        PendingInstallState.FALLBACK_LAUNCHED -> UpdateUiState.InstallBlocked(
            pending.info,
            pending.file,
            "시스템 설치 화면에서 설치가 완료되지 않았습니다. 필요하면 재시도해 주세요.",
        )
        PendingInstallState.READY -> UpdateUiState.Ready(pending.info, pending.file)
        PendingInstallState.SESSION_SUBMITTED ->
            UpdateUiState.SessionSubmitted(pending.info, pending.file)
    }

    private fun hasSmsPerms(): Boolean {
        return listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.READ_SMS,
        ).all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Composable
    private fun App() {
        var creds by remember { mutableStateOf<SavedCredentials?>(null) }
        var localDeviceUsername by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            Credentials.observeDevice(this@MainActivity).collectLatest { localDevice ->
                localDeviceUsername = localDevice?.username
                creds = localDevice?.takeIf { it.token.isNotBlank() }
                loading = false
                // This also reacts when the bridge clears an expired/revoked
                // token, returning the visible activity to the login screen.
                if (creds != null) startBridgeService()
            }
        }

        // Watchdog for the One UI stall: PENDING_USER_ACTION that surfaces
        // nothing leaves SessionSubmitted's banner buttonless forever. The
        // persisted state is deliberately untouched — the confirm (or its
        // notification tap) may still land — the banner just regains its
        // 재시도/닫기 buttons. Keying on updateState cancels the timer whenever
        // the flow moves on.
        val observedUpdateState = updateState
        LaunchedEffect(observedUpdateState) {
            if (observedUpdateState !is UpdateUiState.SessionSubmitted) return@LaunchedEffect
            delay(INSTALL_CONFIRM_WATCHDOG_MS)
            if (updater.pendingUpdate()?.state == PendingInstallState.SESSION_SUBMITTED &&
                updateState is UpdateUiState.SessionSubmitted
            ) {
                updateState = UpdateUiState.InstallBlocked(
                    observedUpdateState.info,
                    observedUpdateState.file,
                    "시스템 설치 확인 창이 열리지 않았습니다. 알림창의 '업데이트 설치 확인' " +
                        "알림을 누르거나 재시도하세요.",
                )
            }
        }

        if (loading) {
            Box(
                Modifier.fillMaxSize().background(Sm.bg),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Sm.cyan)
            }
            return
        }

        if (creds == null) {
            LoginScreen(
                rememberedUsername = localDeviceUsername,
                onForgetLocalDevice = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        stopService(Intent(this@MainActivity, SmsBridgeService::class.java))
                        Credentials.clear(this@MainActivity)
                        BlocklistSync.clear(this@MainActivity)
                        ContactSync.clearStatus(this@MainActivity)
                        // Device-local prefs that are really per-account: a
                        // different account signing in here must not inherit
                        // the previous one's stars or read positions.
                        Favorites.clear(this@MainActivity)
                        LastOpened.clear(this@MainActivity)
                        AppDatabase.get(this@MainActivity).clearAllTables()
                        withContext(Dispatchers.Main) { localDeviceUsername = null }
                    }
                },
            ) { saved ->
                localDeviceUsername = saved.username
                creds = saved
                startBridgeService()
            }
        } else {
            val current = creds!!
            MainScreen(
                creds = current,
                smsRoleHeld = smsRoleHeld,
                smsPermissionsGranted = smsPermissionsGranted,
                notificationPermissionGranted = notificationPermissionGranted,
                conversationTarget = conversationTarget,
                onConversationTargetConsumed = { requestId ->
                    if (conversationTarget?.requestId == requestId) {
                        conversationTarget = null
                    }
                },
                update = UpdateFlow(
                    state = updateState,
                    message = updateMessage,
                    autoEnabled = autoUpdateEnabled,
                    shouldAutoCheck = UpdateValidation.shouldAutoCheck(
                        updater.pendingUpdate() != null,
                    ) && updater.shouldAutoCheck(),
                    onCheck = { manual -> checkForUpdates(manual) },
                    onToggleAuto = { enabled ->
                        autoUpdateEnabled = enabled
                        updater.setAutoCheckEnabled(enabled)
                    },
                    onUpdate = { startDownload(it) },
                    onInstall = { info, file -> startInstall(info, file) },
                    onRetry = { info -> if (info != null) startDownload(info) },
                    onCancelInstall = {
                        // The user gave up on a confirm that never appeared.
                        // FAILED (not cleared): the entry keeps its 재시도 path.
                        // A late confirm replay is rejected by the receiver's
                        // stale-replay guard; a late terminal status merely
                        // re-persists FAILED with more specific guidance.
                        updater.setPendingInstallFailure(
                            "설치 확인을 기다리지 않고 중단했습니다. 재시도하거나 닫을 수 있습니다.",
                        )
                        InstallResultReceiver.cancelConfirmNotification(this)
                        val pending = updater.pendingUpdate()
                        if (pending != null) {
                            pendingInstallFile = pending.file
                            updateState = pendingUiState(pending)
                        } else {
                            pendingInstallFile = null
                            updateState = UpdateUiState.Idle
                        }
                    },
                    onCloseInstallBlocked = {
                        updater.clearPendingUpdate()
                        // A confirm notification for a flow that no longer
                        // exists would linger in the shade indefinitely.
                        InstallResultReceiver.cancelConfirmNotification(this)
                        pendingInstallFile = null
                        updateState = UpdateUiState.Idle
                    },
                    onDismiss = { info ->
                        updater.dismiss(info.tag)
                        updater.clearPendingUpdate()
                        InstallResultReceiver.cancelConfirmNotification(this)
                        pendingInstallFile = null
                        updateState = UpdateUiState.Idle
                    },
                ),
                requestSmsRole = ::requestSmsRole,
                requestPerms = ::requestPerms,
                requestNotificationPermission = ::requestNotificationPermission,
                status = status,
                setStatus = { status = it },
                sendSms = { phone, text -> sendNewSms(current, phone, text) },
                onLogout = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            RelayApi(ServerConfig.url(this@MainActivity)).also {
                                it.token = current.token
                            }.logout()
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Remote logout failed; clearing local session", e)
                        } finally {
                            stopService(Intent(this@MainActivity, SmsBridgeService::class.java))
                            var localCleanupFailed = false
                            try {
                                Credentials.clearSession(this@MainActivity)
                                // Device keys survive logout for same-device re-login, but the
                                // decrypted history they can re-read must not stay on disk:
                                // clear plaintext tables (idempotency ledgers and pending
                                // outbox sends are kept; history re-pulls from the relay).
                                val db = AppDatabase.get(this@MainActivity)
                                db.messageDao().clearAll()
                                db.threadDao().clearAll()
                                db.blockedSmsDao().clearAll()
                                db.relayOutboxDao().clearSentPlaintext()
                            } catch (e: Exception) {
                                localCleanupFailed = true
                                Log.e("MainActivity", "Local credential cleanup failed", e)
                            } finally {
                                // Never leave decrypted messages or keys rendered merely because
                                // Keystore/DataStore cleanup failed. Server logout already revoked
                                // the token, and the next launch will retry credential validation.
                                withContext(Dispatchers.Main) {
                                    creds = null
                                    status = if (localCleanupFailed) {
                                        "로그아웃했습니다. 로컬 인증정보 정리에 실패해 앱 데이터 초기화가 필요합니다."
                                    } else {
                                        "로그아웃했습니다."
                                    }
                                }
                            }
                        }
                    }
                },
                onSimulateSms = ::simulateIncomingSms,
                onTestUpdateFlow = ::testUpdateFlow,
            )
        }
    }

    private fun startBridgeService() {
        // remoteMessaging foreground services are rejected by Android when the
        // app has not yet received the SMS role/runtime permissions. Login must
        // still work in that state so the user can grant them from MainScreen.
        if (!isDefaultSmsApp() || !hasSmsPerms()) return
        val svc = Intent(this, SmsBridgeService::class.java).apply {
            action = SmsBridgeService.ACTION_START_BRIDGE
        }
        try {
            startForegroundService(svc)
        } catch (e: RuntimeException) {
            Log.e("MainActivity", "Bridge service start rejected", e)
        }
    }

    private fun handleConversationIntent(intent: Intent?) {
        if (intent?.action != SmsNotifier.ACTION_OPEN_CONVERSATION) return
        val phone = PhoneNumberNormalizer.normalize(
            intent.getStringExtra(SmsNotifier.EXTRA_PHONE).orEmpty(),
        )
        val cid = intent.getStringExtra(SmsNotifier.EXTRA_CID)?.takeIf { it.isNotBlank() }
        val requestId = intent.getStringExtra(SmsNotifier.EXTRA_REQUEST_ID)
            ?.takeIf { it.isNotBlank() }
            ?: intent.data?.lastPathSegment.orEmpty()
        if (cid == null && phone.isBlank()) return
        // Opening the conversation is the read receipt for its notifications.
        // Without this they accumulate until the package hits the platform's
        // active-notification cap, past which notify() silently drops new ones.
        SmsNotifier.cancelConversation(this, cid, phone)
        conversationTarget = ConversationTarget(cid, phone, requestId)
        // Importing the provider and flushing an already-persisted outbox are safe
        // to repeat on a cold start and on every singleTask onNewIntent delivery.
        startBridgeService()
    }

    /** Debug-only: inject a fake incoming SMS through the real receive
     * pipeline (block check -> provider write -> notification -> bridge
     * relay), so SIM-less devices can verify phone->web interlock. */
    private fun simulateIncomingSms() {
        // Gate the function itself, not just its UI: this writes a fake row
        // into the system SMS Provider, so it must not survive into release
        // binaries even if a future change exposes another call path.
        if (!BuildConfig.DEBUG) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = this@MainActivity
                val sender = "+821000000001"
                val body = "SecureMsg 수신 시뮬레이션 " +
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
                        .format(java.util.Date())
                val db = AppDatabase.get(ctx)
                val decision = BlocklistManager.evaluate(sender, body, db)
                if (decision.blocked) {
                    db.blockedSmsDao().insert(
                        BlockedSms(
                            phoneNumber = sender, body = body,
                            reason = decision.reason, receivedAt = System.currentTimeMillis(),
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            ctx, "차단 규칙에 걸려 격리됨: ${decision.reason}",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }
                val receivedAt = System.currentTimeMillis()
                val providerId = SmsProvider.insertIncoming(ctx, sender, body, receivedAt)
                val content = RelayContentCodec.text(body)
                val persisted = IncomingMessageRepository(db).persistCarrier(
                    kind = ProviderIdentity.SMS,
                    direction = "incoming_sms",
                    phoneNumber = sender,
                    content = content,
                    providerId = providerId,
                    receivedAt = receivedAt,
                )
                if (persisted?.newlyCreated == true) {
                    SmsNotifier.notifyIncoming(
                        ctx,
                        persisted.conversation.normalizedPhone,
                        body,
                        receivedAt,
                        persisted.conversation.cid,
                        persisted.outbox.mid,
                    )
                }
                if (persisted != null) {
                    val intent = Intent(ctx, SmsBridgeService::class.java).apply {
                        action = SmsBridgeService.ACTION_INCOMING_SMS
                        putExtra(SmsBridgeService.EXTRA_PHONE, sender)
                        putExtra(SmsBridgeService.EXTRA_BODY, body)
                        putExtra(SmsBridgeService.EXTRA_PROVIDER_ID, providerId ?: -1L)
                        putExtra(SmsBridgeService.EXTRA_PROVIDER_EPOCH, persisted.outbox.providerEpoch)
                        putExtra(SmsBridgeService.EXTRA_RECEIVED_AT, receivedAt)
                    }
                    ContextCompat.startForegroundService(ctx, intent)
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx, "시뮬레이션 SMS 주입 완료 — 웹에서 확인하세요",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "SMS simulation failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity, "시뮬레이션 실패: ${e.message}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private suspend fun sendNewSms(creds: SavedCredentials, phone: String, text: String): Boolean {
        return try {
            val dispatched = OutgoingSmsDispatcher.queueAndSend(this, creds, phone, text)
            // Relay preparation is durable and may complete immediately or after a
            // later reconnect; carrier SMS itself does not depend on Oracle uptime.
            startBridgeService()
            dispatched
        } catch (e: LinkageError) {
            Log.e("MainActivity", "SMS crypto module unavailable", e)
            false
        } catch (e: Exception) {
            Log.e("MainActivity", "SMS send/sync failed", e)
            false
        }
    }
}
