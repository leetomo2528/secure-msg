package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sender-rule lookups used by the chat overflow menu. `evaluate` needs a Room
 * database and is covered on device; these two are pure by design so the UI can
 * call them off the composition thread — and so the "return the STORED value"
 * contract that unblocking depends on can be pinned here.
 */
class BlocklistManagerTest {

    @Test
    fun returnsTheStoredRuleValueNotTheNormalizedNumber() {
        // Removal compares rule values exactly, so a legacy 010… row must come
        // back in its stored shape or an unblock silently leaves it in place.
        val rules = listOf("01012345678")

        assertEquals(
            listOf("01012345678"),
            BlocklistManager.matchingSenderRules("+821012345678", rules),
        )
    }

    @Test
    fun matchesAcrossEveryStoredShapeOfTheSameNumber() {
        val rules = listOf("+821012345678", "010-1234-5678", "01099998888")

        val matched = BlocklistManager.matchingSenderRules("01012345678", rules)

        assertEquals(listOf("+821012345678", "010-1234-5678"), matched)
        assertTrue(BlocklistManager.senderBlocked("+82 10 1234 5678", rules))
    }

    @Test
    fun duplicateStoredValuesCollapse() {
        val rules = listOf("01012345678", "01012345678")

        assertEquals(listOf("01012345678"), BlocklistManager.matchingSenderRules("+821012345678", rules))
    }

    @Test
    fun unrelatedRulesNeverMatch() {
        val rules = listOf("01099998888", "+442025550123")

        assertEquals(emptyList<String>(), BlocklistManager.matchingSenderRules("+821012345678", rules))
        assertFalse(BlocklistManager.senderBlocked("+821012345678", rules))
        // Same nine-digit tail, different country code: suffix matching is
        // forbidden, so this must not be treated as the blocked number.
        assertFalse(BlocklistManager.senderBlocked("+12025550123", rules))
    }

    @Test
    fun blankNumbersAreNeverBlocked() {
        val rules = listOf("01012345678", "")

        assertEquals(emptyList<String>(), BlocklistManager.matchingSenderRules("", rules))
        assertEquals(emptyList<String>(), BlocklistManager.matchingSenderRules("   ", rules))
        assertFalse(BlocklistManager.senderBlocked("  ", rules))
    }

    @Test
    fun blankStoredRulesCannotBlockEveryone() {
        // An empty rule value canonicalizes to "", which SenderMatcher rejects;
        // a stray blank row must not turn into a catch-all block.
        val rules = listOf("", "   ")

        assertEquals(emptyList<String>(), BlocklistManager.matchingSenderRules("+821012345678", rules))
        assertFalse(BlocklistManager.senderBlocked("+821012345678", rules))
    }

    @Test
    fun emptyRuleSetBlocksNothing() {
        assertEquals(emptyList<String>(), BlocklistManager.matchingSenderRules("+821012345678", emptyList()))
        assertFalse(BlocklistManager.senderBlocked("+821012345678", emptyList()))
    }

    @Test
    fun alphanumericSenderIdsMatchExactlyAndKeepTheirStoredForm() {
        val rules = listOf("BANK010", "01012345678")

        assertEquals(listOf("BANK010"), BlocklistManager.matchingSenderRules("bank010", rules))
        assertFalse(BlocklistManager.senderBlocked("BANK01012345678", rules))
    }
}
