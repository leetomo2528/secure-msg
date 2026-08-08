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

    @Test
    fun pruneCandidatesReturnsLocalValuesMissingFromServer() {
        val local = listOf("광고", "스팸", "도박")
        val server = setOf("광고", "기타")
        assertEquals(listOf("스팸", "도박"), BlocklistSync.pruneCandidates(local, server))
    }

    @Test
    fun pruneCandidatesEmptyWhenServerHasEverything() {
        val local = listOf("광고", "스팸")
        assertEquals(
            emptyList<String>(),
            BlocklistSync.pruneCandidates(local, setOf("광고", "스팸")),
        )
    }

    @Test
    fun pruneCandidatesEmptyServerDropsAllLocal() {
        assertEquals(
            listOf("광고", "스팸"),
            BlocklistSync.pruneCandidates(listOf("광고", "스팸"), emptySet()),
        )
    }

    @Test
    fun pruneCandidatesPreservesOrderAndHandlesEmptyLocal() {
        assertTrue(BlocklistSync.pruneCandidates(emptyList(), setOf("광고")).isEmpty())
        assertEquals(
            listOf("다", "가", "나"),
            BlocklistSync.pruneCandidates(listOf("다", "가", "나"), setOf("라")),
        )
    }

    @Test
    fun remoteDeleteSurvivesUnrelatedLocalPushFailureAndFailedAddRetries() {
        val remoteDeleted = "A"
        val newLocalAdd = "B"
        val localBeforeSync = listOf(remoteDeleted, newLocalAdd)
        val previousBaseline = setOf("keyword|$remoteDeleted")
        val emptyServer = emptySet<String>()

        val firstPush = BlocklistSync.pushCandidates(
            local = localBeforeSync,
            type = "keyword",
            serverKeys = emptyServer,
            previouslySharedKeys = previousBaseline,
        )
        assertEquals(listOf(newLocalAdd), firstPush)

        val firstPrune = BlocklistSync.pruneCandidates(
            local = localBeforeSync,
            serverValues = emptySet(),
            failedPushValues = firstPush.toSet(),
        )
        assertEquals(listOf(remoteDeleted), firstPrune)

        val localAfterSync = localBeforeSync - firstPrune.toSet()
        assertEquals(listOf(newLocalAdd), localAfterSync)
        assertEquals(
            listOf(newLocalAdd),
            BlocklistSync.pushCandidates(
                local = localAfterSync,
                type = "keyword",
                serverKeys = emptyServer,
                previouslySharedKeys = emptyServer,
            ),
        )
    }

    @Test
    fun pushCandidatesDoesNotResurrectRemotelyDeletedRule() {
        assertEquals(
            listOf("새 로컬 규칙"),
            BlocklistSync.pushCandidates(
                local = listOf("원격 삭제 규칙", "새 로컬 규칙", "서버 규칙"),
                type = "keyword",
                serverKeys = setOf("keyword|서버 규칙"),
                previouslySharedKeys = setOf("keyword|원격 삭제 규칙", "keyword|서버 규칙"),
            ),
        )
    }

    @Test
    fun pushCandidatesKeepsNewLocalRowAbsentFromStaleServerSnapshot() {
        assertEquals(
            listOf("새 번호"),
            BlocklistSync.pushCandidates(
                local = listOf("새 번호"),
                type = "sender",
                serverKeys = emptySet(),
                previouslySharedKeys = emptySet(),
            ),
        )
    }

    @Test
    fun explicitLocalReAddOverridesPreviousServerBaseline() {
        assertEquals(
            listOf("다시 추가"),
            BlocklistSync.pushCandidates(
                local = listOf("다시 추가", "다시 추가"),
                type = "keyword",
                serverKeys = emptySet(),
                previouslySharedKeys = setOf("keyword|다시 추가"),
                locallyAddedKeys = setOf("keyword|다시 추가"),
            ),
        )
    }

    @Test
    fun newSenderRulesCanonicalizeWithoutRewritingParsedLegacyRules() {
        assertEquals(
            "+821012345678",
            BlocklistSync.canonicalRuleValue("sender", "010-1234-5678"),
        )
        assertEquals("광고", BlocklistSync.canonicalRuleValue("keyword", "광고"))

        val legacy = BlocklistSync.parseRules(
            """{"rules":[{"id":7,"type":"sender","value":"01012345678"}]}""",
        )
        assertEquals(listOf("01012345678"), legacy.senders)
        assertEquals(7L, legacy.ids["sender|01012345678"])
    }
}
