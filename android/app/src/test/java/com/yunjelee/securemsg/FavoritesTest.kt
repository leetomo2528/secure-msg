package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesTest {
    @Test
    fun toggleAddsThenRemovesTheSameKey() {
        val added = Favorites.toggled(emptySet(), "+821012345678")
        assertEquals(setOf("+821012345678"), added)

        val removed = Favorites.toggled(added, "+821012345678")
        assertEquals(emptySet<String>(), removed)
    }

    @Test
    fun toggleNormalizesSoAddressBookAndThreadFormsAgree() {
        val added = Favorites.toggled(emptySet(), "010-1234-5678")
        assertEquals(setOf("+821012345678"), added)

        assertEquals(emptySet<String>(), Favorites.toggled(added, "+82 10 1234 5678"))
    }

    @Test
    fun toggleLeavesOtherEntriesAloneAndIgnoresBlankKeys() {
        val current = setOf("+821011112222", "+821033334444")

        assertEquals(
            setOf("+821011112222"),
            Favorites.toggled(current, "01033334444"),
        )
        assertEquals(current, Favorites.toggled(current, "   "))
    }
}
