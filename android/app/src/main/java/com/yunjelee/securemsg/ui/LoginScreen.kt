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
    "이메일 인증코드로 비밀번호를 재설정할 수 있습니다. 가입 또는 재설정에 사용한 이메일 주소를 안전하게 보관하세요."
internal const val NEW_DEVICE_HISTORY_WARNING =
    "새 휴대폰·새 설치는 기기 등록 이전 메시지를 복호화할 수 없습니다. 현재는 기존 기기 전송이나 암호화 백업 기능을 제공하지 않습니다."

private class EmailRegistrationRequired(val challengeId: String) : Exception()

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
    var registrationEmail by remember { mutableStateOf("") }
    var registrationChallenge by remember { mutableStateOf<String?>(null) }
    var registrationCode by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(ServerConfig.url(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    var recoveryOpen by remember { mutableStateOf(false) }
    var recoveryEmail by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var recoveryChallenge by remember { mutableStateOf<String?>(null) }
    var recoveryNewPassword by remember { mutableStateOf("") }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }

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
                SmTextField(
                    value = registrationEmail,
                    onValueChange = { registrationEmail = it.take(320) },
                    label = "가입 이메일 (새 계정)", singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                registrationChallenge?.let {
                    SmTextField(
                        value = registrationCode,
                        onValueChange = { registrationCode = it.filter(Char::isDigit).take(6) },
                        label = "가입 인증코드 (6자리)", singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                                val saved = doLogin(
                                    context, serverUrl.trim(), username.trim(), password,
                                    registrationEmail.trim(), registrationChallenge, registrationCode,
                                )
                                withContext(Dispatchers.Main) {
                                    ServerConfig.save(context, serverUrl.trim())
                                    onLogin(saved)
                                }
                            } catch (e: LinkageError) {
                                Log.e("LoginScreen", "Crypto native library initialization failed", e)
                                withContext(Dispatchers.Main) {
                                    error = "암호화 모듈을 불러오지 못했습니다. 앱을 최신 버전으로 다시 설치해 주세요."
                                }
                            } catch (e: EmailRegistrationRequired) {
                                withContext(Dispatchers.Main) {
                                    registrationChallenge = e.challengeId
                                    registrationCode = ""
                                    error = "가입 이메일로 인증코드를 보냈습니다. 코드를 입력한 뒤 다시 눌러 주세요."
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
                Text(
                    if (recoveryOpen) "비밀번호 찾기 닫기" else "비밀번호를 잊으셨나요?",
                    color = Sm.cyan, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            recoveryOpen = !recoveryOpen
                            error = null
                            recoveryMessage = null
                        }
                        .padding(6.dp),
                )
                if (recoveryOpen) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "가입한 이메일로 인증코드를 보내 새 비밀번호를 설정합니다.",
                        color = Sm.text3, fontSize = 11.sp, lineHeight = 16.sp,
                    )
                    SmTextField(
                        value = recoveryEmail,
                        onValueChange = { recoveryEmail = it.take(320) },
                        label = "가입 이메일", singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (recoveryChallenge == null) {
                        SmGradientButton(
                            text = if (busy) "전송 중…" else "인증코드 받기",
                            enabled = !busy && username.isNotBlank() && recoveryEmail.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true; error = null; recoveryMessage = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val challenge = requestPasswordReset(
                                            context, serverUrl.trim(), username.trim(), recoveryEmail.trim(),
                                        )
                                        withContext(Dispatchers.Main) {
                                            recoveryChallenge = challenge
                                            recoveryMessage = "인증코드를 이메일로 보냈습니다. 10분 안에 입력하세요."
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) { error = e.message ?: "인증코드 전송에 실패했습니다." }
                                    } finally {
                                        withContext(Dispatchers.Main) { busy = false }
                                    }
                                }
                            },
                        )
                    } else {
                        SmTextField(
                            value = recoveryCode,
                            onValueChange = { recoveryCode = it.filter(Char::isDigit).take(6) },
                            label = "이메일 인증코드 (6자리)", singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SmTextField(
                            value = recoveryNewPassword,
                            onValueChange = { recoveryNewPassword = it.take(1024) },
                            label = "새 비밀번호 (8자 이상)", singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SmGradientButton(
                            text = if (busy) "변경 중…" else "비밀번호 변경",
                            enabled = !busy && recoveryCode.length == 6 && recoveryNewPassword.length >= 8,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true; error = null; recoveryMessage = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        confirmPasswordReset(
                                            context, serverUrl.trim(), username.trim(), recoveryEmail.trim(),
                                            recoveryChallenge.orEmpty(), recoveryCode, recoveryNewPassword,
                                        )
                                        withContext(Dispatchers.Main) {
                                            recoveryOpen = false
                                            recoveryChallenge = null
                                            recoveryCode = ""
                                            recoveryNewPassword = ""
                                            recoveryMessage = "비밀번호가 변경되었습니다. 새 비밀번호로 로그인하세요."
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) { error = e.message ?: "비밀번호 변경에 실패했습니다." }
                                    } finally {
                                        withContext(Dispatchers.Main) { busy = false }
                                    }
                                }
                            },
                        )
                        TextButton(onClick = {
                            recoveryChallenge = null
                            recoveryCode = ""
                            error = null
                        }) { Text("인증코드 다시 받기", color = Sm.text3) }
                    }
                    recoveryMessage?.let {
                        Text(it, color = Sm.cyan, fontSize = 12.sp, lineHeight = 16.sp)
                    }
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

internal suspend fun requestPasswordReset(
    context: Context,
    serverUrl: String,
    username: String,
    email: String,
): String {
    if (!Regex("^[a-z0-9_]{3,20}$").matches(username)) {
        throw IllegalArgumentException("아이디는 영소문자·숫자·_ 3~20자로 입력하세요.")
    }
    if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) {
        throw IllegalArgumentException("올바른 이메일 주소를 입력하세요.")
    }
    val api = RelayApi(validateServerUrl(serverUrl))
    val response = api.requestPasswordReset(username, email.lowercase(Locale.ROOT))
    if (!response.optBoolean("ok")) throw Exception(response.optString("error", "인증코드 전송에 실패했습니다."))
    return response.getString("challenge_id")
}

internal suspend fun confirmPasswordReset(
    context: Context,
    serverUrl: String,
    username: String,
    email: String,
    challengeId: String,
    code: String,
    newPassword: String,
) {
    if (challengeId.isBlank() || !Regex("^\\d{6}$").matches(code)) {
        throw IllegalArgumentException("6자리 인증코드를 입력하세요.")
    }
    if (newPassword.length < 8) throw IllegalArgumentException("새 비밀번호는 8자 이상이어야 합니다.")
    val api = RelayApi(validateServerUrl(serverUrl))
    val pwHash = CryptoUtil.hashPassword(newPassword, CryptoUtil.saltForUser(username))
    val response = api.confirmPasswordReset(
        username, email.lowercase(Locale.ROOT), challengeId, code, pwHash,
    )
    if (!response.optBoolean("ok")) throw Exception(response.optString("error", "비밀번호 변경에 실패했습니다."))
}

private fun validateServerUrl(serverUrl: String): String {
    val parsedUrl = serverUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("올바른 서버 URL을 입력하세요.")
    if (parsedUrl.scheme != "https" && !isLocalTestHost(parsedUrl.host)) {
        throw IllegalArgumentException("원격 서버는 HTTPS 주소를 사용해야 합니다.")
    }
    if (parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty() ||
        parsedUrl.encodedPath != "/" || parsedUrl.query != null || parsedUrl.fragment != null
    ) throw IllegalArgumentException("서버 URL은 도메인과 포트까지만 입력하세요.")
    return parsedUrl.toString().trimEnd('/')
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
    registrationEmail: String = "",
    registrationChallenge: String? = null,
    registrationCode: String = "",
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

    // User doesn't exist → email-verified registration.
    if (password.length < 8) {
        throw IllegalArgumentException("새 계정 비밀번호는 8자 이상이면 됩니다. 영문·숫자·특수문자는 자유롭게 조합할 수 있습니다.")
    }
    if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(registrationEmail)) {
        throw IllegalArgumentException("새 계정은 가입 이메일을 입력해야 합니다.")
    }
    if (registrationChallenge == null) {
        val requested = api.registerEmailRequest(username, registrationEmail, pwHash)
        if (!requested.optBoolean("ok")) {
            throw Exception(requested.optString("error", "인증코드 전송에 실패했습니다."))
        }
        throw EmailRegistrationRequired(requested.getString("challenge_id"))
    }
    if (!Regex("^\\d{6}$").matches(registrationCode)) {
        throw IllegalArgumentException("가입 이메일로 받은 6자리 인증코드를 입력하세요.")
    }
    val reg = api.registerEmailVerify(registrationChallenge, registrationCode)
    if (!reg.optBoolean("ok")) {
        throw Exception(reg.optString("error", "이메일 인증에 실패했습니다."))
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
