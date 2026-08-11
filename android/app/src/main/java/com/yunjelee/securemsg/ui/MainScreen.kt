package com.yunjelee.securemsg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.AppDatabase
import com.yunjelee.securemsg.ConversationTarget
import com.yunjelee.securemsg.RelayApi
import com.yunjelee.securemsg.SavedCredentials
import com.yunjelee.securemsg.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Post-login home: status header, update banner, tabs (messages / settings). */
@Composable
fun MainScreen(
    creds: SavedCredentials,
    smsRoleHeld: Boolean,
    smsPermissionsGranted: Boolean,
    notificationPermissionGranted: Boolean,
    conversationTarget: ConversationTarget?,
    onConversationTargetConsumed: (String) -> Unit,
    update: UpdateFlow,
    requestSmsRole: () -> Unit,
    requestPerms: () -> Unit,
    requestNotificationPermission: () -> Unit,
    setStatus: (String) -> Unit,
    status: String,
    sendSms: suspend (phone: String, text: String) -> Boolean,
    onLogout: () -> Unit,
    onSimulateSms: () -> Unit,
    onTestUpdateFlow: () -> Unit,
) {
    val context = LocalContext.current
    val db = AppDatabase.get(context)
    val threads by db.threadDao().observeAll().collectAsState(initial = emptyList())
    var selectedSection by remember { mutableIntStateOf(0) }
    var pendingApprovalCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(conversationTarget?.requestId, conversationTarget?.cid) {
        if (conversationTarget != null) selectedSection = 0
    }

    LaunchedEffect(smsRoleHeld, smsPermissionsGranted, creds.sid) {
        if (!smsRoleHeld) {
            setStatus("기본 SMS 앱 설정 필요")
        } else if (!smsPermissionsGranted) {
            setStatus("SMS 권한 필요 — 설정에서 승인하세요")
        } else {
            setStatus("브리지 사용 준비됨 (${creds.username}@${creds.sid})")
        }
    }

    // The Android bridge receives device_pending over Socket.IO, but the
    // service can be running while the user is looking at the message tab.
    // Poll the authoritative device list so a pending web login is visible in
    // the foreground UI instead of being reduced to a logcat entry.
    LaunchedEffect(creds.sid) {
        while (isActive) {
            pendingApprovalCount = withContext(Dispatchers.IO) {
                runCatching {
                    val response = RelayApi(ServerConfig.url(context)).also { it.token = creds.token }.listDevices()
                    val devices = response.optJSONArray("devices") ?: return@runCatching 0
                    (0 until devices.length()).count { index ->
                        val device = devices.optJSONObject(index)
                        device != null && device.optString("sid") != creds.sid && device.optString("trust_state") == "pending"
                    }
                }.getOrDefault(0)
            }
            delay(5_000)
        }
    }

    // Game-style self-update: look for a new release once per 12h window.
    LaunchedEffect(Unit) {
        if (update.shouldAutoCheck) update.onCheck(false)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Sm.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
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
        }
        SmChip(
            status,
            when {
                status.startsWith("브리지 사용 준비됨") -> Sm.teal
                status == "연결 확인 중…" -> Sm.sky
                else -> Sm.warning
            },
        )
        if (pendingApprovalCount > 0) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Sm.teal.copy(alpha = 0.10f))
                    .border(1.dp, Sm.teal.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "새 기기 승인 요청 ${pendingApprovalCount}건",
                    color = Sm.teal, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                SmGradientButton(
                    text = "기기 보안 열기",
                    onClick = { selectedSection = 1 },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!smsRoleHeld) {
            SmCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("기본 SMS 앱 설정이 필요합니다.", color = Sm.warning, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    SmGhostButton(text = "설정", onClick = requestSmsRole)
                }
            }
        }
        if (smsRoleHeld && !smsPermissionsGranted) {
            SmCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("SMS 권한이 필요합니다.", color = Sm.warning, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    SmGhostButton(text = "허용", onClick = requestPerms)
                }
            }
        }

        SmTabs(
            selected = selectedSection,
            labels = listOf("메시지", "차단·설정"),
            onSelect = { selectedSection = it },
        )

        if (selectedSection == 0) {
            MessagesPane(
                threads = threads,
                conversationTarget = conversationTarget,
                onConversationTargetConsumed = onConversationTargetConsumed,
                smsRoleHeld = smsRoleHeld,
                smsPermissionsGranted = smsPermissionsGranted,
                setStatus = setStatus,
                sendSms = sendSms,
            )
        } else {
            Column(Modifier.fillMaxWidth().weight(1f)) {
                    SettingsPane(
                        creds = creds,
                        update = update,
                        notificationPermissionGranted = notificationPermissionGranted,
                        onRequestNotificationPermission = requestNotificationPermission,
                    onLogout = onLogout,
                    onSimulateSms = onSimulateSms,
                    onTestUpdateFlow = onTestUpdateFlow,
                )
            }
        }
    }
}
