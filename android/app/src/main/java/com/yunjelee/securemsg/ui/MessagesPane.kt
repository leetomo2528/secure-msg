package com.yunjelee.securemsg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.AppDatabase
import com.yunjelee.securemsg.MessageSearch
import com.yunjelee.securemsg.PhoneNumberNormalizer
import com.yunjelee.securemsg.SmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "메시지" tab: new-SMS composer, thread list, and the open conversation. */
@Composable
fun ColumnScope.MessagesPane(
    threads: List<SmsThread>,
    smsRoleHeld: Boolean,
    smsPermissionsGranted: Boolean,
    setStatus: (String) -> Unit,
    sendSms: suspend (phone: String, text: String) -> Boolean,
) {
    val context = LocalContext.current
    val db = AppDatabase.get(context)
    val scope = rememberCoroutineScope()
    var selectedThread by remember { mutableStateOf<SmsThread?>(null) }
    var threadSearchQuery by remember { mutableStateOf("") }
    var messageSearchQuery by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var newMsg by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val selectedMessageFlow = remember(selectedThread?.cid) {
        selectedThread?.let { db.messageDao().observeForCid(it.cid) } ?: flowOf(emptyList())
    }
    val selectedMessages by selectedMessageFlow.collectAsState(initial = emptyList())
    val visibleThreads = remember(threads, threadSearchQuery) {
        MessageSearch.filterThreads(threads, threadSearchQuery)
    }
    val visibleMessages = remember(selectedMessages, messageSearchQuery) {
        MessageSearch.filterMessages(selectedMessages, messageSearchQuery)
    }

    // Offline sends start in a provisional local thread. Once the relay
    // reconnects it atomically swaps that cid for the server cid; keep the
    // open conversation selected across that migration.
    LaunchedEffect(threads, selectedThread?.cid) {
        val current = selectedThread ?: return@LaunchedEffect
        selectedThread = threads.firstOrNull { it.cid == current.cid }
            ?: threads.firstOrNull {
                PhoneNumberNormalizer.normalize(it.phoneNumber) ==
                    PhoneNumberNormalizer.normalize(current.phoneNumber)
            }
    }

    val canSend = !sending && smsRoleHeld && smsPermissionsGranted

    Column(
        Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val thread = selectedThread
        if (thread == null) {
            SmCard {
                SectionTitle("새 SMS 발신")
                SmTextField(
                    value = newPhone,
                    onValueChange = { newPhone = it.take(32) },
                    label = "전화번호",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SmTextField(
                    value = newMsg,
                    onValueChange = { newMsg = it.take(20_000) },
                    label = "메시지",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3,
                )
                SmGradientButton(
                    text = if (sending) "발송 중…" else "발송 + 동기화",
                    enabled = canSend && newPhone.isNotBlank() && newMsg.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (sending || newPhone.isBlank() || newMsg.isBlank()) return@SmGradientButton
                        sending = true
                        scope.launch(Dispatchers.IO) {
                            val sent = sendSms(newPhone.trim(), newMsg.trim())
                            withContext(Dispatchers.Main) {
                                if (sent) {
                                    newMsg = ""
                                    setStatus("SMS를 발송했고 동기화 대기열에 저장했습니다.")
                                } else {
                                    setStatus("SMS 발송 실패 — 번호·권한·메시지 길이를 확인하세요.")
                                }
                                sending = false
                            }
                        }
                    },
                )
            }
            MessageSearchField(
                query = threadSearchQuery,
                onQueryChange = { threadSearchQuery = it.take(200) },
                label = "대화 상대 또는 전화번호 검색",
            )
            Text(
                if (threadSearchQuery.isBlank()) {
                    "SMS 스레드 (${threads.size})"
                } else {
                    "검색 결과 (${visibleThreads.size}/${threads.size})"
                },
                color = Sm.text2,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (threads.isEmpty()) {
                Text(
                    "아직 표시할 문자가 없습니다.",
                    color = Sm.text4,
                    fontSize = 12.sp,
                )
            } else if (visibleThreads.isEmpty()) {
                Text(
                    "일치하는 대화 상대나 전화번호가 없습니다.",
                    color = Sm.text4,
                    fontSize = 12.sp,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(visibleThreads, key = { it.cid }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedThread = item
                                messageSearchQuery = ""
                            }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SmAvatar(item.displayName)
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.displayName,
                                color = Sm.text1, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            if (item.showsPhoneSubtitle) {
                                Text(item.phoneNumber, color = Sm.text4, fontSize = 11.sp)
                            }
                        }
                        Text("›", color = Sm.text4, fontSize = 18.sp)
                    }
                    HorizontalDivider(color = Sm.borderSoft)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmAvatar(thread.displayName, size = 34)
                    Column {
                        Text(
                            thread.displayName,
                            color = Sm.text1, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (thread.showsPhoneSubtitle) {
                            Text(thread.phoneNumber, color = Sm.text4, fontSize = 11.sp)
                        }
                    }
                }
                SmGhostButton(
                    text = "목록",
                    onClick = {
                        selectedThread = null
                        reply = ""
                        messageSearchQuery = ""
                    },
                    modifier = Modifier.padding(vertical = 0.dp),
                )
            }
            MessageSearchField(
                query = messageSearchQuery,
                onQueryChange = { messageSearchQuery = it.take(200) },
                label = "이 대화에서 검색",
            )
            if (messageSearchQuery.isNotBlank() && visibleMessages.isEmpty()) {
                Text(
                    "일치하는 메시지나 제목이 없습니다.",
                    color = Sm.text4,
                    fontSize = 12.sp,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(visibleMessages, key = { it.id }) { message ->
                    ChatBubble(
                        mine = message.mine,
                        blocked = message.blocked,
                        text = if (message.blocked) {
                            "차단된 메시지"
                        } else {
                            message.plaintext + carrierStatusLabel(message.carrierStatus)
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().imePadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmTextField(
                    value = reply,
                    onValueChange = { reply = it.take(20_000) },
                    label = "답장",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                val canReply = reply.isNotBlank() && canSend
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (canReply) Sm.gradient
                            else Brush.linearGradient(listOf(Sm.border, Sm.border)),
                        )
                        .clickable(enabled = canReply) {
                            val text = reply.trim()
                            if (text.isNotBlank() && !sending) {
                                sending = true
                                scope.launch(Dispatchers.IO) {
                                    val sent = sendSms(thread.phoneNumber, text)
                                    withContext(Dispatchers.Main) {
                                        if (sent) reply = "" else {
                                            setStatus("SMS 발송 실패 — 번호·권한·메시지 길이를 확인하세요.")
                                        }
                                        sending = false
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (sending) "…" else "↑",
                        color = if (canReply) Color(0xFF052530) else Sm.text4,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmTextField(
            value = query,
            onValueChange = onQueryChange,
            label = label,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (query.isNotEmpty()) {
            SmGhostButton(
                text = "지우기",
                onClick = { onQueryChange("") },
            )
        }
    }
}

/** Carrier delivery status suffix shown inside the chat bubble. */
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
