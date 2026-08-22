package com.yunjelee.securemsg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
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
import com.yunjelee.securemsg.SmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// Bottom-nav order. Notification deep links and the pending-approval card
// jump by these, so they live in one place.
private const val SECTION_MESSAGES = 0
private const val SECTION_CONTACTS = 1
private const val SECTION_SETTINGS = 2

private val NAV_ITEMS = listOf(
    SmNavItem("메시지", SmIconKind.Bubble),
    SmNavItem("연락처", SmIconKind.Users),
    SmNavItem("설정", SmIconKind.Gear),
)

/** Post-login home: status header, update banner, bottom nav (messages / contacts / settings). */
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
    // Remembered: the DAO hands out a new Flow per call and collectAsState
    // keys on the instance, so an unremembered one re-runs the query on every
    // recomposition of this screen.
    val threads by remember { db.threadDao().observeAll() }.collectAsState(initial = emptyList())
    var selectedSection by remember { mutableIntStateOf(SECTION_MESSAGES) }
    var pendingApprovalCount by remember { mutableIntStateOf(0) }
    // Owned here so the shell and the pane agree within one frame on whether
    // a chat or the composer is up (see MessagesPaneState). The pane resets
    // it when it leaves composition.
    val messagesState = rememberMessagesPaneState()
    // The address book and the search text survive tab switches.
    val contactsState = rememberContactsPaneState()
    // Pending request for the number-entry composer: FAB, the 연락처 entry
    // card, or a contact that has no thread yet.
    var composeTarget by remember { mutableStateOf<ComposeTarget?>(null) }

    LaunchedEffect(conversationTarget?.requestId, conversationTarget?.cid) {
        if (conversationTarget != null) selectedSection = SECTION_MESSAGES
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
            var authRejected = false
            pendingApprovalCount = withContext(Dispatchers.IO) {
                runCatching {
                    val response = RelayApi(ServerConfig.url(context)).also { it.token = creds.token }.listDevices()
                    if (!response.optBoolean("ok") && response.optInt("_http_status") == 401) {
                        authRejected = true
                        return@runCatching 0
                    }
                    val devices = response.optJSONArray("devices") ?: return@runCatching 0
                    (0 until devices.length()).count { index ->
                        val device = devices.optJSONObject(index)
                        device != null && device.optString("sid") != creds.sid && device.optString("trust_state") == "pending"
                    }
                }.getOrDefault(0)
            }
            if (authRejected) {
                // The session is gone (logout/revocation/expiry). Stop polling the
                // auth API with a dead token; the login screen takes over.
                break
            }
            delay(15_000)
        }
    }

    // Game-style self-update: look for a new release once per 12h window.
    LaunchedEffect(Unit) {
        if (update.shouldAutoCheck) update.onCheck(false)
    }

    // From the 연락처 tab only, so the pane enters composition fresh and the
    // chat is on screen in the same frame as the tab switch.
    fun openThread(thread: SmsThread) {
        messagesState.open(thread)
        selectedSection = SECTION_MESSAGES
    }

    // nanoTime rather than a counter: the composer re-opens per request, and
    // two taps within one frame must still read as two requests.
    fun composeTo(phone: String?) {
        composeTarget = ComposeTarget(phone, System.nanoTime())
        selectedSection = SECTION_MESSAGES
    }

    // UpdateBanner emits nothing for these states. The padding wrapper below
    // would otherwise be an empty child and still claim the column spacing.
    val showsUpdateBanner = when (val state = update.state) {
        UpdateUiState.Idle, UpdateUiState.Checking -> false
        is UpdateUiState.Failed -> state.info != null
        else -> true
    }
    // True while the 메시지 tab shows a conversation or the composer. Gated on
    // the tab so a chat left open behind another tab cannot hide the nav.
    val conversationOpen = selectedSection == SECTION_MESSAGES && messagesState.fullHeightView
    // 연락처/설정 draw their own titled headers and an open chat has
    // SmChatHeader, so the wordmark row belongs to the 메시지 list alone.
    val showsWordmark = selectedSection == SECTION_MESSAGES && !conversationOpen
    // The chat artboard starts with its header; approval, role/permission and
    // update notices wait on the list. The pane explains a greyed send button
    // itself. 설정 already shows the pending request inside 기기 보안.
    val showsNotices = !conversationOpen
    val showsPendingBanner = showsNotices && pendingApprovalCount > 0 && selectedSection != SECTION_SETTINGS

    // Insets: the root takes the sides and the keyboard. The top is taken
    // here only while a list screen is up — in a chat the header runs under
    // the status bar and pads it inside its surface — and the bottom always
    // belongs to whichever surface is last (nav or composer), so the white
    // continues into the gesture area as the artboards draw it.
    // Horizontal padding is owned per child (20dp headers, 16dp cards) so the
    // panes can run their chat header, composer and nav edge to edge.
    Column(
        Modifier
            .fillMaxSize()
            .background(Sm.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .imePadding()
            .then(
                if (conversationOpen) {
                    Modifier
                } else {
                    Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .padding(top = 14.dp)
                },
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showsWordmark) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SecureMsg",
                    style = TextStyle(
                        brush = Sm.brandGradient,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp,
                    ),
                )
                Spacer(Modifier.width(12.dp))
                // Weighted so a long status ("브리지 사용 준비됨 (user@sid)")
                // wraps inside the chip instead of pushing the wordmark off.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    SmChip(
                        status,
                        when {
                            status.startsWith("브리지 사용 준비됨") -> Sm.success
                            status == "연결 확인 중…" -> Sm.sky
                            else -> Sm.warning
                        },
                    )
                }
            }
        }
        if (showsPendingBanner) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Sm.teal.copy(alpha = 0.10f))
                    .border(1.dp, Sm.teal, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "새 기기 승인 요청 ${pendingApprovalCount}건",
                    color = Sm.accentDeep, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                SmGradientButton(
                    text = "기기 보안 열기",
                    onClick = { selectedSection = SECTION_SETTINGS },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (showsNotices && !smsRoleHeld) {
            SmCard(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("기본 SMS 앱 설정이 필요합니다.", color = Sm.warning, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    SmGhostButton(text = "설정", onClick = requestSmsRole)
                }
            }
        }
        if (showsNotices && smsRoleHeld && !smsPermissionsGranted) {
            SmCard(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("SMS 권한이 필요합니다.", color = Sm.warning, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    SmGhostButton(text = "허용", onClick = requestPerms)
                }
            }
        }

        // The updater checks in the background from this screen. Keep the
        // resulting action visible here; without this banner an Available
        // result was silently reduced to the settings-card status text.
        if (showsNotices && showsUpdateBanner) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                UpdateBanner(
                    state = update.state,
                    onUpdate = update.onUpdate,
                    onInstall = update.onInstall,
                    onRetry = update.onRetry,
                    onCloseInstallBlocked = update.onCloseInstallBlocked,
                    onDismiss = update.onDismiss,
                )
            }
        }

        // Pane + nav share one block so the nav sits flush under the pane
        // instead of picking up the column spacing above.
        Column(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize()) {
                    when (selectedSection) {
                        SECTION_MESSAGES -> MessagesPane(
                            state = messagesState,
                            threads = threads,
                            conversationTarget = conversationTarget,
                            // The activity clears its own only on a matching id.
                            onConversationTargetConsumed = onConversationTargetConsumed,
                            smsRoleHeld = smsRoleHeld,
                            smsPermissionsGranted = smsPermissionsGranted,
                            setStatus = setStatus,
                            sendSms = sendSms,
                            composeTarget = composeTarget,
                            onComposeTargetConsumed = { composeTarget = null },
                        )
                        SECTION_CONTACTS -> ContactsPane(
                            state = contactsState,
                            threads = threads,
                            onOpenThread = { openThread(it) },
                            onNewNumber = { composeTo(null) },
                            // No thread yet: the composer takes the number and
                            // the thread appears only after the first send.
                            onStartConversation = { phone, _ -> composeTo(phone) },
                        )
                        else -> SettingsPane(
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
                if (selectedSection == SECTION_MESSAGES && !conversationOpen) {
                    SmFab(
                        onClick = { composeTo(null) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 20.dp),
                    )
                }
            }
            if (!conversationOpen) {
                SmBottomNav(
                    items = NAV_ITEMS,
                    selected = selectedSection,
                    onSelect = { selectedSection = it },
                )
            }
        }
    }
}
