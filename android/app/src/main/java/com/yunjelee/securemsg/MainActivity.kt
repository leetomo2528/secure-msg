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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : ComponentActivity() {

    private var smsRoleHeld by mutableStateOf(false)
    private var smsPermissionsGranted by mutableStateOf(false)

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        smsRoleHeld = isDefaultSmsApp()
        smsPermissionsGranted = hasSmsPerms()

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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
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
                title = { Text("로컬 기기 초기화") },
                text = { Text("이 휴대폰의 개인키와 로컬 메시지를 삭제합니다. 서버에 남은 기기 등록은 다른 기기에서 폐기해야 합니다.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmForget = false
                        username = ""
                        password = ""
                        onForgetLocalDevice()
                    }) { Text("삭제") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForget = false }) { Text("취소") }
                },
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SecureMsg SMS Bridge", color = Color(0xFF22D3EE), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("자가호스팅 E2E SMS 동기화. 전화번호/이메일 없이 임의 아이디만 사용.",
                color = Color(0xFF64748B), fontSize = 12.sp)

            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it.take(2048) },
                label = { Text("서버 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(20).lowercase(Locale.ROOT) },
                label = { Text("아이디 (3-20자, a-z0-9_)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it.take(1024) },
                label = { Text("비밀번호 (8자 이상, 영문·숫자·특수문자 자유롭게)") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )

            error?.let {
                Text(it, color = Color(0xFFEF4444), fontSize = 12.sp,
                    modifier = Modifier.background(Color(0x33EF4444)).padding(8.dp))
            }

            Button(
                onClick = {
                    if (busy) return@Button
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
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "처리 중…" else "로그인 / 회원가입")
            }
            if (rememberedUsername != null) {
                TextButton(
                    onClick = { confirmForget = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("이 휴대폰의 로컬 기기 초기화", color = Color(0xFFEF4444)) }
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

        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(status, color = Color(0xFF22D3EE), fontSize = 12.sp)
            if (!smsRoleHeld) {
                Text(
                    "자동 차단과 SMS 발신을 사용하려면 SecureMsg를 기본 SMS 앱으로 설정하세요.",
                    color = Color(0xFFF59E0B), fontSize = 12.sp,
                )
                Button(onClick = requestSmsRole, modifier = Modifier.fillMaxWidth()) {
                    Text("기본 SMS 앱으로 설정")
                }
            }
            if (smsRoleHeld && !smsPermissionsGranted) {
                Button(onClick = { requestPerms() }, modifier = Modifier.fillMaxWidth()) {
                    Text("SMS 권한 요청")
                }
            }

            TabRow(
                selectedTabIndex = selectedSection,
                containerColor = Color(0xFF0F172A),
                contentColor = Color(0xFF22D3EE),
            ) {
                Tab(
                    selected = selectedSection == 0,
                    onClick = { selectedSection = 0 },
                    text = { Text("메시지") },
                )
                Tab(
                    selected = selectedSection == 1,
                    onClick = { selectedSection = 1 },
                    text = { Text("차단·설정") },
                )
            }

            if (selectedSection == 0) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val thread = selectedThread
                    if (thread == null) {
                        Text(
                            "새 SMS 발신",
                            color = Color(0xFFE2E8F0),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        OutlinedTextField(
                            value = newPhone,
                            onValueChange = { newPhone = it.take(32) },
                            label = { Text("전화번호") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors(),
                        )
                        OutlinedTextField(
                            value = newMsg,
                            onValueChange = { newMsg = it.take(20_000) },
                            label = { Text("메시지") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors(),
                            minLines = 1,
                            maxLines = 3,
                        )
                        Button(
                            onClick = {
                                if (sending || newPhone.isBlank() || newMsg.isBlank()) return@Button
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
                            enabled = !sending && smsRoleHeld && smsPermissionsGranted &&
                                newPhone.isNotBlank() && newMsg.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (sending) "발송 중…" else "발송 + 동기화") }

                        HorizontalDivider(color = Color(0xFF1E293B))
                        Text(
                            "SMS 스레드 (${threads.size})",
                            color = Color(0xFFE2E8F0),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (threads.isEmpty()) {
                            Text(
                                "아직 표시할 문자가 없습니다.",
                                color = Color(0xFF64748B),
                                fontSize = 12.sp,
                            )
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            items(threads, key = { it.cid }) { item ->
                                Text(
                                    item.contactName ?: item.phoneNumber,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedThread = item }
                                        .padding(vertical = 10.dp),
                                )
                                HorizontalDivider(color = Color(0xFF1E293B))
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(thread.phoneNumber, color = Color(0xFF22D3EE), fontSize = 14.sp)
                            TextButton(onClick = { selectedThread = null; reply = "" }) {
                                Text("목록")
                            }
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            items(selectedMessages, key = { it.id }) { message ->
                                Text(
                                    text = if (message.blocked) {
                                        "차단된 메시지"
                                    } else {
                                        "${if (message.mine) "나" else "수신"}: ${message.plaintext}" +
                                            carrierStatusLabel(message.carrierStatus)
                                    },
                                    color = if (message.blocked) Color(0xFF64748B) else Color(0xFFCBD5E1),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 5.dp),
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().imePadding(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = reply,
                                onValueChange = { reply = it.take(20_000) },
                                label = { Text("답장") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = fieldColors(),
                            )
                            Button(
                                onClick = {
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
                                enabled = reply.isNotBlank() && !sending &&
                                    smsRoleHeld && smsPermissionsGranted,
                            ) { Text(if (sending) "전송 중…" else "전송") }
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "차단 키워드",
                        color = Color(0xFFE2E8F0),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "키워드·발신번호는 모든 기기에 동기화됩니다. 문자 내용은 이 기기에서 복호화한 뒤 검사하며 서버에는 평문으로 보내지 않습니다.",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newKw,
                            onValueChange = { newKw = it.take(120) },
                            label = { Text("키워드") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = fieldColors(),
                        )
                        Button(onClick = {
                            val keyword = newKw.trim()
                            if (keyword.isNotEmpty()) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.blocklistDao().insert(BlockKeyword(keyword = keyword))
                                    pushBlockRulesToServer()
                                    withContext(Dispatchers.Main) { newKw = "" }
                                }
                            }
                        }) { Text("추가") }
                    }
                    blocklist.forEach { keyword ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(keyword.keyword, color = Color(0xFFCBD5E1), fontSize = 13.sp)
                            TextButton(onClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.blocklistDao().delete(keyword)
                                    removeBlockRuleOnServer("keyword", keyword.keyword)
                                }
                            }) { Text("삭제", color = Color(0xFFEF4444)) }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1E293B))
                    Text(
                        "발신번호 차단",
                        color = Color(0xFFE2E8F0),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "번호 차단은 이 Android 기기에서 수신 단계에 적용됩니다.",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newBlockedPhone,
                            onValueChange = { newBlockedPhone = it.take(32) },
                            label = { Text("전화번호") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = fieldColors(),
                        )
                        Button(onClick = {
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
                        }) { Text("차단") }
                    }
                    blockedSenders.forEach { sender ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sender.phoneNumber, color = Color(0xFFCBD5E1), fontSize = 13.sp)
                            TextButton(onClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.blockedSenderDao().delete(sender)
                                    removeBlockRuleOnServer("sender", sender.phoneNumber)
                                }
                            }) { Text("삭제", color = Color(0xFFEF4444)) }
                        }
                    }

                    if (BuildConfig.DEBUG) {
                        HorizontalDivider(color = Color(0xFF1E293B))
                        Text(
                            "개발자 도구",
                            color = Color(0xFFE2E8F0),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "SIM 없이 수신 경로를 검증합니다. 차단 판정 → 시스템 SMS 기록 → 알림 → 서버 relay까지 실제 코드 경로로 주입합니다.",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                        )
                        Button(
                            onClick = { simulateIncomingSms() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF164E63)),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("SMS 수신 시뮬레이션 (+821000000001)") }
                    }

                    HorizontalDivider(color = Color(0xFF1E293B))
                    Text(
                        "격리된 스팸 (${blockedSms.size})",
                        color = Color(0xFFE2E8F0),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (blockedSms.isEmpty()) {
                        Text("격리된 문자가 없습니다.", color = Color(0xFF64748B), fontSize = 11.sp)
                    }
                    blockedSms.take(20).forEach { item ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${item.phoneNumber}: ${item.reason}\n${item.body.take(120)}",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                maxLines = 3,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.blockedSmsDao().delete(item)
                                }
                            }) { Text("삭제", color = Color(0xFFEF4444)) }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF1E293B))
                    Button(
                        onClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                stopService(Intent(this@MainActivity, SmsBridgeService::class.java))
                                Credentials.clearSession(this@MainActivity)
                                withContext(Dispatchers.Main) { onLogout() }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("로그아웃") }
                    Spacer(Modifier.height(8.dp))
                }
            }
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

    @Composable
    private fun fieldColors() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFE2E8F0),
        unfocusedTextColor = Color(0xFFE2E8F0),
        focusedContainerColor = Color(0xFF0F172A),
        unfocusedContainerColor = Color(0xFF0F172A),
        cursorColor = Color(0xFF22D3EE),
        focusedBorderColor = Color(0xFF22D3EE),
        unfocusedBorderColor = Color(0xFF334155),
        focusedLabelColor = Color(0xFF22D3EE),
        unfocusedLabelColor = Color(0xFF64748B),
    )
}
