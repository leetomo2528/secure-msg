package com.yunjelee.securemsg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinNoticeTest {
    @Test
    fun pinningThePlainListPromisesTheTop() {
        val text = pinNoticeText(wasPinned = false, searching = false)
        assertTrue(text, text.contains("맨 위"))
    }

    @Test
    fun pinningFromSearchResultsDefersThePromiseToTheClearedQuery() {
        // The search branch never applies ConversationOrder.pinnedFirst, so the
        // row on screen does not move; claiming it just did would be a lie the
        // user is looking straight at.
        val text = pinNoticeText(wasPinned = false, searching = true)
        assertTrue(text, text.contains("검색을 지우면"))
        assertNotEquals(pinNoticeText(wasPinned = false, searching = false), text)
    }

    @Test
    fun unpinningReadsTheSameInBothModes() {
        val plain = pinNoticeText(wasPinned = true, searching = false)
        assertEquals(plain, pinNoticeText(wasPinned = true, searching = true))
        assertTrue(plain, plain.contains("해제"))
        assertTrue(plain, !plain.contains("맨 위"))
    }
}
