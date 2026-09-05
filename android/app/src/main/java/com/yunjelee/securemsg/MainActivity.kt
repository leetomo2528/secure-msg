package com.yunjelee.securemsg

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.yunjelee.securemsg.ui.LoginScreen
import com.yunjelee.securemsg.ui.MainScreen
import com.yunjelee.securemsg.ui.Sm
import com.yunjelee.securemsg.ui.SmsLinkRequest
import com.yunjelee.securemsg.ui.SmsLinkRequests
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
    private var autoInstallEnabled by mutableStateOf(true)

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
        permsLauncher.launch(BridgeGate.SMS_PERMISSIONS.toTypedArray())
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
        // Started, not resumed: in split-screen the activity stays visible
        // while paused, and the updater's silent commit must not kill the app
        // out from under a pane the user is reading.
        SmsNotifier.setAppVisible(true)
    }

    override fun onStop() {
        SmsNotifier.setAppVisible(false)
        super.onStop()
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
        autoInstallEnabled = updater.autoInstallEnabled()
        // Returning through a notification/system surface must also wake durable
        // provider import and relay flushing; service-side work is idempotent.
        if (smsRoleHeld && smsPermissionsGranted) startBridgeService()
        // The user may have just toggled "install unknown apps" in system settings.
        val pending = updater.pendingUpdate()
        if (pending?.state == PendingInstallState.AWAITING_PERMISSION &&
            updater.canInstallPackages()
        ) {
            startInstall(pending.info, pending.file)
        }
        // A READY or FAILED entry the background tick produced while this
        // activity was already alive: onCreate's restore never re-runs on a warm
        // reopen, and the InstallEvents collector drops a status that lands while
        // the banner is Idle, so a silently blocked commit would wedge every
        // update check for a day with nothing on screen explaining why. Surface
        // it here — but never over a live flow's state.
        if (pending != null &&
            (pending.state == PendingInstallState.READY ||
                pending.state == PendingInstallState.FAILED) &&
            (updateState is UpdateUiState.Idle || updateState is UpdateUiState.Checking ||
                updateState is UpdateUiState.Available || updateState is UpdateUiState.Failed)
        ) {
            updateState = pendingUiState(pending)
        }
        // Returning from the legacy installer (or a confirm dialog) via recents
        // never recreates this singleTask activity, so onCreate's reconciliation
        // does not run again. Success is the only verdict allowed here: marking
        // a failure while the installer might still be up on screen would race
        // its own callback.
        if (pending != null &&
            (pending.state == PendingInstallState.SESSION_SUBMITTED ||
                pending.state == PendingInstallState.FALLBACK_LAUNCHED) &&
            consumeIfInstalled(pending)
        ) {
            updateState = UpdateUiState.Idle
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
            updateState = UpdateUiState.SessionSubmitted(submitted.info, submitted.file)
            return
        }
        if (updateState is UpdateUiState.Installing) return

        try {
            updater.verifyApkSigningCertificate(file)
        } catch (e: Exception) {
            file.delete()
            updater.clearPendingUpdate()
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
        updateState = UpdateUiState.Ready(info, file)
        if (updater.canInstallPackages()) {
            updater.setPendingInstallState(PendingInstallState.SESSION_SUBMITTED)
            updateState = UpdateUiState.Installing(info, file)
            lifecycleScope.launch {
                // userActionNotRequired even for the manual flow: when this
                // build is eligible (installer of record) the tap installs
                // without the system dialog; when not, the system delivers
                // PENDING_USER_ACTION and the confirm flow runs unchanged.
                // installViaSession sets installSubmittedInThisProcess before
                // suspending, so restore cannot misread this as orphaned.
                val submitted = updater.installViaSession(
                    file,
                    callbackToken,
                    userActionNotRequired = true,
                )
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
                            updateState = pendingUiState(pending)
                            updateMessage = null
                        }
                        // SUCCESS cleared the entry (rare: replacement usually
                        // kills the process first). Guarded so a replayed old
                        // emission cannot reset an unrelated Available/
                        // Downloading banner to Idle.
                        pending == null && isInstallFlowState(updateState) -> {
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
        val handedOff = pending.state == PendingInstallState.SESSION_SUBMITTED ||
            pending.state == PendingInstallState.FALLBACK_LAUNCHED
        if (handedOff && consumeIfInstalled(pending)) {
            updateState = UpdateUiState.Idle
            return
        }
        if (pending.state == PendingInstallState.SESSION_SUBMITTED &&
            !AppUpdater.installSubmittedInThisProcess
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
            updateState = UpdateUiState.InstallBlocked(pending.info, pending.file, detail)
            return
        }
        // A live SESSION_SUBMITTED lands here after a recreation and re-arms
        // the confirm watchdog through the SessionSubmitted state it restores.
        updateState = pendingUiState(pending)
    }

    /**
     * Retires [pending] and its APK when the running build already satisfies the
     * version it was downloaded for. Which timestamps decide that is a rule a
     * future change will move, so cold-start restore and the recents-return path
     * in onResume share one copy rather than each carrying their own.
     */
    private fun consumeIfInstalled(pending: PendingUpdate): Boolean {
        val packageUpdatedAt = try {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
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
            return false
        }
        pending.file.delete()
        updater.clearPendingUpdate()
        InstallResultReceiver.cancelConfirmNotification(this)
        return true
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

    private fun hasSmsPerms(): Boolean = BridgeGate.hasSmsPermissions(this)

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
                        // Before anything is cleared, exactly as logout does: a
                        // rebuild is process-scoped and this button is not gated
                        // on it, so a run still walking conversations would
                        // insert the previous account's plaintext history back
                        // behind the wipe. Joining is what makes the wipe last.
                        HistoryRestoreRunner.cancelAndAwait()
                        Credentials.clear(this@MainActivity)
                        Credentials.wipeAccountData(this@MainActivity)
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
                    autoInstallEnabled = autoInstallEnabled,
                    shouldAutoCheck = UpdateValidation.shouldAutoCheck(
                        updater.pendingUpdate() != null,
                    ) && updater.shouldAutoCheck(),
                    onCheck = { manual -> checkForUpdates(manual) },
                    onToggleAuto = { enabled ->
                        autoUpdateEnabled = enabled
                        updater.setAutoCheckEnabled(enabled)
                    },
                    onToggleAutoInstall = { enabled ->
                        autoInstallEnabled = enabled
                        updater.setAutoInstallEnabled(enabled)
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
                            updateState = pendingUiState(pending)
                        } else {
                            updateState = UpdateUiState.Idle
                        }
                    },
                    onCloseInstallBlocked = {
                        // Discard the ~55MB APK with the entry that points at
                        // it: nothing else will, short of cleanupDownloads'
                        // 24h sweep on some later cold start.
                        updater.pendingUpdate()?.file?.delete()
                        updater.clearPendingUpdate()
                        // A confirm notification for a flow that no longer
                        // exists would linger in the shade indefinitely.
                        InstallResultReceiver.cancelConfirmNotification(this)
                        updateState = UpdateUiState.Idle
                    },
                    onDismiss = { info ->
                        updater.dismiss(info.tag)
                        // Dismissal deliberately leaves the unattended tick
                        // running, so without this each window re-downloads the
                        // same release under a new name and the orphans stack up.
                        updater.pendingUpdate()?.file?.delete()
                        updater.clearPendingUpdate()
                        InstallResultReceiver.cancelConfirmNotification(this)
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
                                // Before anything is cleared: a rebuild is
                                // process-scoped and this button is not gated on
                                // it, so a run still walking conversations would
                                // insert plaintext history back behind the wipe.
                                // Joining it is what makes the wipe the last write.
                                HistoryRestoreRunner.cancelAndAwait()
                                Credentials.clearSession(this@MainActivity)
                                // Device keys survive logout for same-device re-login, but the
                                // decrypted history they can re-read must not stay on disk:
                                // clear plaintext tables (idempotency ledgers and pending
                                // outbox sends are kept).
                                //
                                // This is a one-way local wipe. The relay cannot put it back —
                                // an envelope does not record direction, so a re-pull is
                                // consumed as history and never rendered — and the startup
                                // inbox import is inbox-only, capped, and skipped for every row
                                // the retained ledger already names. HistoryRestore rebuilds
                                // conversations from the phone's own telephony store instead,
                                // which is what the logout confirmation tells the user.
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
        // still work in that state so the user can grant them from MainScreen,
        // so [BridgeGate.start] returning without doing anything is normal here.
        BridgeGate.start(this) { e ->
            Log.e("MainActivity", "Bridge service start rejected", e)
        }
    }

    private fun handleConversationIntent(intent: Intent?) {
        if (intent == null) return
        if (handleSmsLinkIntent(intent)) return
        if (intent.action != SmsNotifier.ACTION_OPEN_CONVERSATION) return
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

    /**
     * The manifest's SENDTO filter is what lets this app hold the default-SMS
     * role, and its target used to be dropped on the floor: tapping a
     * phone-number link, "메시지" on a contact, or any share-to-SMS launched
     * SecureMsg — or, being singleTask, brought it forward on whichever screen
     * it was last left on — with no composer and no recipient.
     *
     * Returns true when [intent] was one of those links, so the notification
     * branch above is left to notifications alone.
     */
    private fun handleSmsLinkIntent(intent: Intent): Boolean {
        val link = SmsLink.parse(intent) ?: return false
        val requestId = UUID.randomUUID().toString()
        SmsLinkRequests.post(SmsLinkRequest(link.phone, link.body, requestId))
        // The pane can only act on the request while it is composed, and a link
        // tapped with 연락처/설정 in front would otherwise sit there until the
        // user found the 메시지 tab. This moves the shell to that tab and
        // nothing more: with no cid and no number, ConversationTargetResolver
        // never matches it, and the pane consumes it with the request.
        conversationTarget = ConversationTarget(null, "", requestId)
        // The composer's send needs the bridge for exactly the reason the
        // notification path does, and both are safe to repeat.
        startBridgeService()
        return true
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


/**
 * The `sms:` / `smsto:` link the system hands this activity through the
 * manifest's SENDTO filter.
 *
 * Those URIs are opaque, so `Uri.getQueryParameter` returns null on them and
 * the `?body=` half has to be cut off by hand. The cut is made on the ENCODED
 * scheme-specific part, because a `%3F` inside the body is not a separator and
 * decoding first would turn it into one. Decoding is `Uri.decode` and never
 * URLDecoder, which reads `+` as a space and would eat the country code off
 * `smsto:+8210…`.
 */
private object SmsLink {
    private val SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

    /** The ceiling the 메시지 draft fields already enforce on typing. */
    private const val BODY_MAX = 20_000

    fun parse(intent: Intent): SmsLinkTarget? {
        if (intent.action != Intent.ACTION_SENDTO && intent.action != Intent.ACTION_VIEW) {
            return null
        }
        val data = intent.data ?: return null
        val scheme = data.scheme?.lowercase() ?: return null
        if (scheme !in SCHEMES) return null
        val ssp = data.encodedSchemeSpecificPart.orEmpty()
        val phone = ssp.substringBefore('?')
            // A link may address several people; this app composes to one, and
            // taking the first is the only reading that cannot quietly send to
            // a number the user never saw on screen.
            .split(',', ';')
            .map { Uri.decode(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { PhoneNumberNormalizer.normalize(it) }
            // The same address gate the dispatcher and the blocklist run on.
            // Anything it rejects opens an empty composer instead of prefilling
            // a recipient no send could ever accept.
            ?.takeIf { PhoneNumberNormalizer.isSmsAddress(it) }
        val query = ssp.substringAfter('?', "")
        val body = queryValue(query, "body")
            ?: queryValue(query, "sms_body")
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra("sms_body")
        // The body is whatever app built the link, so it is trimmed to what the
        // composer would have accepted from the keyboard rather than handed
        // straight to a draft field that caps its own input.
        return SmsLinkTarget(phone, body?.take(BODY_MAX)?.takeIf { it.isNotBlank() })
    }

    private fun queryValue(query: String, key: String): String? = query
        .split('&')
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "")
        ?.let { Uri.decode(it) }
}

/** One parsed link. [phone] is null when the URI named no usable recipient. */
private data class SmsLinkTarget(val phone: String?, val body: String?)
