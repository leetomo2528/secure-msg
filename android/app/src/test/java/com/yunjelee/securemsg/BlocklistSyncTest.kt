package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistSyncTest {

    @Test
    fun parsesServerRulesByType() {
        val raw = """
            {"rules":[
              {"id":1,"type":"keyword","value":"광고","created_at":1},
              {"id":2,"type":"sender","value":"+821012345678","created_at":2},
              {"id":3,"type":"keyword","value":"스팸","created_at":3}
            ]}
        """.trimIndent()
        val rules = BlocklistSync.parseRules(raw)
        assertEquals(listOf("광고", "스팸"), rules.keywords)
        assertEquals(listOf("+821012345678"), rules.senders)
        assertEquals(1L, rules.ids["keyword|광고"])
        assertEquals(2L, rules.ids["sender|+821012345678"])
    }

    @Test
    fun blankValuesAreDropped() {
        val raw = """{"rules":[{"id":1,"type":"keyword","value":"  ","created_at":1}]}"""
        val rules = BlocklistSync.parseRules(raw)
        assertTrue(rules.keywords.isEmpty())
        assertTrue(rules.senders.isEmpty())
    }

    @Test
    fun malformedJsonYieldsEmptyRules() {
        val rules = BlocklistSync.parseRules("not-json")
        assertTrue(rules.keywords.isEmpty())
        assertTrue(rules.senders.isEmpty())
        assertTrue(rules.ids.isEmpty())
    }
}
