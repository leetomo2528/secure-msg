package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationOrderTest {
    private fun thread(cid: String, phone: String, at: Long) = SmsThread(
        cid = cid,
        phoneNumber = phone,
        serverName = null,
        lastActivityAt = at,
    )

    // The DAO's order: lastActivityAt DESC, so the list arrives newest-first.
    private val newest = thread("c1", "+821011112222", 3_000)
    private val middle = thread("c2", "+821033334444", 2_000)
    private val oldest = thread("c3", "+821055556666", 1_000)
    private val all = listOf(newest, middle, oldest)

    @Test
    fun `no pins leaves the list untouched`() {
        assertSame(all, ConversationOrder.pinnedFirst(all, emptySet()))
    }

    @Test
    fun `a pin set matching no thread leaves the list untouched`() {
        assertSame(all, ConversationOrder.pinnedFirst(all, setOf("+821099998888")))
    }

    @Test
    fun `pinned threads move above every unpinned one`() {
        val ordered = ConversationOrder.pinnedFirst(all, setOf("+821055556666"))

        assertEquals(listOf(oldest, newest, middle), ordered)
    }

    @Test
    fun `newest-first order is preserved inside both groups`() {
        val ordered = ConversationOrder.pinnedFirst(
            all,
            setOf("+821055556666", "+821011112222"),
        )

        // Pinned: newest before oldest. Unpinned: the single remaining one.
        assertEquals(listOf(newest, oldest, middle), ordered)
    }

    @Test
    fun `threads sharing a timestamp keep the order they arrived in`() {
        val tied = listOf(
            thread("a", "+821011112222", 5_000),
            thread("b", "+821033334444", 5_000),
            thread("c", "+821055556666", 5_000),
            thread("d", "+821077778888", 5_000),
        )

        val ordered = ConversationOrder.pinnedFirst(
            tied,
            setOf("+821077778888", "+821033334444"),
        )

        assertEquals(listOf("b", "d", "a", "c"), ordered.map { it.cid })
    }

    @Test
    fun `pinning every thread changes nothing`() {
        val ordered = ConversationOrder.pinnedFirst(
            all,
            all.map { it.phoneNumber }.toSet(),
        )

        assertEquals(all, ordered)
    }

    @Test
    fun `a contact stored in local form pins the thread keyed in E164`() {
        // What PinnedConversations writes for a long-press on 010-1234-5678.
        val key = PinnedConversations.toggled(emptySet(), "010-1234-5678")
        val threadFromRelay = thread("c9", "+821012345678", 4_000)

        assertTrue(ConversationOrder.isPinned(threadFromRelay, key))
        assertEquals(
            listOf(threadFromRelay, newest, middle, oldest),
            ConversationOrder.pinnedFirst(listOf(newest, middle, threadFromRelay, oldest), key),
        )
    }

    @Test
    fun `a carrier sender id is matched literally rather than reshaped`() {
        val alphanumeric = thread("c10", "15881588", 4_000)

        assertTrue(ConversationOrder.isPinned(alphanumeric, setOf("15881588")))
        assertFalse(ConversationOrder.isPinned(alphanumeric, setOf("+8215881588")))
    }

    @Test
    fun `an empty list survives a non-empty pin set`() {
        assertEquals(
            emptyList<SmsThread>(),
            ConversationOrder.pinnedFirst(emptyList(), setOf("+821011112222")),
        )
    }
}
