package com.yunjelee.securemsg.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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

// The shell gutter, plus each item's own gap to whatever follows it — a
// header with nothing to report is exactly the inset (see ShellHeader).
private val ShellItemPadding = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)

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
    val durations = rememberSmDurations()
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

    // The notification that was already waiting when this screen first
    // composed: a cold start, where the conversation IS the app's first frame.
    // Latched here rather than inside the pane, because the pane is recreated
    // on every tab switch and the effect below selects 메시지 before it
    // composes — a pane-local latch would read a notification tapped from
    // 연락처/설정 as a cold start too and delete that transition as well.
    val coldStartRequestId = remember { conversationTarget?.requestId }

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

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The Android bridge receives device_pending over Socket.IO, but the
    // service can be running while the user is looking at the message tab.
    // Poll the authoritative device list so a pending web login is visible in
    // the foreground UI instead of being reduced to a logcat entry.
    //
    // Gated on the lifecycle, not on the composition: the composition outlives
    // onStop, so the loop kept an authenticated GET going every 15s with the
    // screen off, for a banner only ShellHeader draws. repeatOnLifecycle also
    // re-polls at onStart, which is when a stale count most needs refreshing.
    LaunchedEffect(creds.sid, lifecycle) {
        var authRejected = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive && !authRejected) {
                val polled: Int? = withContext(Dispatchers.IO) {
                    runCatching {
                        val response = RelayApi(ServerConfig.url(context)).also { it.token = creds.token }.listDevices()
                        if (!response.optBoolean("ok") && response.optInt("_http_status") == 401) {
                            authRejected = true
                            return@runCatching 0
                        }
                        // null, not 0: a poll that failed knows nothing about
                        // pending devices, and reporting 0 made the approval
                        // banner flap with connectivity.
                        val devices = response.optJSONArray("devices") ?: return@runCatching null
                        (0 until devices.length()).count { index ->
                            val device = devices.optJSONObject(index)
                            device != null && device.optString("sid") != creds.sid && device.optString("trust_state") == "pending"
                        }
                    }.getOrNull()
                }
                polled?.let { pendingApprovalCount = it }
                if (authRejected) {
                    // The session is gone (logout/revocation/expiry). Stop polling the
                    // auth API with a dead token; the login screen takes over.
                    break
                }
                delay(15_000)
            }
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

    // True while the 메시지 tab shows a conversation or the composer. Gated on
    // the tab so a chat left open behind another tab cannot hide the nav.
    val conversationOpen = selectedSection == SECTION_MESSAGES && messagesState.fullHeightView
    // Zero while the shell is showing a conversation it never animated into —
    // a notification tapped before this screen had drawn anything. The nav and
    // the FAB then simply are not there, rather than sliding off a list the
    // user was never shown; the pane makes the same exemption for the chat.
    val chromeMs = if (messagesState.openedWithoutMotion) 0 else durations.chromeMs

    // Insets: the root takes the sides and the keyboard. The top belongs to
    // whichever surface draws the topmost pixel — ShellHeader on the three
    // list screens, SmChatHeader/ComposeHeader inside a chat and the composer
    // — so a surface that is sliding out carries its status-bar padding with
    // it instead of having the shell pull the padding away underneath. The
    // bottom always belongs to whichever surface is last (nav or composer), so
    // the white continues into the gesture area as the artboards draw it.
    // Horizontal padding is owned per child (20dp headers, 16dp cards) so the
    // panes can run their chat header, composer and nav edge to edge.
    Column(
        Modifier
            .fillMaxSize()
            .background(Sm.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .imePadding(),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // A crossfade would say nothing about where 메시지/연락처/설정 sit
            // relative to each other; a full-width push would be far too
            // much for a control that gets tapped all day. This is the
            // middle: a fade with a slide of a twelfth of the width, keyed
            // on the nav order so the pane moves the way the finger did,
            // over half the conversation's duration.
            AnimatedContent(
                targetState = selectedSection,
                transitionSpec = {
                    val direction = SmMotion.slideDirection(initialState, targetState)
                    val slide = tween<IntOffset>(durations.tabMs, easing = SmMotion.Standard)
                    // The same sideways slide as the conversation, only quicker:
                    // one full-width travel in the direction the finger moved
                    // along the nav. A fade on top of it just blurred the edge.
                    slideInHorizontally(slide) { it * direction } togetherWith
                        slideOutHorizontally(slide) { -it * direction }
                },
                modifier = Modifier.fillMaxSize(),
                label = "section",
            ) { section ->
                Column(Modifier.fillMaxSize()) {
                    when (section) {
                        SECTION_MESSAGES -> MessagesPane(
                            state = messagesState,
                            threads = threads,
                            conversationTarget = conversationTarget,
                            coldStartRequestId = coldStartRequestId,
                            // The activity clears its own only on a matching id.
                            onConversationTargetConsumed = onConversationTargetConsumed,
                            smsRoleHeld = smsRoleHeld,
                            smsPermissionsGranted = smsPermissionsGranted,
                            setStatus = setStatus,
                            sendSms = sendSms,
                            composeTarget = composeTarget,
                            onComposeTargetConsumed = { composeTarget = null },
                            // Handed to the pane instead of being drawn above
                            // it: the thread list is the only 메시지 surface
                            // without a header of its own, and living inside
                            // the pane's own AnimatedContent is what lets the
                            // list carry this off screen — drawn out here it
                            // would be deleted at t=0 and drop the list a
                            // header's worth of pixels before it even moved.
                            listHeader = {
                                ShellHeader(
                                    showsWordmark = true,
                                    status = status,
                                    pendingApprovalCount = pendingApprovalCount,
                                    onOpenDeviceSecurity = { selectedSection = SECTION_SETTINGS },
                                    smsRoleHeld = smsRoleHeld,
                                    smsPermissionsGranted = smsPermissionsGranted,
                                    requestSmsRole = requestSmsRole,
                                    requestPerms = requestPerms,
                                    update = update,
                                )
                            },
                        )
                        SECTION_CONTACTS -> {
                            ShellHeader(
                                showsWordmark = false,
                                status = status,
                                pendingApprovalCount = pendingApprovalCount,
                                onOpenDeviceSecurity = { selectedSection = SECTION_SETTINGS },
                                smsRoleHeld = smsRoleHeld,
                                smsPermissionsGranted = smsPermissionsGranted,
                                requestSmsRole = requestSmsRole,
                                requestPerms = requestPerms,
                                update = update,
                            )
                            ContactsPane(
                                state = contactsState,
                                threads = threads,
                                onOpenThread = { openThread(it) },
                                onNewNumber = { composeTo(null) },
                                // No thread yet: the composer takes the number and
                                // the thread appears only after the first send.
                                onStartConversation = { phone, _ -> composeTo(phone) },
                            )
                        }
                        else -> {
                            // 설정 already shows the pending request inside 기기 보안,
                            // so the banner would only repeat what is on screen.
                            ShellHeader(
                                showsWordmark = false,
                                status = status,
                                pendingApprovalCount = 0,
                                onOpenDeviceSecurity = {},
                                smsRoleHeld = smsRoleHeld,
                                smsPermissionsGranted = smsPermissionsGranted,
                                requestSmsRole = requestSmsRole,
                                requestPerms = requestPerms,
                                update = update,
                            )
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
            FabSlot(
                visible = selectedSection == SECTION_MESSAGES && !conversationOpen,
                durationMs = chromeMs,
                onClick = { composeTo(null) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 20.dp),
            )
        }
        // Collapses its own height on the way out, so the chat's composer
        // rides down into the space as the bar leaves it. A bare slide
        // would keep the height reserved and show the page through it.
        AnimatedVisibility(
            visible = !conversationOpen,
            enter = expandVertically(tween(chromeMs, easing = SmMotion.Enter)) +
                fadeIn(tween(chromeMs, easing = SmMotion.Standard)),
            exit = shrinkVertically(tween(chromeMs, easing = SmMotion.Exit)) +
                fadeOut(tween(chromeMs, easing = SmMotion.Standard)),
        ) {
            SmBottomNav(
                items = NAV_ITEMS,
                selected = selectedSection,
                onSelect = { selectedSection = it },
            )
        }
    }
}

/**
 * The status/wordmark row and the shell-wide notices, drawn by each list
 * screen rather than by the shell above them.
 *
 * It owns the top inset for the screen that draws it, so a pane leaving under
 * a transition keeps its status-bar padding for as long as it is on screen —
 * and a chat, which pads the inset inside its own header, simply never draws
 * this. Each item carries its own 10dp gap to whatever follows instead of the
 * column spacing it, so a header with nothing to report is exactly the inset
 * and the pane beneath it does not move.
 *
 * [pendingApprovalCount] of 0 hides the approval card; [showsWordmark] is for
 * the 메시지 list alone, since 연락처/설정 draw their own titled headers.
 */
@Composable
private fun ShellHeader(
    showsWordmark: Boolean,
    status: String,
    pendingApprovalCount: Int,
    onOpenDeviceSecurity: () -> Unit,
    smsRoleHeld: Boolean,
    smsPermissionsGranted: Boolean,
    requestSmsRole: () -> Unit,
    requestPerms: () -> Unit,
    update: UpdateFlow,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(top = 14.dp),
    ) {
        if (showsWordmark) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
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
        if (pendingApprovalCount > 0) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(ShellItemPadding)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Sm.accent.copy(alpha = 0.10f))
                    .border(1.dp, Sm.accent, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "새 기기 승인 요청 ${pendingApprovalCount}건",
                    color = Sm.accentDeep, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                SmGradientButton(
                    text = "기기 보안 열기",
                    onClick = onOpenDeviceSecurity,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!smsRoleHeld) {
            NoticeCard("기본 SMS 앱 설정이 필요합니다.", action = "설정", onClick = requestSmsRole)
        }
        // Only ever one of the two: the role has to be held before the runtime
        // permissions can be asked for.
        if (smsRoleHeld && !smsPermissionsGranted) {
            NoticeCard("SMS 권한이 필요합니다.", action = "허용", onClick = requestPerms)
        }

        // The updater checks in the background from this screen. Keep the
        // resulting action visible here; without this banner an Available
        // result was silently reduced to the settings-card status text.
        //
        // The wrapper claims its gap even around a banner that draws nothing,
        // so it asks the banner instead of re-deriving the rule from the state.
        if (update.state.rendersBanner) {
            Box(ShellItemPadding) {
                UpdateBanner(
                    state = update.state,
                    onUpdate = update.onUpdate,
                    onInstall = update.onInstall,
                    onRetry = update.onRetry,
                    onCancelInstall = update.onCancelInstall,
                    onCloseInstallBlocked = update.onCloseInstallBlocked,
                    onDismiss = update.onDismiss,
                )
            }
        }
    }
}

/**
 * One shell notice: a sentence and the button that resolves it. The SMS-role
 * and SMS-permission cards were the same eight lines three values apart.
 */
@Composable
private fun NoticeCard(text: String, action: String, onClick: () -> Unit) {
    SmCard(ShellItemPadding) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Sm.warning, fontSize = 12.sp, modifier = Modifier.weight(1f))
            SmGhostButton(text = action, onClick = onClick)
        }
    }
}

/**
 * The 메시지 FAB, arriving and leaving with the list it belongs to — it scales
 * out of the corner it sits in rather than blinking, because a conversation
 * takes the whole screen away from it.
 *
 * The button stops taking taps the frame the exit starts: it is drawn above
 * the section content and stays composed for the whole 180ms, long enough to
 * cover a 연락처 row or a chat's send button and answer a tap meant for them.
 *
 * Its own composable on purpose: at the call site both the shell's BoxScope
 * and the ColumnScope around it are implicit receivers, and AnimatedVisibility
 * has a ColumnScope overload that then claims the call it must not have.
 */
@Composable
private fun FabSlot(
    visible: Boolean,
    durationMs: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(durationMs, easing = SmMotion.Standard)) +
            scaleIn(tween(durationMs, easing = SmMotion.Enter), initialScale = 0.7f),
        exit = fadeOut(tween(durationMs, easing = SmMotion.Standard)) +
            scaleOut(tween(durationMs, easing = SmMotion.Exit), targetScale = 0.7f),
    ) {
        SmFab(onClick = onClick, enabled = visible)
    }
}
