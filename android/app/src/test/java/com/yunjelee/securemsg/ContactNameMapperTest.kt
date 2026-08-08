package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNameMapperTest {
    @Test
    fun normalizesContactAndThreadNumbersBeforeMatching() {
        val result = ContactNameMapper.map(
            contacts = listOf(ContactPhoneRow(" 윤제 ", "010-1234-5678")),
            threads = listOf(SmsThread("cid-1", "(010) 1234.5678", "서버 이름")),
        )

        assertEquals(listOf(ContactNameUpdate("cid-1", "윤제")), result.updates)
        assertEquals(1, result.matchedThreadCount)
        assertEquals(1, result.contactPhoneCount)
    }

    @Test
    fun clearsAStaleLocalNameWhenContactNoLongerMatches() {
        val result = ContactNameMapper.map(
            contacts = emptyList(),
            threads = listOf(
                SmsThread(
                    cid = "cid-1",
                    phoneNumber = "01012345678",
                    serverName = "서버 이름",
                    localContactName = "이전 이름",
                ),
            ),
        )

        assertEquals(listOf(ContactNameUpdate("cid-1", null)), result.updates)
        assertEquals(0, result.matchedThreadCount)
    }

    @Test
    fun skipsDatabaseWritesWhenNamesAreUnchanged() {
        val result = ContactNameMapper.map(
            contacts = listOf(
                ContactPhoneRow("첫 이름", "+82 10-1111-2222"),
                ContactPhoneRow("중복 이름", "+821011112222"),
                ContactPhoneRow("", "01099998888"),
            ),
            threads = listOf(
                SmsThread(
                    cid = "cid-1",
                    phoneNumber = "+821011112222",
                    serverName = "서버 이름",
                    localContactName = "첫 이름",
                ),
            ),
        )

        assertEquals(emptyList<ContactNameUpdate>(), result.updates)
        assertEquals(1, result.matchedThreadCount)
        assertEquals(1, result.contactPhoneCount)
    }

    @Test
    fun localContactNameTakesDisplayPriorityWithoutReplacingServerName() {
        val thread = SmsThread(
            cid = "cid-1",
            phoneNumber = "+821011112222",
            serverName = "서버 대화 이름",
            localContactName = "내 연락처 이름",
        )

        assertEquals("내 연락처 이름", thread.displayName)
        assertEquals("서버 대화 이름", thread.serverName)
        assertEquals(true, thread.showsPhoneSubtitle)
    }

    @Test
    fun serverNameRemainsDisplayFallbackAfterLocalContactIsCleared() {
        val thread = SmsThread(
            cid = "cid-1",
            phoneNumber = "+821011112222",
            serverName = "서버 대화 이름",
            localContactName = null,
        )

        assertEquals("서버 대화 이름", thread.displayName)
        assertEquals(true, thread.showsPhoneSubtitle)
    }
}
