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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.AppDatabase
import com.yunjelee.securemsg.BlocklistSync
import com.yunjelee.securemsg.BuildConfig
import com.yunjelee.securemsg.ContactSync
import com.yunjelee.securemsg.ContactSyncStatus
import com.yunjelee.securemsg.Credentials
import com.yunjelee.securemsg.PhoneNumberNormalizer
import com.yunjelee.securemsg.RelayApi
import com.yunjelee.securemsg.ServerConfig
import kotlinx.coroutines.Dispatchers
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
    var newKw by remember { mutableStateOf("") }
    var newBlockedPhone by remember { mutableStateOf("") }
    // Server-side shared block rules cache (rules added on other devices).
    var sharedRules by remember { mutableStateOf(BlocklistSync.load(context)) }
    var contactStatus by remember { mutableStateOf(ContactSync.loadStatus(context)) }
    var contactMessage by remember { mutableStateOf<String?>(null) }
    var contactSyncing by remember { mutableStateOf(false) }

    fun syncContacts() {
        if (contactSyncing) return
        contactSyncing = true
        contactMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                val result = ContactSync.sync(context)
                withContext(Dispatchers.Main) {
                    contactStatus = result
                    contactMessage = "연락처 이름을 기기 내부에 반영했습니다."
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

    LaunchedEffect(Unit) { reloadShared() }

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
            Caption("전화번호와 이름은 이 Android 기기에서만 대조하며 서버로 전송하지 않습니다.")
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
            "대화 ${status.matchedThreadCount}개 일치",
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
