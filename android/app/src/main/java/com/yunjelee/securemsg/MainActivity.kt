package com.yunjelee.securemsg

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yunjelee.securemsg.ui.Caption
import com.yunjelee.securemsg.ui.ChatBubble
import com.yunjelee.securemsg.ui.SectionTitle
import com.yunjelee.securemsg.ui.Sm
import com.yunjelee.securemsg.ui.SmAvatar
import com.yunjelee.securemsg.ui.SmCard
import com.yunjelee.securemsg.ui.SmChip
import com.yunjelee.securemsg.ui.SmGhostButton
import com.yunjelee.securemsg.ui.SmGradientButton
import com.yunjelee.securemsg.ui.SmTabs
import com.yunjelee.securemsg.ui.SmTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val pct: Int) : UpdateUiState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateUiState
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateUiState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateUiState
}

class MainActivity : ComponentActivity() {

    private var smsRoleHeld by mutableStateOf(false)
    private var smsPermissionsGranted by mutableStateOf(false)

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
        if (isDefaultSmsApp() && smsPermissionsGranted) startBridgeService()
    }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        smsRoleHeld = isDefaultSmsApp()
        smsPermissionsGranted = hasSmsPerms()
        if (smsRoleHeld && !smsPermissionsGranted) {
            requestPerms()
        } else if (smsRoleHeld) {
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
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permsLauncher.launch(perms.toTypedArray())
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

    override fun onResume() {
        super.onResume()
        smsRoleHeld = isDefaultSmsApp()
        smsPermissionsGranted = hasSmsPerms()
        autoUpdateEnabled = updater.autoCheckEnabled()
        // The user may have just toggled "install unknown apps" in system settings.
        val pending = pendingInstallFile
        if (pending != null && updater.canInstallPackages()) {
            pendingInstallFile = null
            startActivity(updater.installIntent(pending))
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        val state = updateState
        if (state is UpdateUiState.Checking || state is UpdateUiState.Downloading) return
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
        updateState = UpdateUiState.Ready(info, file)
        if (updater.canInstallPackages()) {
            startActivity(updater.installIntent(file))
        } else {
            pendingInstallFile = file
            updateState = UpdateUiState.NeedsPermission(info, file)
            updateMessage = "'이 앱의 설치 허용'을 켜면 자동으로 설치가 이어집니다."
            try {
                startActivity(updater.unknownSourcesSettingsIntent())
            } catch (_: Exception) {
                updateMessage = "설정 → 앱 → SecureMsg → '알 수 없는 앱 설치'를 허용해 주세요."
            }
        }
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
        super.onCreate(savedInstanceState)

        smsRoleHeld = isDefaultSmsApp()
        smsPermissionsGranted = hasSmsPerms()

        lifecycleScope.launch(Dispatchers.IO) { updater.cleanupDownloads() }

        setContent { App() }
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
            MainScreen(
                creds = creds!!,
                smsRoleHeld = smsRoleHeld,
                smsPermissionsGranted = smsPermissionsGranted,
                requestSmsRole = ::requestSmsRole,
            ) { creds = null }
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

    private fun serverUrl(): String =
        getSharedPreferences("securemsg_config", MODE_PRIVATE)
            .getString("server_url", "https://msg.yunjelee.com") ?: "https://msg.yunjelee.com"

    /** Push local block rules to the relay and refresh the shared cache (IO). */
    private suspend fun pushBlockRulesToServer() {
        try {
            val saved = Credentials.load(this@MainActivity) ?: return
            val api = RelayApi(serverUrl()).also { it.token = saved.token }
            BlocklistSync.sync(this@MainActivity, api)
        } catch (e: Exception) {
            Log.w("MainActivity", "block rule push failed", e)
        }
    }

    /** Remove a rule server-side after local deletion (IO). */
    private suspend fun removeBlockRuleOnServer(type: String, value: String) {
        try {
            val saved = Credentials.load(this@MainActivity) ?: return
            val api = RelayApi(serverUrl()).also { it.token = saved.token }
            BlocklistSync.removeShared(this@MainActivity, api, type, value)
        } catch (e: Exception) {
            Log.w("MainActivity", "block rule remove failed", e)
        }
    }

    /** Debug-only: inject a fake incoming SMS through the real receive
     * pipeline (block check -> provider write -> notification -> bridge
     * relay), so SIM-less devices can verify phone->web interlock. */
    private fun simulateIncomingSms() {
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
                SmsNotifier.notifyIncoming(ctx, sender, body, receivedAt)
                val intent = Intent(ctx, SmsBridgeService::class.java).apply {
                    action = SmsBridgeService.ACTION_INCOMING_SMS
                    putExtra(SmsBridgeService.EXTRA_PHONE, sender)
                    putExtra(SmsBridgeService.EXTRA_BODY, body)
                    putExtra(SmsBridgeService.EXTRA_PROVIDER_ID, providerId ?: -1L)
                    putExtra(SmsBridgeService.EXTRA_RECEIVED_AT, receivedAt)
                }
                androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
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

    @Composable
    private fun LoginScreen(
        rememberedUsername: String?,
        onForgetLocalDevice: () -> Unit,
        onLogin: (SavedCredentials) -> Unit,
    ) {
        var username by remember(rememberedUsername) { mutableStateOf(rememberedUsername.orEmpty()) }
        var password by remember { mutableStateOf("") }
        var serverUrl by remember {
            mutableStateOf(
                getSharedPreferences("securemsg_config", MODE_PRIVATE)
                    .getString("server_url", "https://msg.yunjelee.com") ?: "https://msg.yunjelee.com"
            )
        }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var confirmForget by remember { mutableStateOf(false) }

        if (confirmForget) {
            AlertDialog(
                onDismissRequest = { confirmForget = false },
                containerColor = Sm.surface,
                shape = RoundedCornerShape(16.dp),
                titleContentColor = Sm.text1,
                textContentColor = Sm.text3,
                title = { Text("로컬 기기 초기화") },
                text = { Text("이 휴대폰의 개인키와 로컬 메시지를 삭제합니다. 서버에 남은 기기 등록은 다른 기기에서 폐기해야 합니다.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmForget = false
                        username = ""
                        password = ""
                        onForgetLocalDevice()
                    }) { Text("삭제", color = Sm.danger) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForget = false }) { Text("취소", color = Sm.text3) }
                },
            )
        }

        Box(Modifier.fillMaxSize().background(Sm.bg)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(32.dp))
                Text(
                    "SecureMsg",
                    style = TextStyle(
                        brush = Sm.gradient,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                    ),
                )
                Text(
                    "자가호스팅 E2E SMS 동기화. 전화번호·이메일 없이 임의 아이디만 사용합니다.",
                    color = Sm.text3, fontSize = 13.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(8.dp))
                SmCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmTextField(
                        value = serverUrl, onValueChange = { serverUrl = it.take(2048) },
                        label = "서버 URL", singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SmTextField(
                        value = username,
                        onValueChange = { username = it.take(20).lowercase(Locale.ROOT) },
                        label = "아이디 (3-20자, a-z0-9_)", singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SmTextField(
                        value = password, onValueChange = { password = it.take(1024) },
                        label = "비밀번호 (8자 이상, 자유롭게 조합)", singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(
                            it, color = Sm.danger, fontSize = 12.sp, lineHeight = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Sm.danger.copy(alpha = 0.08f))
                                .padding(10.dp),
                        )
                    }
                    SmGradientButton(
                        text = if (busy) "처리 중…" else "로그인 / 회원가입",
                        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                    if (busy) return@SmGradientButton
                    busy = true; error = null
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val saved = doLogin(serverUrl.trim(), username.trim(), password)
                            withContext(Dispatchers.Main) {
                                getSharedPreferences("securemsg_config", MODE_PRIVATE)
                                    .edit().putString("server_url", serverUrl.trim()).apply()
                                onLogin(saved)
                            }
                        } catch (e: LinkageError) {
                            Log.e("MainActivity", "Crypto native library initialization failed", e)
                            withContext(Dispatchers.Main) {
                                error = "암호화 모듈을 불러오지 못했습니다. 앱을 최신 버전으로 다시 설치해 주세요."
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { error = e.message ?: "로그인에 실패했습니다." }
                        } finally {
                            withContext(Dispatchers.Main) { busy = false }
                        }
                    }
                        },
                    )
                    if (rememberedUsername != null) {
                        Text(
                            "이 휴대폰의 로컬 기기 초기화",
                            color = Sm.danger.copy(alpha = 0.9f), fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { confirmForget = true }
                                .padding(6.dp),
                        )
                    }
                }
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    color = Sm.text4, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    private suspend fun doLogin(serverUrl: String, username: String, password: String): SavedCredentials {
        if (!Regex("^[a-z0-9_]{3,20}$").matches(username)) {
            throw IllegalArgumentException("아이디는 영소문자·숫자·_ 3~20자로 입력하세요.")
        }
        if (password.isEmpty()) throw IllegalArgumentException("비밀번호를 입력하세요.")
        if (password.length > 1024) throw IllegalArgumentException("비밀번호가 너무 깁니다.")
        val parsedUrl = serverUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("올바른 서버 URL을 입력하세요.")
        // localhost/127.0.0.1: adb reverse test setups; 10.0.2.2: emulator host alias.
        if (parsedUrl.scheme != "https" && parsedUrl.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")) {
            throw IllegalArgumentException("원격 서버는 HTTPS 주소를 사용해야 합니다.")
        }
        if (parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty() ||
            parsedUrl.encodedPath != "/" || parsedUrl.query != null || parsedUrl.fragment != null
        ) {
            throw IllegalArgumentException("서버 URL은 도메인과 포트까지만 입력하세요.")
        }
        val api = RelayApi(parsedUrl.toString().trimEnd('/'))
        val salt = CryptoUtil.saltForUser(username)
        val pwHash = CryptoUtil.hashPassword(password, salt)

        // Try device-login first (if we have a stored sid). Otherwise register + device-register.
        val stored = Credentials.loadDevice(this)
        if (stored != null && stored.username != username) {
            throw IllegalStateException(
                "이 휴대폰에는 ${stored.username} 기기 키가 남아 있습니다. 먼저 로컬 기기를 초기화하세요.",
            )
        }
        if (stored != null && stored.username == username) {
            val r = api.deviceLogin(username, pwHash, stored.sid)
            if (r.optBoolean("ok")) {
                api.token = r.getString("token")
                val saved = stored.copy(token = r.getString("token"))
                Credentials.save(this, saved)
                return saved
            }
        }

        // Try login to see if user exists.
        val loginResp = api.login(username, pwHash)
        val kp = CryptoUtil.generateKeypair()
        val deviceName = "android-${Build.MODEL.take(10)}"

        if (loginResp.optBoolean("ok")) {
            // User exists → register new device.
            val dr = api.deviceRegister(username, pwHash, deviceName, kp.boxPk, kp.signPk)
            if (!dr.optBoolean("ok")) throw Exception(dr.optString("error", "device register failed"))
            api.token = dr.getString("token")
            val saved = SavedCredentials(
                username = username, uid = dr.getInt("uid"),
                sid = dr.getString("sid"), token = dr.getString("token"),
                deviceName = deviceName, keypair = kp,
            )
            Credentials.save(this, saved)
            return saved
        }

        // User doesn't exist → register.
        if (password.length < 8) {
            throw IllegalArgumentException("새 계정 비밀번호는 8자 이상이면 됩니다. 영문·숫자·특수문자는 자유롭게 조합할 수 있습니다.")
        }
        val reg = api.register(username, pwHash)
        if (!reg.optBoolean("ok")) {
            val message = if (reg.optString("error") == "username already taken") {
                "비밀번호가 틀렸거나 이미 가입된 아이디입니다."
            } else {
                reg.optString("error", "register failed")
            }
            throw Exception(message)
        }
        val dr = api.deviceRegister(username, pwHash, deviceName, kp.boxPk, kp.signPk)
        if (!dr.optBoolean("ok")) throw Exception(dr.optString("error", "device register failed"))
        api.token = dr.getString("token")
        val saved = SavedCredentials(
            username = username, uid = dr.getInt("uid"),
            sid = dr.getString("sid"), token = dr.getString("token"),
            deviceName = deviceName, keypair = kp,
        )
        Credentials.save(this, saved)
        return saved
    }

    @Composable
    private fun MainScreen(
        creds: SavedCredentials,
        smsRoleHeld: Boolean,
        smsPermissionsGranted: Boolean,
        requestSmsRole: () -> Unit,
        onLogout: () -> Unit,
    ) {
        val db = AppDatabase.get(this)
        val blocklist by db.blocklistDao().observeAll().collectAsState(initial = emptyList())
        val blockedSms by db.blockedSmsDao().observeAll().collectAsState(initial = emptyList())
        val blockedSenders by db.blockedSenderDao().observeAll().collectAsState(initial = emptyList())
        val threads by db.threadDao().observeAll().collectAsState(initial = emptyList())
        var selectedThread by remember { mutableStateOf<SmsThread?>(null) }
        val selectedMessageFlow = remember(selectedThread?.cid) {
            selectedThread?.let { db.messageDao().observeForCid(it.cid) }
                ?: kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val selectedMessages by selectedMessageFlow.collectAsState(initial = emptyList())
        var newKw by remember { mutableStateOf("") }
        var newBlockedPhone by remember { mutableStateOf("") }
        var reply by remember { mutableStateOf("") }
        var newPhone by remember { mutableStateOf("") }
        var newMsg by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("연결 확인 중…") }
        var sending by remember { mutableStateOf(false) }
        var selectedSection by remember { mutableIntStateOf(0) }

        LaunchedEffect(smsRoleHeld, smsPermissionsGranted, creds.sid) {
            if (!smsRoleHeld) {
                status = "기본 SMS 앱 설정 필요"
            } else if (!smsPermissionsGranted) {
                status = "SMS 권한 필요 — 설정에서 승인하세요"
            } else {
                status = "브리지 사용 준비됨 (${creds.username}@${creds.sid})"
            }
        }

        // Offline sends start in a provisional local thread. Once the relay
        // reconnects it atomically swaps that cid for the server cid; keep the
        // open conversation selected across that migration.
        LaunchedEffect(threads, selectedThread?.cid) {
            val current = selectedThread ?: return@LaunchedEffect
            selectedThread = threads.firstOrNull { it.cid == current.cid }
                ?: threads.firstOrNull { it.phoneNumber == current.phoneNumber }
        }

        // Game-style self-update: look for a new release once per 12h window.
        LaunchedEffect(Unit) {
            if (updater.shouldAutoCheck()) checkForUpdates(manual = false)
        }

        Column(
            Modifier.fillMaxSize().background(Sm.bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SecureMsg",
                    style = TextStyle(
                        brush = Sm.gradient,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp,
                    ),
                )
                SmChip("v${BuildConfig.VERSION_NAME}", Sm.text4)
            }
            SmChip(
                status,
                when {
                    status.startsWith("브리지 사용 준비됨") -> Sm.teal
                    status == "연결 확인 중…" -> Sm.sky
                    else -> Sm.warning
                },
            )
            UpdateBanner(
                state = updateState,
                onUpdate = { startDownload(it) },
                onInstall = { info, file -> startInstall(info, file) },
                onRetry = { info -> if (info != null) startDownload(info) },
                onDismiss = { info ->
                    updater.dismiss(info.tag)
                    updateState = UpdateUiState.Idle
                },
            )
            if (!smsRoleHeld) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Sm.warning.copy(alpha = 0.07f))
                        .border(1.dp, Sm.warning.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "자동 차단과 SMS 발신을 사용하려면 SecureMsg를 기본 SMS 앱으로 설정하세요.",
                        color = Sm.warning, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    SmGradientButton(
                        text = "기본 SMS 앱으로 설정",
                        onClick = requestSmsRole,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (smsRoleHeld && !smsPermissionsGranted) {
                SmGradientButton(
                    text = "SMS 권한 요청",
                    onClick = { requestPerms() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SmTabs(
                selected = selectedSection,
                labels = listOf("메시지", "차단·설정"),
                onSelect = { selectedSection = it },
            )

            if (selectedSection == 0) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val thread = selectedThread
                    if (thread == null) {
                        SmCard {
                            SectionTitle("새 SMS 발신")
                            SmTextField(
                                value = newPhone,
                                onValueChange = { newPhone = it.take(32) },
                                label = "전화번호",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SmTextField(
                                value = newMsg,
                                onValueChange = { newMsg = it.take(20_000) },
                                label = "메시지",
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 1,
                                maxLines = 3,
                            )
                            SmGradientButton(
                                text = if (sending) "발송 중…" else "발송 + 동기화",
                                enabled = !sending && smsRoleHeld && smsPermissionsGranted &&
                                    newPhone.isNotBlank() && newMsg.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                if (sending || newPhone.isBlank() || newMsg.isBlank()) return@SmGradientButton
                                sending = true
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val sent = sendNewSms(creds, newPhone.trim(), newMsg.trim())
                                    withContext(Dispatchers.Main) {
                                        if (sent) {
                                            newMsg = ""
                                            status = "SMS를 발송했고 동기화 대기열에 저장했습니다."
                                        } else {
                                            status = "SMS 발송 실패 — 번호·권한·메시지 길이를 확인하세요."
                                        }
                                        sending = false
                                    }
                                }
                                },
                            )
                        }
                        Text(
                            "SMS 스레드 (${threads.size})",
                            color = Sm.text2,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (threads.isEmpty()) {
                            Text(
                                "아직 표시할 문자가 없습니다.",
                                color = Sm.text4,
                                fontSize = 12.sp,
                            )
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            items(threads, key = { it.cid }) { item ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedThread = item }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SmAvatar(item.contactName ?: item.phoneNumber)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.contactName ?: item.phoneNumber,
                                            color = Sm.text1, fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        if (item.contactName != null) {
                                            Text(item.phoneNumber, color = Sm.text4, fontSize = 11.sp)
                                        }
                                    }
                                    Text("›", color = Sm.text4, fontSize = 18.sp)
                                }
                                HorizontalDivider(color = Sm.borderSoft)
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SmAvatar(thread.contactName ?: thread.phoneNumber, size = 34)
                                Column {
                                    Text(
                                        thread.contactName ?: thread.phoneNumber,
                                        color = Sm.text1, fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (thread.contactName != null) {
                                        Text(thread.phoneNumber, color = Sm.text4, fontSize = 11.sp)
                                    }
                                }
                            }
                            SmGhostButton(
                                text = "목록",
                                onClick = { selectedThread = null; reply = "" },
                                modifier = Modifier.padding(vertical = 0.dp),
                            )
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            items(selectedMessages, key = { it.id }) { message ->
                                ChatBubble(
                                    mine = message.mine,
                                    blocked = message.blocked,
                                    text = if (message.blocked) {
                                        "차단된 메시지"
                                    } else {
                                        message.plaintext + carrierStatusLabel(message.carrierStatus)
                                    },
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().imePadding(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SmTextField(
                                value = reply,
                                onValueChange = { reply = it.take(20_000) },
                                label = "답장",
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (reply.isNotBlank() && !sending &&
                                            smsRoleHeld && smsPermissionsGranted
                                        ) Sm.gradient
                                        else Brush.linearGradient(listOf(Sm.border, Sm.border)),
                                    )
                                    .clickable(
                                        enabled = reply.isNotBlank() && !sending &&
                                            smsRoleHeld && smsPermissionsGranted,
                                    ) {
                                    val text = reply.trim()
                                    if (text.isNotBlank() && !sending) {
                                        sending = true
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            val sent = sendNewSms(creds, thread.phoneNumber, text)
                                            withContext(Dispatchers.Main) {
                                                if (sent) reply = "" else {
                                                    status = "SMS 발송 실패 — 번호·권한·메시지 길이를 확인하세요."
                                                }
                                                sending = false
                                            }
                                        }
                                    }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (sending) "…" else "↑",
                                    color = if (reply.isNotBlank() && !sending &&
                                        smsRoleHeld && smsPermissionsGranted
                                    ) Color(0xFF052530) else Sm.text4,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SmCard {
                        SectionTitle("차단 키워드")
                        Caption("키워드·발신번호는 모든 기기에 동기화됩니다. 문자 내용은 이 기기에서 복호화한 뒤 검사하며 서버에는 평문으로 보내지 않습니다.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmTextField(
                                value = newKw,
                                onValueChange = { newKw = it.take(120) },
                                label = "키워드",
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            SmGradientButton(
                                text = "추가",
                                onClick = {
                            val keyword = newKw.trim()
                            if (keyword.isNotEmpty()) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.blocklistDao().insert(BlockKeyword(keyword = keyword))
                                    pushBlockRulesToServer()
                                    withContext(Dispatchers.Main) { newKw = "" }
                                }
                            }
                                },
                            )
                        }
                        if (blocklist.isEmpty()) {
                            Caption("추가된 키워드가 없습니다.")
                        }
                        blocklist.forEach { keyword ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Sm.surfaceAlt)
                                    .padding(start = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(keyword.keyword, color = Sm.text2, fontSize = 13.sp)
                                TextButton(onClick = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        db.blocklistDao().delete(keyword)
                                        removeBlockRuleOnServer("keyword", keyword.keyword)
                                    }
                                }) { Text("삭제", color = Sm.danger, fontSize = 12.sp) }
                            }
                        }
                    }

                    SmCard {
                        SectionTitle("발신번호 차단")
                        Caption("번호 차단은 이 Android 기기에서 수신 단계에 적용됩니다.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmTextField(
                                value = newBlockedPhone,
                                onValueChange = { newBlockedPhone = it.take(32) },
                                label = "전화번호",
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            SmGradientButton(
                                text = "차단",
                                onClick = {
                            val number = PhoneNumberNormalizer.normalize(newBlockedPhone)
                            if (Regex("^\\+?[0-9*#]{3,24}$").matches(number)) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.blockedSenderDao().insert(BlockedSender(number))
                                    pushBlockRulesToServer()
                                    withContext(Dispatchers.Main) { newBlockedPhone = "" }
                                }
                            } else {
                                status = "차단할 전화번호 형식을 확인하세요."
                            }
                                },
                            )
                        }
                        if (blockedSenders.isEmpty()) {
                            Caption("차단된 번호가 없습니다.")
                        }
                        blockedSenders.forEach { sender ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Sm.surfaceAlt)
                                    .padding(start = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(sender.phoneNumber, color = Sm.text2, fontSize = 13.sp)
                                TextButton(onClick = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        db.blockedSenderDao().delete(sender)
                                        removeBlockRuleOnServer("sender", sender.phoneNumber)
                                    }
                                }) { Text("삭제", color = Sm.danger, fontSize = 12.sp) }
                            }
                        }
                    }

                    SmCard {
                        SectionTitle("앱 업데이트")
                        Caption("현재 버전 v${BuildConfig.VERSION_NAME} · 새 버전을 자동으로 내려받아 게임처럼 바로 설치합니다.")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "자동 업데이트 확인 (12시간마다)",
                                color = Sm.text2,
                                fontSize = 13.sp,
                            )
                            Switch(
                                checked = autoUpdateEnabled,
                                onCheckedChange = {
                                    autoUpdateEnabled = it
                                    updater.setAutoCheckEnabled(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Sm.accentDeep,
                                    uncheckedThumbColor = Sm.text4,
                                    uncheckedTrackColor = Sm.surfaceAlt,
                                ),
                            )
                        }
                        SmGhostButton(
                            text = when (updateState) {
                                is UpdateUiState.Checking -> "확인 중…"
                                is UpdateUiState.Downloading -> "다운로드 중…"
                                else -> "업데이트 확인"
                            },
                            onClick = { checkForUpdates(manual = true) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        updateMessage?.let {
                            Text(it, color = Sm.text3, fontSize = 12.sp)
                        }
                    }

                    SmCard {
                        SectionTitle("격리된 스팸 (${blockedSms.size})")
                        if (blockedSms.isEmpty()) {
                            Caption("격리된 문자가 없습니다.")
                        }
                        blockedSms.take(20).forEach { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Sm.surfaceAlt)
                                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${item.phoneNumber}: ${item.reason}\n${item.body.take(120)}",
                                    color = Sm.text3,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    maxLines = 3,
                                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                                )
                                TextButton(onClick = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        db.blockedSmsDao().delete(item)
                                    }
                                }) { Text("삭제", color = Sm.danger, fontSize = 12.sp) }
                            }
                        }
                    }

                    if (BuildConfig.DEBUG) {
                        SmCard {
                            SectionTitle("개발자 도구")
                            Caption("SIM 없이 수신 경로를 검증합니다. 차단 판정 → 시스템 SMS 기록 → 알림 → 서버 relay까지 실제 코드 경로로 주입합니다.")
                            SmGhostButton(
                                text = "SMS 수신 시뮬레이션 (+821000000001)",
                                onClick = { simulateIncomingSms() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SmGhostButton(
                                text = "업데이트 흐름 테스트 (최신 릴리스 강제 다운로드→설치)",
                                onClick = { testUpdateFlow() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    SmGhostButton(
                        text = "로그아웃",
                        textColor = Sm.danger,
                        onClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                stopService(Intent(this@MainActivity, SmsBridgeService::class.java))
                                Credentials.clearSession(this@MainActivity)
                                withContext(Dispatchers.Main) { onLogout() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    @Composable
    private fun UpdateBanner(
        state: UpdateUiState,
        onUpdate: (UpdateInfo) -> Unit,
        onInstall: (UpdateInfo, File) -> Unit,
        onRetry: (UpdateInfo?) -> Unit,
        onDismiss: (UpdateInfo) -> Unit,
    ) {
        when (state) {
            is UpdateUiState.Available -> Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Sm.gradientSoft)
                    .border(1.dp, Sm.teal.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "새 버전 v${state.info.versionName} 출시 (현재 v${BuildConfig.VERSION_NAME})",
                    color = Sm.text1,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                SmGradientButton(text = "업데이트", onClick = { onUpdate(state.info) })
                TextButton(onClick = { onDismiss(state.info) }) {
                    Text("나중에", color = Sm.text3, fontSize = 12.sp)
                }
            }
            is UpdateUiState.Downloading -> Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Sm.surface)
                    .border(1.dp, Sm.border, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("업데이트 다운로드 중… ${state.pct}%", color = Sm.cyan, fontSize = 12.sp)
                LinearProgressIndicator(
                    progress = { state.pct / 100f },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = Sm.teal,
                    trackColor = Sm.surfaceAlt,
                )
            }
            is UpdateUiState.Ready -> SmGradientButton(
                text = "v${state.info.versionName} 지금 설치",
                onClick = { onInstall(state.info, state.file) },
                modifier = Modifier.fillMaxWidth(),
            )
            is UpdateUiState.NeedsPermission -> Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Sm.warning.copy(alpha = 0.07f))
                    .border(1.dp, Sm.warning.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Text(
                    "설치 권한이 필요합니다. 방금 열린 설정에서 '이 앱의 설치 허용'을 켜 주세요. 허용하면 자동으로 설치가 이어집니다.",
                    color = Sm.warning,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                TextButton(onClick = { onInstall(state.info, state.file) }) {
                    Text("다시 시도", color = Sm.text2)
                }
            }
            is UpdateUiState.Failed -> if (state.info != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Sm.danger.copy(alpha = 0.06f))
                        .border(1.dp, Sm.danger.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.message,
                        color = Sm.danger,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRetry(state.info) }) {
                        Text("재시도", color = Sm.text2)
                    }
                }
            }
            else -> {}
        }
    }

    private suspend fun sendNewSms(creds: SavedCredentials, phone: String, text: String): Boolean {
        return try {
            sendNewSmsInternal(creds, phone, text)
        } catch (e: LinkageError) {
            Log.e("MainActivity", "SMS crypto module unavailable", e)
            false
        } catch (e: Exception) {
            Log.e("MainActivity", "SMS send/sync failed", e)
            false
        }
    }

    private suspend fun sendNewSmsInternal(
        creds: SavedCredentials,
        phone: String,
        text: String,
    ): Boolean {
        val dispatched = OutgoingSmsDispatcher.queueAndSend(this, creds, phone, text)
        // Relay preparation is durable and may complete immediately or after a
        // later reconnect; carrier SMS itself does not depend on Oracle uptime.
        startBridgeService()
        return dispatched
    }

    private fun carrierStatusLabel(status: String): String = when (status) {
        "queued" -> " · 대기"
        "dispatched" -> " · 발송 요청"
        "sent" -> " · 통신사 접수"
        "delivered" -> " · 전달됨"
        "failed" -> " · 발송 실패"
        "delivery_failed" -> " · 전달 실패"
        "unknown" -> " · 상태 확인 중"
        else -> ""
    }

}
