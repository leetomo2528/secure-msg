package com.yunjelee.securemsg.ui

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.AppDatabase
import com.yunjelee.securemsg.BlocklistSync
import com.yunjelee.securemsg.BuildConfig
import com.yunjelee.securemsg.ContactSync
import com.yunjelee.securemsg.ContactSyncStatus
import com.yunjelee.securemsg.Credentials
import com.yunjelee.securemsg.CryptoUtil
import com.yunjelee.securemsg.DeviceSecurityController
import com.yunjelee.securemsg.DeviceSecurityView
import com.yunjelee.securemsg.DeviceTrustCrypto
import com.yunjelee.securemsg.DeviceTrustRepository
import com.yunjelee.securemsg.PairingHandshake
import com.yunjelee.securemsg.PairingQrFields
import com.yunjelee.securemsg.PendingDeviceApproval
import com.yunjelee.securemsg.parsePairingQr
import com.yunjelee.securemsg.PhoneNumberNormalizer
import com.yunjelee.securemsg.RelayApi
import com.yunjelee.securemsg.RelayTrustedDeviceApi
import com.yunjelee.securemsg.SavedCredentials
import com.yunjelee.securemsg.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/** Rows of the settings row-list card; tapping one expands its detail inline below. */
private enum class SettingsRow { Quarantine, BlockedSenders, ContactSync, Update }

/** Sender rules are validated the same way the dispatcher validates recipients. */
private val SenderRulePattern = Regex("^\\+?[0-9*#]{3,24}$")

/**
 * "설정" tab: device security, block keywords, then a row-list (quarantined
 * SMS · sender block · contact-name sync · app update) whose rows expand in
 * place, and dev tools on debug builds. Owns its 16dp horizontal gutter.
 */
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
    var scanningPairing by remember { mutableStateOf(false) }
    var pendingPairing by remember {
        mutableStateOf<Pair<PendingDeviceApproval, PairingHandshake>?>(null)
    }
    // Which row-list entry is open; null collapses all of them.
    var expanded by remember { mutableStateOf<SettingsRow?>(null) }

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

    /**
     * QR pairing, approver half. The scan only opens a session; the human
     * comparing the safety number on both screens is the authorization, so
     * nothing is signed until [confirmPairing].
     */
    fun onPairingPayload(payload: String) {
        scanningPairing = false
        deviceActionMessage = null
        val scanned: PairingQrFields? = parsePairingQr(payload)
        if (scanned == null) {
            deviceActionMessage = "QR을 읽지 못했습니다. 새 기기 화면의 코드를 다시 스캔하세요."
            return
        }
        val target = deviceSecurity.pending.firstOrNull { it.sid == scanned.sid }
        if (target == null) {
            deviceActionMessage = "이 코드에 해당하는 승인 대기 기기를 찾지 못했습니다."
            return
        }
        scope.launch(Dispatchers.IO) {
            val handshake = runCatching {
                securityController().openPairing(target, scanned)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (handshake == null) {
                    deviceActionMessage =
                        "페어링을 시작하지 못했습니다. QR의 키가 서버 등록 정보와 다르면 승인하지 마세요."
                } else {
                    pendingPairing = target to handshake
                }
            }
        }
    }

    fun confirmPairing() {
        val (device, handshake) = pendingPairing ?: return
        deviceActionMessage = null
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                securityController().approvePaired(device, handshake)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                pendingPairing = null
                deviceActionMessage = if (ok) "기기를 승인했습니다."
                    else "승인에 실패했습니다. 페어링이 만료됐을 수 있습니다."
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

    fun addKeyword() {
        val keyword = newKw.trim()
        if (keyword.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            addBlockRule(context, "keyword", keyword)
            withContext(Dispatchers.Main) {
                newKw = ""
                reloadShared()
            }
        }
    }

    fun addBlockedSender() {
        val number = PhoneNumberNormalizer.normalize(newBlockedPhone)
        if (!SenderRulePattern.matches(number)) return
        scope.launch(Dispatchers.IO) {
            addBlockRule(context, "sender", number)
            withContext(Dispatchers.Main) {
                newBlockedPhone = ""
                reloadShared()
            }
        }
    }

    fun toggleRow(row: SettingsRow) {
        expanded = if (expanded == row) null else row
    }

    LaunchedEffect(creds.sid) {
        reloadShared()
        // One refresh per settings visit. Pending-device changes arrive via the
        // service's device_pending socket event and MainScreen already polls the
        // device list for its badge, so this pane no longer adds its own loop.
        refreshDeviceSecurity()
    }

    // This gateway's own fingerprint for the card footer; the keypair is fixed
    // for the session, so compute it once.
    val ownFingerprint = remember(creds.keypair.boxPk, creds.keypair.signPk) {
        runCatching {
            DeviceTrustCrypto.deviceFingerprint(creds.keypair.boxPk, creds.keypair.signPk)
        }.getOrNull()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(username = creds.username, deviceName = creds.deviceName)

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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("기기 보안")
                securityChip(deviceSecurity)?.let { (text, color) -> SmChipSmall(text, color) }
            }
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
                val challenge = deviceSecurity.selfPendingChallenge
                if (challenge != null) {
                    // The nonce must survive a recomposition or a screen
                    // revisit: an approver may already be looking at a safety
                    // number derived from it, and a fresh one would make the
                    // two screens disagree — which reads as an attack.
                    val nonce = remember(creds.sid) { CryptoUtil.randomNonceB64u() }
                    PairingQrCard(
                        payload = pairingQrPayload(
                            server = ServerConfig.url(context),
                            username = creds.username,
                            sid = creds.sid,
                            challenge = challenge,
                            boxPk = creds.keypair.boxPk,
                            sigPk = creds.keypair.signPk,
                            nonceNew = nonce,
                            nowSeconds = System.currentTimeMillis() / 1000,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
            if (deviceSecurity.pending.isNotEmpty()) {
                SmInsetNotice(
                    title = "새 기기 승인 요청 ${deviceSecurity.pending.size}건",
                    subtitle = pendingSummary(deviceSecurity.pending.first()),
                )
            }
            val confirmation = pendingPairing
            if (confirmation != null) {
                SectionTitle("두 화면의 숫자가 같습니까?")
                Caption("새 기기 화면에도 같은 숫자가 떠 있어야 합니다. 다르면 승인하지 말고 취소하세요.")
                Text(
                    confirmation.second.safetyNumber,
                    color = Sm.teal, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                SmGradientButton(
                    text = "숫자가 같습니다 · 승인",
                    onClick = ::confirmPairing,
                    modifier = Modifier.fillMaxWidth(),
                )
                SmGhostButton(
                    text = "취소",
                    onClick = { pendingPairing = null },
                    modifier = Modifier.fillMaxWidth(),
                    textColor = Sm.danger,
                )
            } else if (scanningPairing) {
                PairingScanner(
                    onPayload = ::onPairingPayload,
                    onCancel = { scanningPairing = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (deviceSecurity.pending.isNotEmpty()) {
                SmGradientButton(
                    text = "QR 스캔으로 승인",
                    onClick = { scanningPairing = true },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = SmIconKind.Qr,
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
            ThisDeviceRow(
                fingerprint = ownFingerprint,
                status = if (deviceSecurity.selfPending) "승인 대기 중" else "승인됨",
            )
        }

        SmCard {
            SectionTitle("차단 키워드")
            Caption("키워드·발신번호는 모든 기기에 동기화됩니다. 문자 내용은 이 기기에서 복호화한 뒤 검사하며 서버에는 평문으로 보내지 않습니다.")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuleInput(
                    value = newKw,
                    onValueChange = { newKw = it.take(120) },
                    placeholder = "키워드",
                    onDone = ::addKeyword,
                    modifier = Modifier.weight(1f),
                )
                RuleAddButton(text = "추가", enabled = newKw.isNotBlank(), onClick = ::addKeyword)
            }
            val localKeywords = blocklist.map { it.keyword }.toSet()
            val sharedOnlyKeywords = sharedRules.keywords.filter { it !in localKeywords }
            if (blocklist.isEmpty() && sharedOnlyKeywords.isEmpty()) {
                Caption("추가된 키워드가 없습니다.")
            } else {
                KeywordChips {
                    blocklist.forEach { keyword ->
                        KeywordChip(
                            value = keyword.keyword,
                            shared = false,
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    removeBlockRuleOnServer(context, "keyword", keyword.keyword)
                                    withContext(Dispatchers.Main) { reloadShared() }
                                }
                            },
                        )
                    }
                    sharedOnlyKeywords.forEach { value ->
                        KeywordChip(
                            value = value,
                            shared = true,
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    removeBlockRuleOnServer(context, "keyword", value)
                                    withContext(Dispatchers.Main) { reloadShared() }
                                }
                            },
                        )
                    }
                }
            }
        }

        // Row-list and its inline details share one unspaced column so a
        // collapsed (zero-height) detail does not claim a 12dp gap of its own.
        val localSenders = blockedSenders.map { it.phoneNumber }.toSet()
        val sharedOnlySenders = sharedRules.senders.filter { it !in localSenders }
        val syncLabel = remember(contactStatus?.lastSyncedAt) {
            syncTimeLabel(contactStatus?.lastSyncedAt)
        }
        Column(Modifier.fillMaxWidth()) {
            SmListRowCard {
                SmListRow(
                    label = "격리된 스팸",
                    value = "${blockedSms.size}건",
                    onClick = { toggleRow(SettingsRow.Quarantine) },
                )
                SmListRow(
                    label = "발신번호 차단",
                    value = "${localSenders.size + sharedOnlySenders.size}개",
                    onClick = { toggleRow(SettingsRow.BlockedSenders) },
                )
                SmListRow(
                    label = "연락처 이름 동기화",
                    value = syncLabel,
                    onClick = { toggleRow(SettingsRow.ContactSync) },
                )
                SmListRow(
                    label = "앱 업데이트",
                    value = updateRowValue(update.state),
                    onClick = { toggleRow(SettingsRow.Update) },
                    showDivider = false,
                )
            }

            SettingsDetail(visible = expanded == SettingsRow.Quarantine) {
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

            SettingsDetail(visible = expanded == SettingsRow.BlockedSenders) {
                SectionTitle("발신번호 차단")
                Caption("번호 차단은 이 Android 기기에서 수신 단계에 적용됩니다.")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RuleInput(
                        value = newBlockedPhone,
                        onValueChange = { newBlockedPhone = it.take(32) },
                        placeholder = "전화번호",
                        keyboardType = KeyboardType.Phone,
                        onDone = ::addBlockedSender,
                        modifier = Modifier.weight(1f),
                    )
                    RuleAddButton(
                        text = "차단",
                        enabled = SenderRulePattern.matches(
                            PhoneNumberNormalizer.normalize(newBlockedPhone),
                        ),
                        onClick = ::addBlockedSender,
                    )
                }
                if (blockedSenders.isEmpty() && sharedOnlySenders.isEmpty()) {
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
                sharedOnlySenders.forEach { value ->
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

            SettingsDetail(visible = expanded == SettingsRow.ContactSync) {
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

            SettingsDetail(visible = expanded == SettingsRow.Update) {
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
                            checkedThumbColor = Sm.onAccent,
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

// ---------------------------------------------------------------------------
// Header / device security
// ---------------------------------------------------------------------------

/**
 * Pane title row. The root column's 16dp gutter plus 4dp here lands on the
 * 20dp header inset; the 2dp bottom lifts the 12dp card gap to 14dp.
 */
@Composable
private fun SettingsHeader(username: String, deviceName: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "설정",
            color = Sm.text1,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp,
        )
        Text(
            "$username · $deviceName",
            color = Sm.text4,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Card-title chip summarising account trust; null while the state is unknown. */
private fun securityChip(view: DeviceSecurityView): Pair<String, Color>? = when {
    view.selfPending -> "승인 대기" to Sm.warning
    view.securityMode == "legacy_v1" -> "레거시 계정" to Sm.warning
    view.trustWarning != null -> "검증 차단" to Sm.danger
    view.securityMode == "verified_v2" -> "검증된 계정" to Sm.success
    else -> null
}

/** Inset subtitle for the first pending request: "웹 · device-mf5e · 방금 전". */
private fun pendingSummary(device: PendingDeviceApproval): String {
    val kind = when (device.kind) {
        "web" -> "웹"
        "android_gateway" -> "Android"
        else -> device.kind
    }
    return listOfNotNull(kind, device.name, device.requestedAt?.let(::relativeLabel))
        .joinToString(" · ")
}

/** Coarse age from relay unix seconds (the server stamps `int(time.time())`). */
private fun relativeLabel(unixSeconds: Long): String {
    val delta = (System.currentTimeMillis() / 1000 - unixSeconds).coerceAtLeast(0)
    return when {
        delta < 60 -> "방금 전"
        delta < 3_600 -> "${delta / 60}분 전"
        delta < 86_400 -> "${delta / 3_600}시간 전"
        else -> "${delta / 86_400}일 전"
    }
}

/** Footer of the 기기 보안 card: this gateway's own fingerprint and approval state. */
@Composable
private fun ThisDeviceRow(fingerprint: String?, status: String) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Sm.borderSoft, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmIconCircle(
                kind = SmIconKind.Smartphone,
                size = 32.dp,
                tint = Sm.sky,
                background = Sm.accentTint,
                iconSize = 16.dp,
                shape = RoundedCornerShape(10.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "이 기기 · SMS 게이트웨이",
                    color = Sm.text1, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                // Fingerprint is "XXXX XXXX …" hex groups; two groups identify it.
                val lead = fingerprint?.let { "지문 ${it.take(9)} …" } ?: "공개키 확인 불가 ·"
                Text(
                    "$lead $status",
                    color = Sm.text4, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One pending device with its fallback actions. "QR 스캔으로 승인" above is the
 * card's single primary action; approving from here skips the safety-number
 * comparison (legacy v1 signature), so it stays a quiet secondary and says
 * so — never a second gradient button competing with the scan.
 */
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
            SmGhostButton("번호 비교 없이 승인", onApprove, Modifier.weight(1f))
            SmGhostButton("거절", onReject, Modifier.weight(1f), Sm.danger)
        }
    }
}

// ---------------------------------------------------------------------------
// Block rules
// ---------------------------------------------------------------------------

/** Single-line rule entry: 12dp box, strong hairline, Done submits. */
@Composable
private fun RuleInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(shape)
            .background(Sm.surface)
            // borderStrong for the same reason as SmGhostButton: `border` on
            // white is a 1.15:1 hairline and the field edge vanishes.
            .border(1.dp, Sm.borderStrong, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        singleLine = true,
        textStyle = TextStyle(color = Sm.text1, fontSize = 13.sp),
        cursorBrush = SolidColor(Sm.teal),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = Sm.text4, fontSize = 13.sp)
                inner()
            }
        },
    )
}

/** Compact solid button beside [RuleInput] ("추가" / "차단"); matches its height. */
@Composable
private fun RuleAddButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (enabled) Sm.teal else Sm.surfaceAlt)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Sm.onAccent else Sm.text4,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Wrapping chip container; `FlowRow` is still experimental in foundation-layout 1.6. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordChips(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/**
 * One block keyword as a removable chip. `shared` marks rules added on another
 * device; deletion always goes through the relay either way.
 */
@Composable
private fun KeywordChip(value: String, shared: Boolean, onDelete: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Sm.surfaceAlt)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(value, color = Sm.text2, fontSize = 12.sp)
        if (shared) Text("다른 기기", color = Sm.text4, fontSize = 10.sp)
        // 20dp hit box around a 12dp glyph so the chip stays at spec height.
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "$value 삭제",
                tint = Sm.text4,
                modifier = Modifier.size(12.dp),
            )
        }
    }
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

// ---------------------------------------------------------------------------
// Row-list details
// ---------------------------------------------------------------------------

/**
 * Detail card that expands directly under the row-list card. The 12dp top
 * padding is the inter-card gap the parent column deliberately does not add.
 */
@Composable
private fun SettingsDetail(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        SmCard(modifier = Modifier.padding(top = 12.dp), content = content)
    }
}

/** Row value for 연락처 이름 동기화: "오늘 08:12" / "어제 08:12" / "M/d HH:mm". */
private fun syncTimeLabel(lastSyncedAt: Long?): String {
    if (lastSyncedAt == null) return "미동기화"
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(lastSyncedAt).atZone(zone)
    val today = LocalDate.now(zone)
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (at.toLocalDate()) {
        today -> "오늘 $time"
        today.minusDays(1) -> "어제 $time"
        else -> "${at.monthValue}/${at.dayOfMonth} $time"
    }
}

/**
 * Row value for 앱 업데이트. Idle reads as 최신: the auto check runs at launch
 * and every 12h, and a found update moves the state out of Idle.
 */
private fun updateRowValue(state: UpdateUiState): String {
    val suffix = when (state) {
        is UpdateUiState.Idle -> "최신"
        is UpdateUiState.Checking -> "확인 중"
        is UpdateUiState.Failed -> "확인 실패"
        else -> "업데이트 있음"
    }
    return "v${BuildConfig.VERSION_NAME} · $suffix"
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
