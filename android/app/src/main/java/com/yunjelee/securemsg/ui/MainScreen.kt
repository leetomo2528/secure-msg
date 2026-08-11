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
import com.yunjelee.securemsg.BuildConfig
import com.yunjelee.securemsg.ConversationTarget
import com.yunjelee.securemsg.SavedCredentials

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
            state = update.state,
            onUpdate = update.onUpdate,
            onInstall = update.onInstall,
            onRetry = update.onRetry,
            onCloseInstallBlocked = update.onCloseInstallBlocked,
            onDismiss = update.onDismiss,
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
                onClick = requestPerms,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!notificationPermissionGranted) {
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
                    "알림 권한이 꺼져 있어 새 SMS 알림을 표시할 수 없습니다. SMS 브리지는 계속 사용할 수 있습니다.",
                    color = Sm.warning, fontSize = 12.sp, lineHeight = 17.sp,
                )
                SmGradientButton(
                    text = "알림 권한 요청",
                    onClick = requestNotificationPermission,
                    modifier = Modifier.fillMaxWidth(),
                )
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
