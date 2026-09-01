package com.yunjelee.securemsg.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.AppDatabase
import com.yunjelee.securemsg.BlockedSender
import com.yunjelee.securemsg.BlocklistManager
import com.yunjelee.securemsg.BlocklistSync
import com.yunjelee.securemsg.ConversationOrder
import com.yunjelee.securemsg.ConversationTarget
import com.yunjelee.securemsg.ConversationTargetResolver
import com.yunjelee.securemsg.MessageHit
import com.yunjelee.securemsg.MessageRow
import com.yunjelee.securemsg.MessageSearch
import com.yunjelee.securemsg.PhoneNumberNormalizer
import com.yunjelee.securemsg.PinnedConversations
import com.yunjelee.securemsg.SmsNotifier
import com.yunjelee.securemsg.SmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Transient line under the list's search pill: what the last pin toggle did.
 *
 * [nonce] distinguishes two toggles that produce the same sentence, so the
 * dismissal timer restarts instead of the second one riding out the first's
 * remaining time.
 */
private data class PinNotice(val text: String, val nonce: Long)

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
    // Global body-search state. The rows are a one-shot query snapshot, not a
    // Flow: re-running it on every Room emission would re-scan the whole
    // message table for each arrival while the field is open.
    var globalRows by remember { mutableStateOf<List<MessageRow>>(emptyList()) }
    var quarantineMatches by remember { mutableStateOf(0) }
    // False from the first keystroke until that query's rows land, so the list
    // says "검색 중" instead of reporting a stale or empty result as final.
    var globalSearchSettled by remember { mutableStateOf(true) }
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
    // Sender-block state for the open conversation, from the two sources
    // BlocklistManager.evaluate reads: the Room rows and the prefs snapshot of
    // rules this account added on its other devices. The DAO hands out a new
    // Flow per call and collectAsState keys on the instance, so the remember
    // is what stops the query re-running on every recomposition.
    val blockedSenderRows by remember { db.blockedSenderDao().observeAll() }
        .collectAsState(initial = emptyList())
    var sharedRules by remember { mutableStateOf(BlocklistSync.load(context)) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var confirmBlockFor by remember { mutableStateOf<SmsThread?>(null) }
    // In-memory mirror of the pin prefs, same shape as the 연락처 tab's
    // favourites: the file is read once and the rows recompose off this.
    var pinnedPhones by remember { mutableStateOf(PinnedConversations.load(context)) }
    var pinNotice by remember { mutableStateOf<PinNotice?>(null) }
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
    // Pins lift rows only on the plain list. A search is ranked by what was
    // asked for, so letting a pin jump a weaker match to the top would answer
    // a different question than the one typed; filterThreads returns `threads`
    // untouched for a blank query, so this branch is not a second filter.
    val visibleThreads = remember(threads, threadSearchQuery, pinnedPhones) {
        if (threadSearchQuery.isBlank()) {
            ConversationOrder.pinnedFirst(threads, pinnedPhones)
        } else {
            MessageSearch.filterThreads(threads, threadSearchQuery)
        }
    }
    val visibleMessages = remember(selectedMessages, messageSearchQuery) {
        MessageSearch.filterMessages(selectedMessages, messageSearchQuery)
    }
    // Re-filtered against the CURRENT query, so rows fetched for a shorter
    // prefix narrow as the user keeps typing instead of showing stale matches
    // until the next query returns. Also re-labels when a thread is renamed.
    val messageHits = remember(globalRows, threads, threadSearchQuery) {
        MessageSearch.globalHits(globalRows, threads, threadSearchQuery)
    }
    // Re-read the wall clock whenever the data it labels changes, so "오늘"
    // cannot stay pinned across midnight for long.
    val clock = remember(threads, selectedMessages) { DayClock() }
    val senderRules = remember(blockedSenderRows, sharedRules) {
        senderRuleValues(blockedSenderRows, sharedRules)
    }
    // Only meaningful for the open conversation: the list and the composer have
    // no single sender to judge, so this stays false there.
    val selectedBlocked = remember(selectedThread?.phoneNumber, senderRules) {
        selectedThread?.let { BlocklistManager.senderBlocked(it.phoneNumber, senderRules) } == true
    }

    fun closeConversation() {
        selectedThread = null
        reply = ""
        sendNotice = null
        messageSearchQuery = ""
        messageSearchVisible = false
        moreMenuOpen = false
        confirmBlockFor = null
    }

    /**
     * Runs a block/unblock through the same helpers 설정 uses, then re-reads
     * both rule sources and checks what the sender's verdict actually became.
     *
     * The helpers swallow their own failures, and an unblock that could not
     * reach the server leaves the account-wide rule standing — so without this
     * check the menu would close, the header would still read 차단됨, and
     * nothing on screen would explain why. Success stays silent: the header and
     * the composer notice already move on their own.
     */
    fun changeSenderRule(phoneNumber: String, unblocking: Boolean, change: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            change()
            val reloaded = BlocklistSync.load(context)
            val stillBlocked = BlocklistManager.senderBlocked(
                phoneNumber,
                senderRuleValues(db.blockedSenderDao().getAll(), reloaded),
            )
            withContext(Dispatchers.Main) {
                sharedRules = reloaded
                sendNotice = if (stillBlocked == unblocking) {
                    SendNotice(
                        if (unblocking) UNBLOCK_FAILED else BLOCK_FAILED,
                        failed = true,
                    )
                } else {
                    null
                }
            }
        }
    }

    // Cross-conversation body search. Keyed on the query alone: the labels are
    // resolved from `threads` at render time, so a thread emission must not
    // restart the scan. Recomposition cancels the previous coroutine, which is
    // what debounces it — the delay is the first thing the body does, so only
    // the last keystroke of a burst reaches the database. Room's suspend DAO
    // dispatches to its own executor; the explicit IO context keeps the LIKE
    // scan off the main thread even if that ever changes.
    LaunchedEffect(threadSearchQuery) {
        val term = threadSearchQuery.trim()
        if (term.isEmpty()) {
            globalRows = emptyList()
            quarantineMatches = 0
            globalSearchSettled = true
            return@LaunchedEffect
        }
        globalSearchSettled = false
        delay(SEARCH_DEBOUNCE_MS)
        val escaped = MessageSearch.escapeLike(term)
        val (rows, quarantined) = withContext(Dispatchers.IO) {
            db.messageDao().searchAll(escaped, MessageSearch.GLOBAL_LIMIT) to
                db.blockedSmsDao().countMatching(escaped)
        }
        globalRows = rows
        quarantineMatches = quarantined
        globalSearchSettled = true
    }

    // The pin notice reports something already visible (the pin on the row,
    // and on the plain list the place it just took), so it retires itself
    // rather than sitting above the list until the next unrelated interaction.
    LaunchedEffect(pinNotice?.nonce) {
        if (pinNotice == null) return@LaunchedEffect
        delay(PIN_NOTICE_MS)
        pinNotice = null
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
        // Reading the thread is the read receipt for its notifications, whether
        // it was reached from the shade or from this list. Left out, they pile
        // up until the package hits the platform's active-notification cap, past
        // which notify() drops new ones without a word. Keyed on lastActivityAt
        // as well, so a message that lands while the thread is open is cleared
        // too.
        SmsNotifier.cancelConversation(context, thread.cid, thread.phoneNumber)
    }

    // Tell the notifier which conversation is on screen so an arrival in it is
    // not announced over the top of itself; the dispose covers back, thread
    // switching and this pane leaving the composition.
    DisposableEffect(selectedThread?.cid, selectedThread?.phoneNumber) {
        val thread = selectedThread
        SmsNotifier.setVisibleConversation(thread?.cid, thread?.phoneNumber)
        onDispose { SmsNotifier.setVisibleConversation(null, null) }
    }

    // The shared rules are a prefs snapshot, not a Flow, so re-read them when a
    // conversation opens — otherwise a rule added on the web or another phone
    // would not show here until this pane is recreated. Also drops an open menu
    // if the thread underneath it is swapped (offline cid migration).
    LaunchedEffect(selectedThread?.cid) {
        moreMenuOpen = false
        if (selectedThread == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { BlocklistSync.load(context) }
        sharedRules = loaded
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
        // Blocking is receive-side only (OutgoingSmsDispatcher never consults
        // the blocklist). Say so rather than leaving a live send button next to
        // a header that reads 차단됨.
        selectedBlocked -> BLOCKED_SENDER_NOTICE to Sm.text4
        else -> null
    }

    confirmBlockFor?.let { target ->
        SmConfirmDialog(
            title = "이 번호 차단",
            body = blockConfirmBody(target),
            confirmLabel = "차단",
            onConfirm = {
                confirmBlockFor = null
                changeSenderRule(target.phoneNumber, unblocking = false) {
                    blockSenderFromChat(context, target.phoneNumber)
                }
            },
            onDismiss = { confirmBlockFor = null },
        )
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

                // An alphanumeric sender id cannot become a rule — 설정 validates
                // new sender rules with this same pattern — so the ⋮ would open
                // a menu whose only item does nothing. A thread already covered
                // by a rule keeps it either way, or the block could not be lifted.
                val ruleCandidate = remember(thread.phoneNumber) {
                    SenderRulePattern.matches(PhoneNumberNormalizer.normalize(thread.phoneNumber))
                }
                SmChatHeader(
                    name = thread.displayName,
                    subtitle = "SMS · ${thread.phoneNumber}" + (if (selectedBlocked) " · 차단됨" else ""),
                    onBack = { closeConversation() },
                    onSearch = {
                        messageSearchVisible = !messageSearchVisible
                        if (!messageSearchVisible) messageSearchQuery = ""
                    },
                    onMore = if (selectedBlocked || ruleCandidate) ({ moreMenuOpen = true }) else null,
                    moreMenu = {
                        SmMenu(expanded = moreMenuOpen, onDismiss = { moreMenuOpen = false }) {
                            if (selectedBlocked) {
                                // Reversible and non-destructive: no confirmation.
                                SmMenuItem(
                                    text = "차단 해제",
                                    onClick = {
                                        moreMenuOpen = false
                                        changeSenderRule(thread.phoneNumber, unblocking = true) {
                                            unblockSenderFromChat(context, thread.phoneNumber, senderRules)
                                        }
                                    },
                                )
                            } else {
                                SmMenuItem(
                                    text = "이 번호 차단",
                                    onClick = {
                                        moreMenuOpen = false
                                        confirmBlockFor = thread
                                    },
                                    textColor = Sm.danger,
                                )
                            }
                        }
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
                val searching = threadSearchQuery.isNotBlank()
                val threadListState = rememberLazyListState()
                // A pin toggle reorders keyed rows, and LazyColumn answers that
                // by holding whatever row was first visible at the same y — so
                // the row the user just pinned is carried off the top of the
                // viewport instead of appearing there, and an unpin drags the
                // list down chasing that same anchor. An explicit scroll clears
                // the anchor key, and index 0 is where the pinned block starts,
                // which is what PIN_ADDED tells the user to look at.
                var pinScrollTick by remember { mutableStateOf(0) }
                LaunchedEffect(pinScrollTick) {
                    if (pinScrollTick > 0) threadListState.scrollToItem(0)
                }
                SmSearchPill(
                    query = threadSearchQuery,
                    onQueryChange = { threadSearchQuery = it.take(200) },
                    // Both halves of the promise are met: name/number over the
                    // loaded threads (MessageSearch.filterThreads) and body text
                    // over every conversation (MessageDao.searchAll).
                    placeholder = "대화·메시지 검색",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                )
                pinNotice?.let { note ->
                    Text(
                        note.text,
                        color = Sm.text4,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    )
                }
                if (searching) {
                    Text(
                        if (globalSearchSettled) {
                            MessageSearch.resultSummary(visibleThreads.size, messageHits.size)
                        } else {
                            "대화 상대 ${visibleThreads.size}건 · 메시지 검색 중…"
                        },
                        color = Sm.text4,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp),
                    )
                    // Only once the count belongs to the query on screen: the
                    // debounce window would otherwise attribute the previous
                    // query's spam count to what the user just typed.
                    MessageSearch.quarantineNotice(
                        if (globalSearchSettled) quarantineMatches else 0,
                    )?.let { note ->
                        Text(
                            note,
                            color = Sm.text4,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                        )
                    }
                } else if (threads.isEmpty()) {
                    EmptyNote("아직 표시할 문자가 없습니다.")
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = threadListState,
                    // Bottom inset clears the 56dp FAB the shell floats 20dp
                    // off the corner, so the last row can scroll out from under it.
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Section headers only while searching, so the plain list is
                    // untouched. Keys are prefixed because the two sections share
                    // one LazyColumn and a cid must not collide with a message id.
                    if (searching && visibleThreads.isNotEmpty()) {
                        item(key = "h:threads") { SmSectionHeader("대화 상대") }
                    }
                    items(visibleThreads, key = { "t:${it.cid}" }) { item ->
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
                        val pinned = ConversationOrder.isPinned(item, pinnedPhones)
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
                            pinned = pinned,
                            onLongClick = {
                                val next = PinnedConversations.toggle(context, item.phoneNumber)
                                // Read the flip back instead of assuming it: a
                                // number that normalizes to nothing is not
                                // stored, and claiming otherwise would leave the
                                // list contradicting the notice.
                                if (next != pinnedPhones) {
                                    pinnedPhones = next
                                    pinNotice = PinNotice(
                                        pinNoticeText(wasPinned = pinned, searching = searching),
                                        (pinNotice?.nonce ?: 0L) + 1,
                                    )
                                    if (!searching) pinScrollTick++
                                }
                            },
                        )
                    }
                    if (searching) {
                        if (messageHits.isNotEmpty()) {
                            item(key = "h:messages") { SmSectionHeader("메시지") }
                        }
                        items(messageHits, key = { "m:${it.messageId}" }) { hit ->
                            MessageHitRow(
                                hit = hit,
                                time = clock.listTime(hit.createdAt),
                                onClick = {
                                    threads.firstOrNull { it.cid == hit.cid }?.let { target ->
                                        selectedThread = target
                                        // Hand the query to the in-conversation
                                        // search instead of scrolling to an id:
                                        // that pane already filters and shows the
                                        // matches, and its predicate is a superset
                                        // of the SQL one, so the tapped message is
                                        // always among them.
                                        messageSearchQuery = threadSearchQuery.trim()
                                        messageSearchVisible = true
                                    }
                                },
                            )
                        }
                        if (globalSearchSettled && visibleThreads.isEmpty() && messageHits.isEmpty()) {
                            item(key = "e:none") {
                                EmptyNote("일치하는 대화 상대나 메시지가 없습니다.")
                            }
                        }
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

/**
 * Pause after the last keystroke before the cross-conversation scan runs.
 * Matches the web client's ChatList debounce so the two feel the same.
 */
private const val SEARCH_DEBOUNCE_MS = 200L

/**
 * One global-search hit, under the 메시지 header.
 *
 * Deliberately not an [SmConversationRow]: that row means "a conversation, and
 * this is its latest message", and reusing it here would say the match IS the
 * thread's newest message. This one leads with whose conversation the hit is
 * in, then the matched text, so a hit is never read detached from its
 * conversation.
 */
@Composable
private fun MessageHitRow(hit: MessageHit, time: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SmAvatar(hit.displayName, size = 36, personIcon = !hit.showsPhoneSubtitle)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    hit.displayName,
                    color = Sm.text1,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(time, color = Sm.text4, fontSize = 10.sp)
            }
            Text(
                // The row is titled with the conversation, so without this a
                // message the user sent reads as one the other side sent.
                if (hit.mine) {
                    buildAnnotatedString {
                        append("나: ")
                        append(highlightedSnippet(hit.snippet))
                    }
                } else {
                    highlightedSnippet(hit.snippet)
                },
                color = Sm.text4,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val SEND_FAILED = "SMS 발송 실패 — 번호·권한·메시지 길이를 확인하세요."
private const val SEND_QUEUED = "SMS를 발송했고 동기화 대기열에 저장했습니다."
private const val BLOCKED_SENDER_NOTICE =
    "차단한 번호입니다 — 받는 문자는 격리되고, 보내기는 계속 가능합니다."
private const val UNBLOCK_FAILED =
    "차단 해제 실패 — 서버에 반영되지 않아 계속 차단됩니다. 연결 후 다시 시도하세요."
private const val BLOCK_FAILED =
    "차단 실패 — 규칙이 저장되지 않았습니다. 연결 후 다시 시도하세요."
private const val PIN_ADDED = "대화를 고정했습니다 — 목록 맨 위에 표시됩니다. 이 기기에만 저장됩니다."
private const val PIN_ADDED_SEARCHING =
    "대화를 고정했습니다 — 검색을 지우면 목록 맨 위에 표시됩니다. 이 기기에만 저장됩니다."
private const val PIN_REMOVED = "대화 고정을 해제했습니다."

/** How long a pin toggle's line stays under the search pill. */
private const val PIN_NOTICE_MS = 2_500L

/**
 * The line a pin toggle leaves under the search pill, given the row's state
 * before the flip and whether a query is on screen.
 *
 * [searching] changes what can be promised: pins lift rows only on the plain
 * list (see visibleThreads), so a pin made from search results moves nothing
 * the user can see and the notice has to name the moment it comes true.
 * Un-pinning claims no position either way, so it reads the same in both.
 */
internal fun pinNoticeText(wasPinned: Boolean, searching: Boolean): String = when {
    wasPinned -> PIN_REMOVED
    searching -> PIN_ADDED_SEARCHING
    else -> PIN_ADDED
}

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

// ---------------------------------------------------------------------------
// Sender block (chat overflow menu)
// ---------------------------------------------------------------------------

/** Every sender rule in force on this device — the two sources BlocklistManager.evaluate reads. */
private fun senderRuleValues(
    local: List<BlockedSender>,
    shared: BlocklistSync.SharedRules,
): List<String> = (local.map { it.phoneNumber } + shared.senders).distinct()

/**
 * Adds a sender rule exactly the way 설정 does, normalization included, so a
 * number blocked from a chat and the same number blocked from 설정 produce one
 * rule rather than two shapes of it.
 */
private suspend fun blockSenderFromChat(context: Context, phoneNumber: String) {
    addBlockRule(context, "sender", PhoneNumberNormalizer.normalize(phoneNumber))
}

/**
 * Removes every stored rule covering [phoneNumber].
 *
 * Removal is an exact-value comparison on both sides (the Room row is matched
 * by `phoneNumber ==`, the server id is looked up under `"sender|$value"`), so
 * handing it the normalized number would silently leave a legacy `010…` rule —
 * which [BlocklistManager.senderMatches] still honours — behind, and the sender
 * would stay blocked with the UI claiming otherwise.
 */
private suspend fun unblockSenderFromChat(
    context: Context,
    phoneNumber: String,
    ruleValues: List<String>,
) {
    BlocklistManager.matchingSenderRules(phoneNumber, ruleValues)
        .forEach { removeBlockRuleOnServer(context, "sender", it) }
}

/**
 * Body of the block confirmation.
 *
 * It promises only what the code does. The web client re-applies sender rules
 * to existing history on `blocklist_updated`, and its verdict is
 * "sender-blocked conversation AND the message came through an android_gateway
 * device" (useStore.ts) — which is every message this phone relayed, the user's
 * own outgoing SMS included. So the copy says the conversation stays and its
 * messages are masked, not that the conversation disappears, and it names the
 * outgoing half explicitly: that asymmetry is invisible otherwise, and this
 * dialog is the only place the user is told any of it.
 */
private fun blockConfirmBody(thread: SmsThread): String {
    val who = if (thread.showsPhoneSubtitle) {
        "${thread.displayName}(${thread.phoneNumber})"
    } else {
        thread.phoneNumber
    }
    return "$who 을(를) 차단합니다.\n\n" +
        "앞으로 이 번호에서 오는 문자는 대화에 표시되지 않고 설정 › 격리된 스팸에 보관됩니다. " +
        "보내기는 계속 가능합니다.\n\n" +
        "차단 규칙은 이 계정의 모든 기기에 동기화됩니다. 웹에서는 이 대화가 목록에 그대로 남지만 " +
        "기존 메시지가 '차단된 메시지'로 가려지며, 이 폰이 중계한 메시지가 대상이라 내가 폰에서 보낸 " +
        "문자도 함께 가려집니다(웹에서 보낸 문자는 그대로 보입니다). 차단을 해제하면 다시 표시됩니다."
}

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
 * is what "unread" is measured against. Not synced; the bridge carries a stamp
 * across its provisional→server cid rewrite via [move], nothing else migrates.
 */
internal object LastOpened {
    private const val PREFS = "thread_last_opened"

    /** Forget-device path: stamps belong to the account being removed. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Re-keys one stamp when the bridge rewrites a conversation's cid. A shade
     * 읽음/reply on a still-provisional `local_…` thread stamps that cid; once
     * the relay assigns the real one, the old key would otherwise be orphaned
     * and the conversation would flip back to unread.
     */
    fun move(context: Context, fromCid: String, toCid: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val from = prefs.getLong(fromCid, 0L)
        if (from <= 0L) return
        // max(): the target thread may already carry its own, later stamp.
        prefs.edit()
            .remove(fromCid)
            .putLong(toCid, maxOf(from, prefs.getLong(toCid, 0L)))
            .apply()
    }

    fun all(context: Context): Map<String, Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (cid, at) -> (at as? Long)?.let { cid to it } }
            .toMap()

    fun set(context: Context, cid: String, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(cid, at).apply()
    }

    /**
     * Stamps a conversation the user has never opened as read up to [at].
     *
     * For HistoryRestore, whose rebuilt conversations carry brand-new cids and
     * therefore no stamp at all: without this every restored thread whose newest
     * message is incoming comes back bold with a badge counting the whole
     * history the user read years ago. An existing stamp is the user's real read
     * position and is never moved.
     */
    fun setIfAbsent(context: Context, cid: String, at: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(cid, 0L) > 0L) return
        prefs.edit().putLong(cid, at).apply()
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
