package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSearchTest {
    @Test
    fun `blank query restores every thread and message`() {
        val threads = listOf(thread("local", localName = "윤제"), thread("server"))
        val messages = listOf(message(1, "공개"), message(2, "비밀", blocked = true))

        assertEquals(threads, MessageSearch.filterThreads(threads, "  "))
        assertEquals(messages, MessageSearch.filterMessages(messages, "\t"))
    }

    @Test
    fun `thread search uses display-name priority and phone number`() {
        val local = thread(
            cid = "local",
            phone = "010-1111-2222",
            serverName = "서버 별칭",
            localName = "내 연락처",
        )
        val server = thread("server", phone = "010-3333-4444", serverName = "Alice")
        val phoneOnly = thread("phone", phone = "+82 10-5555-6666")
        val threads = listOf(local, server, phoneOnly)

        assertEquals(listOf(local), MessageSearch.filterThreads(threads, "연락처"))
        assertEquals(emptyList<SmsThread>(), MessageSearch.filterThreads(threads, "서버 별칭"))
        assertEquals(listOf(server), MessageSearch.filterThreads(threads, "aLiCe"))
        assertEquals(listOf(phoneOnly), MessageSearch.filterThreads(threads, "5555"))
    }

    @Test
    fun `message search matches plaintext and subject case-insensitively`() {
        val bodyMatch = message(1, "Meet at Noon")
        val subjectMatch = message(2, "첨부 파일", subject = "Project ALPHA")
        val miss = message(3, "다른 내용", subject = "베타")
        val messages = listOf(bodyMatch, subjectMatch, miss)

        assertEquals(listOf(bodyMatch), MessageSearch.filterMessages(messages, "NOON"))
        assertEquals(listOf(subjectMatch), MessageSearch.filterMessages(messages, "alpha"))
    }

    @Test
    fun `blocked messages never match plaintext or subject`() {
        val blockedBody = message(1, "노출되면 안 되는 secret", blocked = true)
        val blockedSubject = message(2, "본문", subject = "secret subject", blocked = true)
        val visible = message(3, "visible secret")

        assertEquals(
            listOf(visible),
            MessageSearch.filterMessages(listOf(blockedBody, blockedSubject, visible), "secret"),
        )
    }

    private fun thread(
        cid: String,
        phone: String = "010-0000-0000",
        serverName: String? = null,
        localName: String? = null,
    ) = SmsThread(
        cid = cid,
        phoneNumber = phone,
        serverName = serverName,
        localContactName = localName,
    )

    private fun message(
        id: Long,
        plaintext: String,
        subject: String? = null,
        blocked: Boolean = false,
    ) = MessageRow(
        id = id,
        cid = "cid",
        seq = id.toInt(),
        senderSid = "sid",
        plaintext = plaintext,
        createdAt = id,
        mine = false,
        blocked = blocked,
        subject = subject,
    )
}
