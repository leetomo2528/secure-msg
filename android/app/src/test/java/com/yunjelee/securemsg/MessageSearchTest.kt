package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `message search preserves newest-first conversation order`() {
        val newest = message(3, "찾을 내용 newest")
        val middle = message(2, "다른 내용")
        val oldest = message(1, "찾을 내용 oldest")

        assertEquals(
            listOf(newest, oldest),
            MessageSearch.filterMessages(listOf(newest, middle, oldest), "찾을 내용"),
        )
    }

    // -----------------------------------------------------------------------
    // Global (cross-conversation) search
    // -----------------------------------------------------------------------

    @Test
    fun `like wildcards in the query are escaped to literals`() {
        assertEquals("50\\%", MessageSearch.escapeLike("50%"))
        assertEquals("a\\_b", MessageSearch.escapeLike("a_b"))
        assertEquals("C:\\\\tmp", MessageSearch.escapeLike("C:\\tmp"))
        assertEquals("100\\%\\_할인", MessageSearch.escapeLike("100%_할인"))
        assertEquals("안녕하세요", MessageSearch.escapeLike("안녕하세요"))
    }

    @Test
    fun `wildcard characters match literally on the kotlin side too`() {
        val threads = listOf(thread("cid"))
        val percent = message(1, "할인 50% 쿠폰")
        val other = message(2, "할인 5000원 쿠폰")

        val hits = MessageSearch.globalHits(listOf(percent, other), threads, "50%")

        assertEquals(listOf(1L), hits.map { it.messageId })
    }

    @Test
    fun `snippet windows around the match and reports its span`() {
        val body = "가".repeat(80) + "약속시간" + "나".repeat(80)

        val snippet = MessageSearch.snippet(body, "약속시간")

        assertEquals("약속시간", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        assertTrue(snippet.hasMatch)
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        assertTrue(snippet.text.length < body.length)
    }

    @Test
    fun `snippet flattens line breaks and keeps a short body whole`() {
        val snippet = MessageSearch.snippet("첫 줄\n\n  둘째 줄 약속", "약속")

        assertEquals("첫 줄 둘째 줄 약속", snippet.text)
        assertEquals("약속", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun `snippet without an occurrence renders a head and marks no match`() {
        val snippet = MessageSearch.snippet("본문에는 없는 말", "제목")

        assertEquals("본문에는 없는 말", snippet.text)
        assertFalse(snippet.hasMatch)
    }

    @Test
    fun `subject hits are snippeted as subject then body`() {
        val threads = listOf(thread("cid"))
        val row = message(1, "본문 내용", subject = "회의 안내")

        val hit = MessageSearch.globalHits(listOf(row), threads, "안내").single()

        assertEquals("회의 안내 — 본문 내용", hit.snippet.text)
        assertEquals("안내", hit.snippet.text.substring(hit.snippet.matchStart, hit.snippet.matchEnd))
    }

    @Test
    fun `korean matches inside a word, with no tokenizer boundary`() {
        val threads = listOf(thread("cid"))
        val row = message(1, "오늘 재검색합니다")

        val hit = MessageSearch.globalHits(listOf(row), threads, "검색").single()

        assertEquals("검색", hit.snippet.text.substring(hit.snippet.matchStart, hit.snippet.matchEnd))
    }

    @Test
    fun `global search excludes blocked rows and labels the rest with their conversation`() {
        val threads = listOf(thread("cid", phone = "010-1111-2222", localName = "엄마"))
        val blocked = message(2, "secret 차단본문", blocked = true)
        val visible = message(1, "secret 정상본문")

        val hits = MessageSearch.globalHits(listOf(blocked, visible), threads, "secret")

        assertEquals(listOf(1L), hits.map { it.messageId })
        assertEquals("엄마", hits.single().displayName)
        assertEquals("010-1111-2222", hits.single().phoneNumber)
        assertEquals("cid", hits.single().cid)
    }

    @Test
    fun `quarantined spam is reported as a count, never as a result`() {
        assertNull(MessageSearch.quarantineNotice(0))
        assertNull(MessageSearch.quarantineNotice(-1))
        assertTrue(MessageSearch.quarantineNotice(3)!!.contains("3건"))
        assertTrue(MessageSearch.quarantineNotice(3)!!.contains("격리된 스팸"))
    }

    @Test
    fun `the quarantine list can reproduce every count the notice names`() {
        val quarantined = (1L..30L).map { quarantine(it, "본문 $it 이벤트 당첨") } +
            quarantine(99, "관계 없는 본문")

        val hits = MessageSearch.filterQuarantine(quarantined, "이벤트")

        // The notice would say 30건; the destination must be able to list 30.
        assertEquals(30, hits.size)
        assertEquals((1L..30L).toList(), hits.map { it.id })
    }

    @Test
    fun `quarantine filter matches body only and keeps newest-first order`() {
        val items = listOf(
            quarantine(3, "쿠폰 안내", phone = "010-1111-1111"),
            quarantine(2, "무관한 본문", phone = "010-쿠폰-0000"),
            quarantine(1, "쿠폰 재발급"),
        )

        val hits = MessageSearch.filterQuarantine(items, " 쿠폰 ".trim())

        // Sender numbers are not searched: countMatching does not count them,
        // so listing them would overshoot the number the notice promised.
        assertEquals(listOf(3L, 1L), hits.map { it.id })
    }

    @Test
    fun `an empty quarantine query keeps the whole list`() {
        val items = listOf(quarantine(2, "가"), quarantine(1, "나"))

        assertEquals(items, MessageSearch.filterQuarantine(items, ""))
        assertEquals(items, MessageSearch.filterQuarantine(items, "   "))
    }

    @Test
    fun `a quarantine match past the visible head is windowed into view`() {
        val body = "광고".repeat(200) + "무료수신거부"

        val snippet = MessageSearch.snippet(body, "무료수신거부", radius = 32, maxLength = 120)

        assertTrue(snippet.hasMatch)
        assertEquals(
            "무료수신거부",
            snippet.text.substring(snippet.matchStart, snippet.matchEnd),
        )
    }

    @Test
    fun `empty and whitespace queries produce no global hits`() {
        val threads = listOf(thread("cid"))
        val rows = listOf(message(1, "무엇이든 담긴 본문"))

        assertEquals(emptyList<MessageHit>(), MessageSearch.globalHits(rows, threads, ""))
        assertEquals(emptyList<MessageHit>(), MessageSearch.globalHits(rows, threads, "  \t\n "))
    }

    @Test
    fun `global hits keep dao order, honour the cap and drop unknown conversations`() {
        val threads = listOf(thread("cid"))
        val known = (5L downTo 1L).map { message(it, "찾기 $it") }
        val orphan = message(9, "찾기 orphan").copy(cid = "지워진대화")

        assertEquals(
            listOf(5L, 4L, 3L),
            MessageSearch.globalHits(known + orphan, threads, "찾기", limit = 3).map { it.messageId },
        )
        assertEquals(
            listOf(5L, 4L, 3L, 2L, 1L),
            MessageSearch.globalHits(known + orphan, threads, "찾기").map { it.messageId },
        )
    }

    @Test
    fun `result summary names the cap only when it may have been reached`() {
        assertEquals("대화 상대 2건 · 메시지 3건", MessageSearch.resultSummary(2, 3))
        assertTrue(
            MessageSearch.resultSummary(0, MessageSearch.GLOBAL_LIMIT)
                .contains("${MessageSearch.GLOBAL_LIMIT}건 이상"),
        )
    }

    private fun quarantine(
        id: Long,
        body: String,
        phone: String = "010-0000-0000",
    ): BlockedSms = BlockedSms(
        id = id,
        phoneNumber = phone,
        body = body,
        reason = "keyword",
        receivedAt = id,
    )

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

    @Test
    fun `snippet never splits a surrogate pair`() {
        // A window edge landing inside an emoji used to emit a lone surrogate,
        // which renders as a replacement glyph at the head of the row.
        val body = "\uD83D\uDE00".repeat(12) + "X검색 약속"
        val snippet = MessageSearch.snippet(body, "검색")
        assertTrue(
            "snippet must not contain an unpaired surrogate: ${snippet.text}",
            snippet.text.none { Character.isHighSurrogate(it) || Character.isLowSurrogate(it) } ||
                isWellFormedUtf16(snippet.text),
        )
    }

    @Test
    fun `clipping a no-match body keeps surrogate pairs intact`() {
        val body = "a".repeat(95) + "\uD83D\uDE00".repeat(10)
        val snippet = MessageSearch.snippet(body, "찾을수없는말")
        assertTrue(isWellFormedUtf16(snippet.text))
    }

    private fun isWellFormedUtf16(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            i += 1
        }
        return true
    }
}
