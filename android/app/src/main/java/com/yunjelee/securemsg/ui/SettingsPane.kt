package com.yunjelee.securemsg.ui

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.AppDatabase
import com.yunjelee.securemsg.BlocklistSync
import com.yunjelee.securemsg.BuildConfig
import com.yunjelee.securemsg.ContactSync
import com.yunjelee.securemsg.ContactSyncStatus
import com.yunjelee.securemsg.Credentials
import com.yunjelee.securemsg.DeviceSecurityController
import com.yunjelee.securemsg.DeviceSecurityView
import com.yunjelee.securemsg.DeviceTrustCrypto
import com.yunjelee.securemsg.DeviceTrustRepository
import com.yunjelee.securemsg.PendingDeviceApproval
import com.yunjelee.securemsg.PhoneNumberNormalizer
import com.yunjelee.securemsg.RelayApi
import com.yunjelee.securemsg.RelayTrustedDeviceApi
import com.yunjelee.securemsg.SavedCredentials
import com.yunjelee.securemsg.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Add a local/shared block rule as one serialized operation (IO). */
internal suspend fun addBlockRule(context: Context, type: String, value: String) {
    try {
        val saved = Credentials.load(context)
        if (saved == null) {
            BlocklistSync.addLocal(context, type, value)
            return
        }
        val api = RelayApi(ServerConfig.url(context)).also { it.token = saved.token }
        BlocklistSync.addShared(context, api, type, value)
    } catch (e: Exception) {
        Log.w("SettingsPane", "block rule push failed", e)
    }
}

/** Remove a local/shared block rule as one serialized operation (IO). */
internal suspend fun removeBlockRuleOnServer(context: Context, type: String, value: String) {
    try {
        val saved = Credentials.load(context)
        if (saved == null) {
            BlocklistSync.removeLocal(context, type, value)
            return
        }
        val api = RelayApi(ServerConfig.url(context)).also { it.token = saved.token }
        BlocklistSync.removeShared(context, api, type, value)
    } catch (e: Exception) {
        Log.w("SettingsPane", "block rule remove failed", e)
    }
}

/** "차단·설정 (Block·Settings)" tab: shared block rules, update controls, quarantined SMS, dev tools. */
@Composable
fun SettingsPane(
    creds: SavedCredentials,
    update: UpdateFlow,
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onLogout: () -> Unit,
    onSimulateSms: () -> Unit,
    onTestUpdateFlow: () -> Unit,
) {
    val context = LocalContext.current
    val db = AppDatabase.get(context)
    val scope = rememberCoroutineScope()
    val blocklist by db.blocklistDao().observeAll().collectAsState(initial = emptyList())
    val blockedSenders by db.blockedSenderDao().observeAll().collectAsState(initial = emptyList())
    val blockedSms by db.blockedSmsDao().observeAll().collectAsState(initial = emptyList())
    val trustRepo = remember(db) { DeviceTrustRepository(db) }
    val trustPins by trustRepo.observePins(creds.uid.toLong()).collectAsState(initial = emptyList())
    val trustState by trustRepo.observeState(creds.uid.toLong()).collectAsState(initial = null)
    val clipboard = LocalClipboardManager.current
    var newKw by remember { mutableStateOf("") }
    var newBlockedPhone by remember { mutableStateOf("") }
    // Server-side shared block rules cache (rules added on other devices).
    var sharedRules by remember { mutableStateOf(BlocklistSync.load(context)) }
    var contactStatus by remember { mutableStateOf(ContactSync.loadStatus(context)) }
    var contactMessage by remember { mutableStateOf<String?>(null) }
    var contactSyncing by remember { mutableStateOf(false) }
    var deviceSecurity by remember { mutableStateOf(DeviceSecurityView()) }
    var deviceSecurityLoading by remember { mutableStateOf(false) }
    var deviceActionMessage by remember { mutableStateOf<String?>(null) }

    fun securityController(): DeviceSecurityController {
        val relay = RelayApi(ServerConfig.url(context)).also { it.token = creds.token }
        return DeviceSecurityController(
            RelayTrustedDeviceApi(relay, creds.uid.toLong()), creds, trustRepo,
        )
    }

    fun refreshDeviceSecurity() {
        if (deviceSecurityLoading) return
        deviceSecurityLoading = true
        scope.launch(Dispatchers.IO) {
            val result = securityController().refresh()
            withContext(Dispatchers.Main) {
                deviceSecurity = result
                deviceSecurityLoading = false
            }
        }
    }

    fun actOnPending(device: PendingDeviceApproval, approve: Boolean) {
        deviceActionMessage = null
        scope.launch(Dispatchers.IO) {
            val ok = try {
                val controller = securityController()
                if (approve) controller.approve(device) else controller.reject(device)
            } catch (e: Exception) {
                Log.w("SettingsPane", "pending device action failed", e)
                false
            }
            withContext(Dispatchers.Main) {
                deviceActionMessage = if (ok) {
                    if (approve) "기기를 승인했습니다." else "기기 요청을 거절했습니다."
                } else {
                    "기기 요청 처리에 실패했습니다. 서버 상태를 확인해 주세요."
                }
                refreshDeviceSecurity()
            }
        }
    }

    fun cancelOwnPending() {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { securityController().cancelOwnPending() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) onLogout()
                else deviceActionMessage = "승인 요청 취소에 실패했습니다. 서버 연결을 확인해 주세요."
            }
        }
    }

    fun upgradeLegacySecurity() {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { securityController().upgradeLegacySecurity() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                deviceActionMessage = if (ok) "계정 기기 보안을 verified_v2로 업그레이드했습니다."
                    else "보안 업그레이드 실패: identity 기기에서 다시 시도해 주세요."
                refreshDeviceSecurity()
            }
        }
    }

    fun syncContacts() {
        if (contactSyncing) return
        contactSyncing = true
        contactMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                val saved = Credentials.load(context)
                    ?: error("다시 로그인한 뒤 동기화해 주세요")
                val api = RelayApi(ServerConfig.url(context)).also { it.token = saved.token }
                val result = ContactSync.sync(context, api)
                withContext(Dispatchers.Main) {
                    contactStatus = result
                    contactMessage = if (result.failedUploadCount == 0) {
                        "이 기기와 다른 로그인 기기에 연락처 이름을 반영했습니다."
                    } else {
                        "로컬 반영 완료 · 서버 업로드 ${result.uploadedCount}건 · " +
                            "실패 ${result.failedUploadCount}건"
                    }
                }
            } catch (e: Exception) {
                Log.w("SettingsPane", "local contact sync failed", e)
                withContext(Dispatchers.Main) {
                    contactMessage = "연락처 동기화 실패: ${e.message ?: "알 수 없는 오류"}"
                }
            } finally {
                withContext(Dispatchers.Main) { contactSyncing = false }
            }
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            syncContacts()
        } else {
            contactMessage = "연락처 이름 표시를 사용하려면 연락처 읽기 권한이 필요합니다."
        }
    }

    fun reloadShared() {
        sharedRules = BlocklistSync.load(context)
    }

    LaunchedEffect(creds.sid) {
        reloadShared()
        // One refresh per settings visit. Pending-device changes arrive via the
        // service's device_pending socket event and MainScreen already polls the
        // device list for its badge, so this pane no longer adds its own loop.
        refreshDeviceSecurity()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!notificationPermissionGranted) {
            SmCard {
                SectionTitle("알림 권한")
                Caption("알림 권한이 꺼져 있어 새 SMS 알림을 표시할 수 없습니다. SMS 브리지는 계속 사용할 수 있습니다.")
                SmGradientButton(
                    text = "알림 권한 요청",
                    onClick = onRequestNotificationPermission,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SmCard {
            SectionTitle("기기 보안")
            Caption(
                "새 기기는 기존 기기의 Ed25519 승인을 받아야 합니다. 키가 바뀌거나 보안 epoch가 " +
                    "되돌아가면 로컬 pin을 덮어쓰지 않고 동기화를 차단합니다.",
            )
            deviceSecurity.trustWarning?.let {
                Text(it, color = Sm.danger, fontSize = 12.sp, lineHeight = 16.sp)
            }
            when {
                deviceSecurity.selfPending -> Text(
                    "이 기기는 승인 대기 중입니다. 이미 승인된 다른 기기의 설정에서 요청을 확인하세요.",
                    color = Sm.warning, fontSize = 12.sp, lineHeight = 16.sp,
                )
                deviceSecurity.serverUnsupported -> Caption("현재 서버가 기기 승인 API를 아직 지원하지 않습니다.")
                deviceSecurity.error != null -> Text(
                    "조회 실패: ${deviceSecurity.error}", color = Sm.warning, fontSize = 12.sp,
                )
                deviceSecurity.pending.isEmpty() -> Caption("승인 대기 중인 기기가 없습니다.")
            }
            if (deviceSecurity.selfPending) {
                SmGhostButton(
                    text = "이 기기의 승인 요청 취소",
                    onClick = ::cancelOwnPending,
                    modifier = Modifier.fillMaxWidth(),
                    textColor = Sm.danger,
                )
            }
            if (deviceSecurity.securityMode == "legacy_v1") {
                Text(
                    "레거시 기기들은 검증되지 않았습니다. 업그레이드하면 현재 identity 기기 외의 " +
                        "레거시 기기는 다시 승인을 받아야 합니다.",
                    color = Sm.warning, fontSize = 12.sp, lineHeight = 16.sp,
                )
                SmGradientButton(
                    text = "기기 보안 업그레이드",
                    onClick = ::upgradeLegacySecurity,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            deviceSecurity.pending.forEach { pending ->
                PendingDeviceRow(
                    device = pending,
                    fingerprint = runCatching {
                        DeviceTrustCrypto.deviceFingerprint(pending.pubKey, pending.sigPub)
                    }.getOrElse { "유효하지 않은 공개키" },
                    onApprove = { actOnPending(pending, true) },
                    onReject = { actOnPending(pending, false) },
                )
            }
            deviceActionMessage?.let { Text(it, color = Sm.text3, fontSize = 12.sp) }
            SmGhostButton(
                text = if (deviceSecurityLoading) "확인 중…" else "기기 보안 새로고침",
                onClick = ::refreshDeviceSecurity,
                modifier = Modifier.fillMaxWidth(),
            )
            trustState?.let { state ->
                Caption("보안 epoch ${state.epoch} · 디렉터리 ${state.directoryHash.take(12)}…")
                Text(
                    state.safetyNumber,
                    color = Sm.text1,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Caption("안전 번호는 계정 identity key에서 계산되며 서버가 바뀌어도 동일해야 합니다.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmGhostButton(
                        text = "안전 번호 복사",
                        onClick = { clipboard.setText(AnnotatedString(state.safetyNumber)) },
                        modifier = Modifier.weight(1f),
                    )
                    SmGhostButton(
                        text = "QR 데이터 복사",
                        onClick = {
                            clipboard.setText(AnnotatedString(
                                DeviceTrustCrypto.safetyQrPayload(state.accountUid, state.identityKey),
                            ))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            trustPins.forEach { pin ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Sm.surfaceAlt).padding(10.dp),
                ) {
                    Text("${pin.name} · ${pin.kind}", color = Sm.text2, fontSize = 12.sp)
                    Text(pin.fingerprint, color = Sm.text4, fontSize = 10.sp)
                }
            }
        }
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
                            scope.launch(Dispatchers.IO) {
                                addBlockRule(context, "keyword", keyword)
                                withContext(Dispatchers.Main) {
                                    newKw = ""
                                    reloadShared()
                                }
                            }
                        }
                    },
                )
            }
            val localKeywords = blocklist.map { it.keyword }.toSet()
            if (blocklist.isEmpty() && sharedRules.keywords.all { it in localKeywords }) {
                Caption("추가된 키워드가 없습니다.")
            }
            blocklist.forEach { keyword ->
                RuleRow(
                    value = keyword.keyword,
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            removeBlockRuleOnServer(context, "keyword", keyword.keyword)
                            withContext(Dispatchers.Main) { reloadShared() }
                        }
                    },
                )
            }
            sharedRules.keywords.filter { it !in localKeywords }.forEach { value ->
                RuleRow(
                    value = value,
                    subtitle = "다른 기기에서 추가됨",
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            removeBlockRuleOnServer(context, "keyword", value)
                            withContext(Dispatchers.Main) { reloadShared() }
                        }
                    },
                )
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
                            scope.launch(Dispatchers.IO) {
                                addBlockRule(context, "sender", number)
                                withContext(Dispatchers.Main) {
                                    newBlockedPhone = ""
                                    reloadShared()
                                }
                            }
                        }
                    },
                )
            }
            val localSenders = blockedSenders.map { it.phoneNumber }.toSet()
            if (blockedSenders.isEmpty() && sharedRules.senders.all { it in localSenders }) {
                Caption("차단된 번호가 없습니다.")
            }
            blockedSenders.forEach { sender ->
                RuleRow(
                    value = sender.phoneNumber,
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            removeBlockRuleOnServer(context, "sender", sender.phoneNumber)
                            withContext(Dispatchers.Main) { reloadShared() }
                        }
                    },
                )
            }
            sharedRules.senders.filter { it !in localSenders }.forEach { value ->
                RuleRow(
                    value = value,
                    subtitle = "다른 기기에서 추가됨",
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            removeBlockRuleOnServer(context, "sender", value)
                            withContext(Dispatchers.Main) { reloadShared() }
                        }
                    },
                )
            }
        }

        SmCard {
            SectionTitle("연락처 이름")
            Caption(
                "폰 연락처와 전화번호를 이 기기에서 대조한 뒤, 일치한 이름(또는 삭제 상태)을 " +
                    "다른 로그인 기기에도 표시하도록 릴레이 서버에 저장합니다. 전체 연락처 목록은 " +
                    "업로드하지 않습니다.",
            )
            ContactSyncStatusText(contactStatus)
            SmGhostButton(
                text = if (contactSyncing) "동기화 중…" else "연락처 이름 동기화",
                onClick = {
                    if (contactSyncing) return@SmGhostButton
                    if (ContactSync.hasPermission(context)) {
                        syncContacts()
                    } else {
                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            contactMessage?.let { Text(it, color = Sm.text3, fontSize = 12.sp) }
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
                    checked = update.autoEnabled,
                    onCheckedChange = update.onToggleAuto,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Sm.accentDeep,
                        uncheckedThumbColor = Sm.text4,
                        uncheckedTrackColor = Sm.surfaceAlt,
                    ),
                )
            }
            SmGhostButton(
                text = when (update.state) {
                    is UpdateUiState.Checking -> "확인 중…"
                    is UpdateUiState.Downloading -> "다운로드 중…"
                    else -> "업데이트 확인"
                },
                onClick = { update.onCheck(true) },
                modifier = Modifier.fillMaxWidth(),
            )
            update.message?.let {
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
                        scope.launch(Dispatchers.IO) {
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
                    onClick = onSimulateSms,
                    modifier = Modifier.fillMaxWidth(),
                )
                SmGhostButton(
                    text = "업데이트 흐름 테스트 (최신 릴리스 강제 다운로드→설치)",
                    onClick = onTestUpdateFlow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SmGhostButton(
            text = "로그아웃",
            textColor = Sm.danger,
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PendingDeviceRow(
    device: PendingDeviceApproval,
    fingerprint: String,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Sm.warning.copy(alpha = 0.08f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("새 기기 승인 요청: ${device.name}", color = Sm.warning, fontSize = 13.sp)
        Caption("${device.kind} · ${device.sid}\n$fingerprint")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmGradientButton("승인", onApprove, Modifier.weight(1f))
            SmGhostButton("거절", onReject, Modifier.weight(1f), Sm.danger)
        }
    }
}

@Composable
private fun ContactSyncStatusText(status: ContactSyncStatus?) {
    if (status == null) {
        Caption("아직 동기화하지 않았습니다.")
        return
    }
    val timestamp = remember(status.lastSyncedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(status.lastSyncedAt))
    }
    Caption(
        "마지막 동기화: $timestamp · 연락처 번호 ${status.contactPhoneCount}개 · " +
            "대화 ${status.matchedThreadCount}개 일치 · 서버 ${status.uploadedCount}건" +
            if (status.failedUploadCount > 0) " · 실패 ${status.failedUploadCount}건" else "",
    )
}

/**
 * One shared-block-rule row (keyword or sender). `subtitle` marks rules that
 * were added on another device; deletion always goes through the relay.
 */
@Composable
private fun RuleRow(
    value: String,
    subtitle: String? = null,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Sm.surfaceAlt)
            .padding(start = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (subtitle != null) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(value, color = Sm.text2, fontSize = 13.sp)
                Text(subtitle, color = Sm.text4, fontSize = 10.sp)
            }
        } else {
            Text(value, color = Sm.text2, fontSize = 13.sp)
        }
        TextButton(onClick = onDelete) {
            Text("삭제", color = Sm.danger, fontSize = 12.sp)
        }
    }
}
