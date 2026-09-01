package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PinnedConversationsTest {
    @Test
    fun `toggle adds then removes the same key`() {
        val added = PinnedConversations.toggled(emptySet(), "+821012345678")
        assertEquals(setOf("+821012345678"), added)

        assertEquals(emptySet<String>(), PinnedConversations.toggled(added, "+821012345678"))
    }

    @Test
    fun `toggle normalizes so address book and thread forms agree`() {
        val added = PinnedConversations.toggled(emptySet(), "010-1234-5678")
        assertEquals(setOf("+821012345678"), added)

        // The same conversation reached from the relay's E.164 key unpins it.
        assertEquals(emptySet<String>(), PinnedConversations.toggled(added, "+82 10 1234 5678"))
    }

    @Test
    fun `toggle leaves other entries alone`() {
        val current = setOf("+821011112222", "+821033334444")

        assertEquals(
            setOf("+821011112222"),
            PinnedConversations.toggled(current, "01033334444"),
        )
        assertEquals(
            current + "+821055556666",
            PinnedConversations.toggled(current, "010-5555-6666"),
        )
    }

    @Test
    fun `a key that normalizes to nothing is not stored`() {
        val current = setOf("+821011112222")

        assertSame(current, PinnedConversations.toggled(current, "   "))
        assertSame(current, PinnedConversations.toggled(current, ""))
    }

    @Test
    fun `pins and favourites use the same key so neither reads the other's`() {
        // Separate prefs files, one key shape: a number starred in 연락처 and
        // pinned in 메시지 must not need two spellings of itself.
        assertEquals(
            Favorites.toggled(emptySet(), "010-1234-5678"),
            PinnedConversations.toggled(emptySet(), "+821012345678"),
        )
    }
}
