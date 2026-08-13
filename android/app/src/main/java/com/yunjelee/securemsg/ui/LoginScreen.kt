package com.yunjelee.securemsg.ui

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.BuildConfig
import com.yunjelee.securemsg.Credentials
import com.yunjelee.securemsg.CryptoUtil
import com.yunjelee.securemsg.DeviceLoginStatement
import com.yunjelee.securemsg.RelayApi
import com.yunjelee.securemsg.SavedCredentials
import com.yunjelee.securemsg.ServerConfig
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val ACCOUNT_RECOVERY_WARNING =
    "비밀번호 재설정·계정 복구 수단이 없습니다. 비밀번호를 잊으면 현재 로그인된 세션은 만료 전까지 동작할 수 있지만, 세션 만료 후에는 다시 로그인할 수 없습니다."
internal const val NEW_DEVICE_HISTORY_WARNING =
    "새 휴대폰·새 설치는 기기 등록 이전 메시지를 복호화할 수 없습니다. 현재는 기존 기기 전송이나 암호화 백업 기능을 제공하지 않습니다."

/** Login / auto-register screen (self-hosted relay, arbitrary username). */
@Composable
fun LoginScreen(
    rememberedUsername: String?,
    onForgetLocalDevice: () -> Unit,
    onLogin: (SavedCredentials) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember(rememberedUsername) { mutableStateOf(rememberedUsername.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(ServerConfig.url(context)) }
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
                .windowInsetsPadding(WindowInsets.safeDrawing)
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
            SmCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "가입·새 기기 등록 전 확인",
                    color = Sm.text1, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "복구 불가: $ACCOUNT_RECOVERY_WARNING",
                    color = Sm.text3, fontSize = 11.sp, lineHeight = 16.sp,
                )
                Text(
                    "새 기기 기록 제한: $NEW_DEVICE_HISTORY_WARNING",
                    color = Sm.text3, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }
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
                        scope.launch(Dispatchers.IO) {
                            try {
                                val saved = doLogin(context, serverUrl.trim(), username.trim(), password)
                                withContext(Dispatchers.Main) {
                                    ServerConfig.save(context, serverUrl.trim())
                                    onLogin(saved)
                                }
                            } catch (e: LinkageError) {
                                Log.e("LoginScreen", "Crypto native library initialization failed", e)
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

/**
 * Shared-secret login: device-login with a stored sid, otherwise register the
 * account (if new) and register this device's keypair.
 */
internal suspend fun doLogin(
    context: Context,
    serverUrl: String,
    username: String,
    password: String,
): SavedCredentials {
    if (!Regex("^[a-z0-9_]{3,20}$").matches(username)) {
        throw IllegalArgumentException("아이디는 영소문자·숫자·_ 3~20자로 입력하세요.")
    }
    if (password.isEmpty()) throw IllegalArgumentException("비밀번호를 입력하세요.")
    if (password.length > 1024) throw IllegalArgumentException("비밀번호가 너무 깁니다.")
    val parsedUrl = serverUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("올바른 서버 URL을 입력하세요.")
    if (parsedUrl.scheme != "https" && !isLocalTestHost(parsedUrl.host)) {
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
    val stored = Credentials.loadDevice(context)
    if (stored != null && stored.username != username) {
        throw IllegalStateException(
            "이 휴대폰에는 ${stored.username} 기기 키가 남아 있습니다. 먼저 로컬 기기를 초기화하세요.",
        )
    }
    if (stored != null && stored.username == username) {
        val challenge = api.deviceLoginChallenge(username, pwHash, stored.sid)
        val r = if (challenge.optBoolean("ok")) {
            val statement = DeviceLoginStatement(
                challenge.getLong("uid"), stored.sid, challenge.getString("challenge_id"),
                challenge.getString("challenge"), challenge.getLong("session_version"),
            ).canonical()
            val proof = CryptoUtil.signDetached(
                statement.toByteArray(Charsets.UTF_8), stored.keypair.signSk,
            )
            api.deviceLoginProof(
                username, pwHash, stored.sid, challenge.getString("challenge_id"),
                challenge.getString("challenge"), proof,
            )
        } else challenge
        if (r.optBoolean("ok")) {
            api.token = r.getString("token")
            val saved = stored.copy(token = r.getString("token"))
            Credentials.save(context, saved)
            return saved
        }
        if (r.optString("error") !in setOf("device not found", "device revoked")) {
            throw Exception(r.optString("error", "device login failed"))
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
        Credentials.save(context, saved)
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
    Credentials.save(context, saved)
    return saved
}

/**
 * Hosts allowed to speak plain HTTP (local test rigs only):
 * localhost/127.0.0.1 (adb reverse), 10.0.2.2 (emulator host alias),
 * and RFC1918 private ranges (real phone ↔ same-LAN dev box).
 */
internal fun isLocalTestHost(host: String): Boolean {
    if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return true
    val parts = host.split(".")
    if (parts.size != 4 || parts.any { p -> p.toIntOrNull()?.let { it in 0..255 } == null }) {
        return false
    }
    return when (parts[0].toInt()) {
        10 -> true
        192 -> parts[1] == "168"
        172 -> parts[1].toInt() in 16..31
        else -> false
    }
}
