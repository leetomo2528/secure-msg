package com.yunjelee.securemsg.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.AppDatabase
import com.yunjelee.securemsg.ConversationTarget
import com.yunjelee.securemsg.ConversationTargetResolver
import com.yunjelee.securemsg.MessageRow
import com.yunjelee.securemsg.MessageSearch
import com.yunjelee.securemsg.PhoneNumberNormalizer
import com.yunjelee.securemsg.SmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Request to open the number-entry composer. [phone] prefills the recipient
 * (null = empty field, as from the FAB); [requestId] tells repeated requests
 * for the same number apart so each one re-opens the composer.
 */
data class ComposeTarget(val phone: String?, val requestId: Long)

/**
 * The slice of the 메시지 tab's state the shell reads while composing: whether
 * a conversation or the composer is up decides the wordmark, FAB and bottom
 * nav for the SAME frame, so MainScreen owns it instead of having the pane
 * report upward from an effect (which left the shell one frame behind and
 * made every open/close jump). Everything else the pane needs — drafts,
 * search text — stays remembered inside it.
 */
@Stable
class MessagesPaneState {
    var selectedThread by mutableStateOf<SmsThread?>(null)
    var composing by mutableStateOf(false)

    /** True while a conversation or the composer is showing. */
    val fullHeightView: Boolean get() = selectedThread != null || composing

    /**
     * Open [thread] straight away. Meant for callers outside the pane (the
     * 연락처 tab): the pane enters composition fresh, so its drafts and search
     * fields are already clear.
     */
    fun open(thread: SmsThread) {
        selectedThread = thread
        composing = false
    }

    fun reset() {
        selectedThread = null
        composing = false
    }
}

@Composable
fun rememberMessagesPaneState(): MessagesPaneState = remember { MessagesPaneState() }

/** Feedback line above the composer: the last send's outcome. */
private data class SendNotice(val text: String, val failed: Boolean)

/**
 * "메시지" tab: thread list, the number-entry composer, and the open
 * conversation — one of the three is on screen at a time.
 *
 * [state] is reset when the pane leaves composition, so a tab switch mid-chat
 * returns to the list the way it always has and cannot strand the shell with
 * its nav hidden.
 */
@Composable
fun ColumnScope.MessagesPane(
    state: MessagesPaneState,
    threads: List<SmsThread>,
    conversationTarget: ConversationTarget?,
    onConversationTargetConsumed: (String) -> Unit,
    smsRoleHeld: Boolean,
    smsPermissionsGranted: Boolean,
    setStatus: (String) -> Unit,
    sendSms: suspend (phone: String, text: String) -> Boolean,
    composeTarget: ComposeTarget? = null,
    onComposeTargetConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val db = AppDatabase.get(context)
    val scope = rememberCoroutineScope()
    var selectedThread by state::selectedThread
    var composing by state::composing
    var threadSearchQuery by remember { mutableStateOf("") }
    var messageSearchVisible by remember { mutableStateOf(false) }
    var messageSearchQuery by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var newMsg by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    // Shown above the composer where the send happened. The shell's status
    // chip is hidden while a chat or the composer is up, so setStatus alone
    // would leave a failed send with nothing but a stopped spinner.
    var sendNotice by remember { mutableStateOf<SendNotice?>(null) }
    // Normalized number of a composer send whose thread has not shown up in
    // [threads] yet; the dispatcher upserts it before returning, so the next
    // Room emission opens it. Set on failure too: the dispatcher records the
    // failed row in that thread, and leaving the composer up would only breed
    // duplicate failed rows per retry.
    var openAfterSend by remember { mutableStateOf<String?>(null) }
    val selectedMessageFlow = remember(selectedThread?.cid) {
        selectedThread?.let { db.messageDao().observeForCid(it.cid) } ?: flowOf(emptyList())
    }
    val selectedMessages by selectedMessageFlow.collectAsState(initial = emptyList())
    val latestMessages by remember { db.messageDao().observeLatestPerCid() }
        .collectAsState(initial = emptyList())
    val latestByCid = remember(latestMessages) { latestMessages.associateBy { it.cid } }
    // In-memory mirror of the last-opened prefs so rows recompose the moment a
    // thread is read instead of re-reading the file per row.
    val lastOpened = remember {
        mutableStateMapOf<String, Long>().apply { putAll(LastOpened.all(context)) }
    }
    val visibleThreads = remember(threads, threadSearchQuery) {
        MessageSearch.filterThreads(threads, threadSearchQuery)
    }
    val visibleMessages = remember(selectedMessages, messageSearchQuery) {
        MessageSearch.filterMessages(selectedMessages, messageSearchQuery)
    }
    // Re-read the wall clock whenever the data it labels changes, so "오늘"
    // cannot stay pinned across midnight for long.
    val clock = remember(threads, selectedMessages) { DayClock() }

    fun closeConversation() {
        selectedThread = null
        reply = ""
        sendNotice = null
        messageSearchQuery = ""
        messageSearchVisible = false
    }

    // A notification can arrive before Room's thread Flow emits (cold process),
    // or its local cid can have been replaced by the authoritative relay cid.
    // Wait for either exact cid or canonical phone, then consume exactly once.
    LaunchedEffect(
        conversationTarget?.requestId,
        conversationTarget?.cid,
        conversationTarget?.normalizedPhone,
        threads,
    ) {
        val target = conversationTarget ?: return@LaunchedEffect
        val resolved = ConversationTargetResolver.resolve(threads, target)
            ?: return@LaunchedEffect
        selectedThread = resolved
        composing = false
        openAfterSend = null
        threadSearchQuery = ""
        messageSearchQuery = ""
        messageSearchVisible = false
        reply = ""
        sendNotice = null
        onConversationTargetConsumed(target.requestId)
    }

    // FAB, "번호로 새 문자", or a contact without a thread. A number that
    // already has a thread opens it instead: the dispatcher would reuse that
    // thread on send anyway, so an empty composer for it only misleads.
    LaunchedEffect(composeTarget?.requestId) {
        val target = composeTarget ?: return@LaunchedEffect
        val existing = target.phone?.let { phone ->
            threads.firstOrNull { samePhone(it.phoneNumber, phone) }
        }
        if (existing != null) {
            selectedThread = existing
            composing = false
        } else {
            selectedThread = null
            newPhone = target.phone.orEmpty()
            newMsg = ""
            composing = true
        }
        openAfterSend = null
        reply = ""
        sendNotice = null
        messageSearchQuery = ""
        messageSearchVisible = false
        onComposeTargetConsumed()
    }

    // First send from the composer: move into the thread as soon as it
    // exists. The composer stays up until then, so the list never flashes in
    // between. A send the dispatcher rejected before creating the thread (bad
    // number) finds nothing here and the composer simply stays, notice shown.
    LaunchedEffect(threads, openAfterSend) {
        val phone = openAfterSend ?: return@LaunchedEffect
        val thread = threads.firstOrNull { PhoneNumberNormalizer.normalize(it.phoneNumber) == phone }
            ?: return@LaunchedEffect
        openAfterSend = null
        composing = false
        newPhone = ""
        selectedThread = thread
    }

    // Offline sends start in a provisional local thread. Once the relay
    // reconnects it atomically swaps that cid for the server cid; keep the
    // open conversation selected across that migration.
    LaunchedEffect(threads, selectedThread?.cid) {
        val current = selectedThread ?: return@LaunchedEffect
        selectedThread = threads.firstOrNull { it.cid == current.cid }
            ?: threads.firstOrNull { samePhone(it.phoneNumber, current.phoneNumber) }
    }

    // Opening a thread reads it. Keyed on lastActivityAt too, so an arrival,
    // carrier update or background `touch` while it stays open cannot re-flag
    // it. max() with the clock covers a relay timestamp ahead of this phone.
    LaunchedEffect(selectedThread?.cid, selectedThread?.lastActivityAt) {
        val thread = selectedThread ?: return@LaunchedEffect
        val at = maxOf(System.currentTimeMillis(), thread.lastActivityAt)
        lastOpened[thread.cid] = at
        LastOpened.set(context, thread.cid, at)
    }

    val fullHeightView = state.fullHeightView
    DisposableEffect(state) { onDispose { state.reset() } }

    // Live only while there is somewhere to go back to; on the list the
    // system back must fall through to the activity as before.
    BackHandler(enabled = fullHeightView) {
        if (selectedThread != null) {
            closeConversation()
        } else {
            composing = false
            openAfterSend = null
            sendNotice = null
        }
    }

    val canSend = !sending && smsRoleHeld && smsPermissionsGranted
    // The role/permission cards live on the list, which a chat covers; say
    // here why the send button is grey instead of leaving it mute.
    val notice = sendNotice
    val composerNotice: Pair<String, Color>? = when {
        notice != null -> notice.text to (if (notice.failed) Sm.danger else Sm.text4)
        !smsRoleHeld -> "기본 SMS 앱으로 설정해야 보낼 수 있습니다." to Sm.warning
        !smsPermissionsGranted -> "SMS 권한이 필요합니다 — 설정에서 승인하세요." to Sm.warning
        else -> null
    }

    Column(Modifier.fillMaxWidth().weight(1f)) {
        val thread = selectedThread
        when {
            thread != null -> {
                val conversationListState = rememberLazyListState()
                var followsLatest by remember(thread.cid) { mutableStateOf(true) }
                val chatRows = remember(visibleMessages, clock) { buildChatRows(visibleMessages, clock) }

                // Only user scrolling changes follow mode. A Room emission can
                // shift keyed rows when a new message is inserted at index 0; it
                // must not accidentally make an at-bottom user look scrolled up.
                // Index 0 is still the newest MESSAGE: date pills follow the
                // oldest row of their day, never precede the newest one.
                LaunchedEffect(conversationListState, thread.cid) {
                    snapshotFlow { conversationListState.isScrollInProgress }
                        .distinctUntilChanged()
                        .collect { scrolling ->
                            if (scrolling) {
                                // Stop follow mode as soon as a drag/fling starts so an arrival during
                                // the gesture cannot pull the reader back to the latest message.
                                followsLatest = false
                            } else {
                                followsLatest =
                                    conversationListState.firstVisibleItemIndex == 0 &&
                                    conversationListState.firstVisibleItemScrollOffset == 0
                            }
                        }
                }

                // Opening/switching a conversation starts at its newest message.
                // Continue following arrivals only while the user is already at
                // the latest position; reading older history is never interrupted.
                LaunchedEffect(
                    visibleMessages.firstOrNull()?.id,
                ) {
                    if (
                        followsLatest &&
                        !conversationListState.isScrollInProgress &&
                        visibleMessages.isNotEmpty()
                    ) {
                        conversationListState.scrollToItem(0)
                    }
                }

                // A new/cleared search is a new result set, so begin at its latest
                // match. Subsequent arrivals still respect the user's scroll mode.
                LaunchedEffect(thread.cid, messageSearchQuery) {
                    followsLatest = true
                    if (visibleMessages.isNotEmpty()) {
                        conversationListState.scrollToItem(0)
                    }
                }

                SmChatHeader(
                    name = thread.displayName,
                    subtitle = "SMS · ${thread.phoneNumber}",
                    onBack = { closeConversation() },
                    onSearch = {
                        messageSearchVisible = !messageSearchVisible
                        if (!messageSearchVisible) messageSearchQuery = ""
                    },
                )
                if (messageSearchVisible) {
                    SmSearchPill(
                        query = messageSearchQuery,
                        onQueryChange = { messageSearchQuery = it.take(200) },
                        placeholder = "이 대화에서 검색",
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    )
                    if (messageSearchQuery.isNotBlank() && visibleMessages.isEmpty()) {
                        Text(
                            "일치하는 메시지나 제목이 없습니다.",
                            color = Sm.text4,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = conversationListState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                ) {
                    items(chatRows, key = { it.key }, contentType = { it::class }) { row ->
                        when (row) {
                            is ChatRow.DayPill -> SmDatePill(row.label)
                            is ChatRow.Message -> {
                                val message = row.message
                                ChatBubble(
                                    mine = message.mine,
                                    blocked = message.blocked,
                                    text = if (message.blocked) "차단된 메시지" else message.plaintext,
                                    statusLine = if (message.blocked) {
                                        null
                                    } else {
                                        clock.clockTime(message.createdAt) + carrierStatusLabel(message.carrierStatus)
                                    },
                                )
                            }
                        }
                    }
                }
                composerNotice?.let { (text, color) -> ComposerNotice(text, color) }
                SmComposer(
                    value = reply,
                    onValueChange = {
                        reply = it.take(20_000)
                        sendNotice = null
                    },
                    placeholder = "메시지 입력",
                    canSend = canSend,
                    sending = sending,
                    onSend = {
                        val text = reply.trim()
                        if (text.isBlank() || sending) return@SmComposer
                        sending = true
                        sendNotice = null
                        scope.launch(Dispatchers.IO) {
                            val sent = sendSms(thread.phoneNumber, text)
                            withContext(Dispatchers.Main) {
                                if (sent) {
                                    reply = ""
                                } else {
                                    sendNotice = SendNotice(SEND_FAILED, failed = true)
                                    setStatus(SEND_FAILED)
                                }
                                sending = false
                            }
                        }
                    },
                )
            }
            composing -> {
                val recipientFocus = remember { FocusRequester() }
                val messageFocus = remember { FocusRequester() }
                // An empty recipient (FAB) starts in the number field; a
                // prefilled one (contact) goes straight to the message.
                // SmComposer owns its text field, so the requester sits on
                // its wrapper and resolves to the first focusable descendant.
                LaunchedEffect(Unit) {
                    if (newPhone.isBlank()) recipientFocus.requestFocus() else messageFocus.requestFocus()
                }

                ComposeHeader(
                    onBack = {
                        composing = false
                        openAfterSend = null
                    },
                )
                RecipientField(
                    value = newPhone,
                    onValueChange = { newPhone = it.take(32) },
                    focusRequester = recipientFocus,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "첫 메시지를 보내면 대화가 시작됩니다.",
                        color = Sm.text4,
                        fontSize = 12.sp,
                    )
                }
                composerNotice?.let { (text, color) -> ComposerNotice(text, color) }
                SmComposer(
                    value = newMsg,
                    onValueChange = {
                        newMsg = it.take(20_000)
                        sendNotice = null
                    },
                    placeholder = "메시지 입력",
                    canSend = canSend && newPhone.isNotBlank(),
                    sending = sending,
                    onSend = {
                        val phone = newPhone.trim()
                        val text = newMsg.trim()
                        if (sending || phone.isBlank() || text.isBlank()) return@SmComposer
                        sending = true
                        sendNotice = null
                        scope.launch(Dispatchers.IO) {
                            val sent = sendSms(phone, text)
                            withContext(Dispatchers.Main) {
                                // Either way the thread (if the dispatcher got as
                                // far as creating it) is where the result shows:
                                // a queued bubble or a failed one.
                                openAfterSend = PhoneNumberNormalizer.normalize(phone)
                                if (sent) {
                                    newMsg = ""
                                    sendNotice = SendNotice(SEND_QUEUED, failed = false)
                                    setStatus(SEND_QUEUED)
                                } else {
                                    sendNotice = SendNotice(SEND_FAILED, failed = true)
                                    setStatus(SEND_FAILED)
                                }
                                sending = false
                            }
                        }
                    },
                    modifier = Modifier.focusRequester(messageFocus),
                )
            }
            else -> {
                SmSearchPill(
                    query = threadSearchQuery,
                    onQueryChange = { threadSearchQuery = it.take(200) },
                    // Filters on name/number only (MessageSearch.filterThreads);
                    // the artboard's "대화·메시지 검색" would promise body search.
                    placeholder = "대화 상대·번호 검색",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                )
                if (threadSearchQuery.isNotBlank()) {
                    Text(
                        "검색 결과 ${visibleThreads.size}/${threads.size}",
                        color = Sm.text4,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp),
                    )
                }
                if (threads.isEmpty()) {
                    EmptyNote("아직 표시할 문자가 없습니다.")
                } else if (visibleThreads.isEmpty()) {
                    EmptyNote("일치하는 대화 상대나 전화번호가 없습니다.")
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    // Bottom inset clears the 56dp FAB the shell floats 20dp
                    // off the corner, so the last row can scroll out from under it.
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visibleThreads, key = { it.cid }) { item ->
                        val latest = latestByCid[item.cid]
                        val since = lastOpened[item.cid] ?: 0L
                        val unread = latest != null && !latest.mine && item.lastActivityAt > since
                        // One count query per unread row on screen, re-run on
                        // the next arrival (latest id) or when the thread is
                        // read (since). The threshold is per thread, so this
                        // cannot fold into the single latest-per-cid query.
                        val unreadCount by produceState(0, item.cid, since, latest?.id, unread) {
                            value = if (unread) db.messageDao().countIncomingSince(item.cid, since) else 0
                        }
                        SmConversationRow(
                            name = item.displayName,
                            subtitle = snippet(item, latest),
                            time = clock.listTime(item.lastActivityAt),
                            unread = unread,
                            // Floor of 1 while the count is still loading, or
                            // when only lastActivityAt moved past the stamp
                            // (relay-timestamped arrival): the row is already
                            // bold, so the badge must not vanish under it.
                            unreadCount = unreadCount.coerceAtLeast(1),
                            showPersonIcon = !item.showsPhoneSubtitle,
                            onClick = {
                                selectedThread = item
                                messageSearchQuery = ""
                                messageSearchVisible = false
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Composer (number entry)
// ---------------------------------------------------------------------------

/**
 * Composer header: back + title. [SmChatHeader] minus the avatar and search it
 * has no use for. Like that header it runs under the status bar and pads the
 * inset inside its own surface; the shell drops its top inset while we are up.
 */
@Composable
private fun ComposeHeader(onBack: () -> Unit) {
    val shadow = Sm.ink.copy(alpha = 0.10f)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(2.dp, ambientColor = shadow, spotColor = shadow)
            .background(Sm.surface.copy(alpha = 0.88f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.semantics { contentDescription = "뒤로" }) {
                SmIconCircle(
                    kind = SmIconKind.ChevronLeft,
                    size = 36.dp,
                    tint = Sm.text3,
                    background = Color.Transparent,
                    iconSize = 20.dp,
                    strokeWidth = 2.dp,
                    onClick = onBack,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("새 메시지", color = Sm.text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("연락처에 없는 번호로 바로 보내기", color = Sm.text4, fontSize = 11.sp)
            }
        }
        Hairline(Sm.ink.copy(alpha = 0.08f))
    }
}

/** Recipient number in the composer's pill style, on the phone keypad. */
@Composable
private fun RecipientField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val pill = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(pill)
            .background(Sm.surfaceAlt)
            .border(1.dp, Sm.ink.copy(alpha = 0.08f), pill)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("받는 사람", color = Sm.text4, fontSize = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = Sm.text1,
                fontSize = 14.sp,
                fontFeatureSettings = "tnum",
            ),
            cursorBrush = SolidColor(Sm.teal),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("전화번호", color = Sm.text3, fontSize = 13.sp)
                    inner()
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// List helpers
// ---------------------------------------------------------------------------

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        color = Sm.text4,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
    )
}

private const val SEND_FAILED = "SMS 발송 실패 — 번호·권한·메시지 길이를 확인하세요."
private const val SEND_QUEUED = "SMS를 발송했고 동기화 대기열에 저장했습니다."

/** One line directly above [SmComposer]: a send result, or why sending is off. */
@Composable
private fun ComposerNotice(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
    )
}

/** 1dp separator; same idea as Theme's private one, kept file-local. */
@Composable
private fun Hairline(color: Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

private fun samePhone(a: String, b: String): Boolean =
    PhoneNumberNormalizer.normalize(a) == PhoneNumberNormalizer.normalize(b)

/** Row subtitle: the newest message on one line, or the number / "SMS" until one exists. */
private fun snippet(thread: SmsThread, latest: MessageRow?): String = when {
    latest == null -> if (thread.showsPhoneSubtitle) thread.phoneNumber else "SMS"
    latest.blocked -> "차단된 메시지"
    else -> latest.plaintext
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .ifEmpty { latest.subject?.takeIf { it.isNotBlank() } ?: "(내용 없음)" }
}

/**
 * On-device "last opened" time per thread (prefs `thread_last_opened`, key =
 * cid). The schema has no unread column and the relay no read state, so this
 * is what "unread" is measured against. Not synced, not migrated.
 */
internal object LastOpened {
    private const val PREFS = "thread_last_opened"

    /** Forget-device path: stamps belong to the account being removed. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun all(context: Context): Map<String, Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (cid, at) -> (at as? Long)?.let { cid to it } }
            .toMap()

    fun set(context: Context, cid: String, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(cid, at).apply()
    }
}

// ---------------------------------------------------------------------------
// Chat helpers
// ---------------------------------------------------------------------------

/** One entry of the reversed chat list. Keys are prefixed so a pill can never collide with a message id. */
private sealed class ChatRow(val key: String) {
    class Message(val message: MessageRow) : ChatRow("m:${message.id}")
    class DayPill(val label: String, day: Long) : ChatRow("d:$day")
}

/**
 * Newest-first messages with a date pill AFTER the oldest message of each
 * day — "after" in list order is "above" on screen because the list is
 * reversed. Index 0 therefore stays the newest message, which the scroll
 * follow logic depends on.
 */
private fun buildChatRows(messages: List<MessageRow>, clock: DayClock): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 8)
    messages.forEachIndexed { i, message ->
        rows += ChatRow.Message(message)
        val day = clock.dayOf(message.createdAt)
        val older = messages.getOrNull(i + 1)
        if (older == null || clock.dayOf(older.createdAt) != day) {
            rows += ChatRow.DayPill(clock.pillLabel(message.createdAt), day)
        }
    }
    return rows
}

/**
 * Wall-clock labels for the list's time column and the chat's date pills.
 * Cheap to build; callers re-create one when the data it labels changes.
 * Main-thread only (SimpleDateFormat is not thread-safe).
 */
private class DayClock(now: Long = System.currentTimeMillis()) {
    private val cal: Calendar = Calendar.getInstance()
    private val clockFormat = SimpleDateFormat("a h:mm", Locale.KOREA)
    private val sameYearDate = SimpleDateFormat("M월 d일", Locale.KOREA)
    private val otherYearDate = SimpleDateFormat("yyyy.M.d", Locale.KOREA)
    private val sameYearPill = SimpleDateFormat("M월 d일", Locale.KOREA)
    private val otherYearPill = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA)
    private val today: Long = dayOf(now)
    private val yesterday: Long = run {
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -1)
        key()
    }

    /** Calendar day of [at] in the device zone, encoded year × 1000 + day-of-year. */
    fun dayOf(at: Long): Long {
        cal.timeInMillis = at
        return key()
    }

    private fun key(): Long = cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)

    private fun sameYear(day: Long): Boolean = day / 1000 == today / 1000

    /** "오후 8:32" — bubble status line. */
    fun clockTime(at: Long): String = clockFormat.format(Date(at))

    /** Thread list time column: clock today, "어제", then "8월 19일" / "2025.8.19". */
    fun listTime(at: Long): String {
        if (at <= 0L) return ""
        val day = dayOf(at)
        return when {
            day == today -> clockTime(at)
            day == yesterday -> "어제"
            sameYear(day) -> sameYearDate.format(Date(at))
            else -> otherYearDate.format(Date(at))
        }
    }

    /** Date pill for the day containing [at]. */
    fun pillLabel(at: Long): String {
        val day = dayOf(at)
        return when {
            day == today -> "오늘"
            day == yesterday -> "어제"
            sameYear(day) -> sameYearPill.format(Date(at))
            else -> otherYearPill.format(Date(at))
        }
    }
}

/** Carrier delivery state appended to the bubble's time line ("오후 8:32 · 통신사 접수"). */
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
