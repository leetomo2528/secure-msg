package com.yunjelee.securemsg

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/**
 * End-to-end cover for the sync orchestration.
 *
 * BlocklistSyncTest can only reach the pure helpers: what decides whether a
 * rule survives is the order of push, re-pull, prune and cache-write around
 * them, and that path reads AppDatabase and SharedPreferences directly. Those
 * are Android, so this is where it can be driven — a rule wrongly pruned here
 * is a sender the user blocked who starts getting through again.
 */
@RunWith(AndroidJUnit4::class)
class BlocklistSyncInstrumentedTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private var previousDatabase: AppDatabase? = null

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        // The shared-rule cache is real SharedPreferences on the Context that
        // is passed in, and the Room rows come from the AppDatabase singleton
        // that syncLocked resolves for itself. Both are redirected here, or the
        // test would rewrite the block rules of the account installed on the
        // phone it runs on.
        context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences("$name.instrumented-test", mode)
        }
        db = Room.inMemoryDatabaseBuilder(target, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        previousDatabase = installDatabase(db)
        BlocklistSync.clear(context)
        server = MockWebServer()
        // Bound to the literal loopback address: the manifest's network
        // security config permits cleartext for 127.0.0.1 and localhost only,
        // and what "localhost" canonicalises to varies by device.
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
        BlocklistSync.clear(context)
        installDatabase(previousDatabase)
        db.close()
    }

    @Test
    fun aRuleTheRelayRefusesIsKeptLocallyAndPushedAgainNextSync() = runBlocking {
        db.blocklistDao().insert(BlockKeyword(keyword = "광고"))
        enqueueRules()
        enqueueBody("""{"ok":false,"error":"invalid rule"}""")
        enqueueRules()

        BlocklistSync.sync(context, api())

        // A refused push must not read as "deleted on another device": the row
        // is the only copy left of a rule the user asked for.
        assertEquals(listOf("광고"), keywords())
        assertEquals(emptyMap<String, Long>(), BlocklistSync.load(context).ids)

        enqueueRules()
        enqueueBody("""{"ok":true}""")
        enqueueRules(rule(9, "keyword", "광고"))

        BlocklistSync.sync(context, api())

        assertEquals(listOf("광고"), keywords())
        assertEquals(mapOf("keyword|광고" to 9L), BlocklistSync.load(context).ids)
    }

    @Test
    fun aRuleDeletedOnAnotherDeviceIsPrunedAndNotPushedBack() = runBlocking {
        db.blocklistDao().insert(BlockKeyword(keyword = "광고"))
        enqueueRules(rule(1, "keyword", "광고"))
        enqueueRules(rule(1, "keyword", "광고"))

        BlocklistSync.sync(context, api())
        assertEquals(mapOf("keyword|광고" to 1L), BlocklistSync.load(context).ids)

        enqueueRules()
        enqueueRules()

        BlocklistSync.sync(context, api())

        assertEquals(emptyList<String>(), keywords())
        assertEquals(emptyMap<String, Long>(), BlocklistSync.load(context).ids)
        // Nothing was pushed at any point: a resurrecting POST is the bug the
        // cached baseline exists to prevent (issue #1).
        assertEquals(emptyList<String>(), recordedMethods().filter { it != "GET" })
    }

    @Test
    fun aFailedRePullLeavesTheBaselineAndTheLocalRowsAlone() = runBlocking {
        db.blocklistDao().insert(BlockKeyword(keyword = "광고"))
        enqueueRules(rule(1, "keyword", "광고"))
        enqueueRules(rule(1, "keyword", "광고"))
        BlocklistSync.sync(context, api())

        // The re-pull is what the new cache is written from. Committing a prune
        // against a server set this sync never managed to read would drop both
        // the row and the baseline that keeps it from being pushed back.
        enqueueRules()
        server.enqueue(MockResponse().setResponseCode(500))

        assertFalse(BlocklistSync.sync(context, api()))

        assertEquals(listOf("광고"), keywords())
        assertEquals(mapOf("keyword|광고" to 1L), BlocklistSync.load(context).ids)
    }

    private suspend fun keywords(): List<String> = db.blocklistDao().getAll().map { it.keyword }

    private fun api() = RelayApi("http://127.0.0.1:${server.port}", OkHttpClient())

    private fun rule(id: Long, type: String, value: String) =
        """{"id":$id,"type":"$type","value":"$value"}"""

    private fun enqueueRules(vararg rules: String) {
        enqueueBody("""{"ok":true,"rules":[${rules.joinToString(",")}]}""")
    }

    private fun enqueueBody(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }

    private fun recordedMethods(): List<String> =
        (0 until server.requestCount).map { server.takeRequest().method.orEmpty() }

    private fun installDatabase(database: AppDatabase?): AppDatabase? {
        val field = AppDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        val previous = field.get(null) as AppDatabase?
        field.set(null, database)
        return previous
    }
}
