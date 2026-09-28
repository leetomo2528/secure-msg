package com.yunjelee.securemsg

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * SM-1 against real SQLite and a real CursorWindow.
 *
 * OutboxRowBudgetTest pins the arithmetic on the JVM. What only a device can
 * show is that the rows really read back: a 512 KiB MMS with the longest text,
 * prepared with a real envelope, through every outbox read the flush makes;
 * that a legacy row no window can hold neither hides the rest of the page nor
 * survives the 13 -> 14 migration; and that the migration leaves a schema Room
 * accepts.
 */
@RunWith(AndroidJUnit4::class)
class OutboxRowBudgetInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun maxMms(): RelayContent {
        val bytes = RelayContentCodec.MAX_ATTACHMENT_BYTES
        return RelayContent(
            type = RelayContentCodec.TYPE_MMS,
            text = "가".repeat(20_000),
            subject = "제목",
            attachments = listOf(
                RelayAttachment(
                    name = "photo.jpg",
                    contentType = "image/jpeg",
                    data = RelayContentCodec.encodeBytes(Random(11).nextBytes(bytes)),
                    size = bytes,
                ),
            ),
        )
    }

    private fun envelopeFor(plaintext: String): String {
        val sender = CryptoUtil.generateKeypair()
        val recipients = (1..3).map { CryptoUtil.Recipient("sid-$it", CryptoUtil.generateKeypair().boxPk) }
        return CryptoUtil.envelopeToJson(CryptoUtil.encryptMessage(plaintext, recipients, sender)).toString()
    }

    @Test
    fun aPreparedMaxSizeMmsReadsBackThroughEveryOutboxQuery() = runBlocking {
        val persisted = IncomingMessageRepository(db).persistCarrier(
            kind = ProviderIdentity.MMS,
            direction = "incoming_mms",
            phoneNumber = "+821012345678",
            content = maxMms(),
            providerId = 901L,
            receivedAt = 1_757_000_000_000L,
        )!!
        val row = persisted.outbox
        assertNull(row.attachmentsJson)
        // The visible row keeps its attachments; only the outbox copy is gone.
        assertNotNull(db.messageDao().getById(row.localMessageId!!)!!.attachmentsJson)

        val payload = envelopeFor(row.plaintext)
        db.relayOutboxDao().markPrepared(row.id, "sms-budget", payload)

        val ref = db.relayOutboxDao().pendingRefs(Long.MAX_VALUE).single()
        assertEquals(row.id, ref.id)
        val full = db.relayOutboxDao().getById(ref.id)!!
        assertEquals(payload, full.payload)
        assertEquals(OutboxRowBudget.largeColumnBytes(full), ref.largeColumnBytes)
        assertTrue("row=${ref.largeColumnBytes}", OutboxRowBudget.fits(ref.largeColumnBytes))
        assertEquals(row.id, db.relayOutboxDao().getByMid(row.mid)!!.id)
    }

    private fun legacyRow(mid: String, bigColumn: String?) = RelayOutbox(
        mid = mid,
        cid = "sms-legacy",
        payload = bigColumn ?: "{}",
        plaintext = bigColumn ?: RelayContentCodec.encode(RelayContentCodec.text("small")),
        attachmentsJson = bigColumn,
        phoneNumber = "+821012345678",
        direction = "incoming_mms",
        createdAt = if (bigColumn != null) 1L else 2L,
    )

    @Test
    fun aRowNoWindowCanHoldNeitherHidesThePageNorSurvivesTheMigration() = runBlocking {
        // What v0.23.1 left behind: three ~0.8 MB columns in one row.
        val big = "A".repeat(800 * 1024)
        val bigId = db.relayOutboxDao().insert(legacyRow("legacy-big-row-0001", big))
        val smallId = db.relayOutboxDao().insert(legacyRow("legacy-small-row-02", null))

        val refs = db.relayOutboxDao().pendingRefs(Long.MAX_VALUE)
        assertEquals(listOf(bigId, smallId), refs.map { it.id })
        assertFalse(OutboxRowBudget.fits(refs.first().largeColumnBytes))

        val skipped = mutableListOf<Long>()
        val cursor = OutboxRowCursor(
            refs = refs,
            read = { id -> db.relayOutboxDao().getById(id) },
            skip = { ref, reason -> skipped += ref.id; db.relayOutboxDao().recordAttempt(ref.id, reason) },
        )
        assertEquals(smallId, cursor.next()!!.id)
        assertNull(cursor.next())
        assertEquals(listOf(bigId), skipped)

        AppDatabase.MIGRATION_13_14.migrate(db.openHelper.writableDatabase)

        val healed = db.relayOutboxDao().getById(bigId)!!
        assertNull(healed.attachmentsJson)
        assertEquals(big, healed.payload)
        assertEquals(1, healed.attempts)
        assertEquals(OutboxRowCursor.OVERSIZED, healed.lastError)
    }

    /**
     * A v13 file is the v14 schema minus what 13 -> 14 creates, so it is built
     * from Room's own v14 schema and stepped back; Room then runs the real
     * migration on open and validates every table against the entities.
     */
    @Test
    fun migrationThirteenToFourteenNullsTheAttachmentCopyAndKeepsEveryRow() {
        val name = "outbox-migration-${System.nanoTime()}.db"
        try {
            Room.databaseBuilder(context, AppDatabase::class.java, name).build().apply {
                openHelper.writableDatabase
                close()
            }
            val big = "B".repeat(800 * 1024)
            raw(name, 14).apply {
                writableDatabase.apply {
                    stepBackToThirteen(this)
                    execSQL(
                        "INSERT INTO relay_outbox(id,mid,cid,payload,plaintext,contentType,subject," +
                            "attachmentsJson,phoneNumber,providerEpoch,providerId,sourceFingerprint," +
                            "sourceEventKey,localMessageId,direction,carrierState,carrierStatusPending," +
                            "relayState,serverSeq,attempts,lastError,createdAt) VALUES " +
                            "(7,'legacy-mid-byte-exact','sms-live',?,?,'mms',NULL,?,'+821012345678'," +
                            "0,42,'fp','ek',9,'incoming_mms','not_applicable',0,'pending',NULL,3,'offline',333)",
                        arrayOf<Any?>(big, big, big),
                    )
                }
                close()
            }

            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_13_14)
                .allowMainThreadQueries()
                .build()
            try {
                runBlocking {
                    val row = migrated.relayOutboxDao().getById(7)!!
                    assertNull(row.attachmentsJson)
                    assertEquals("legacy-mid-byte-exact", row.mid)
                    assertEquals(big, row.payload)
                    assertEquals(big, row.plaintext)
                    assertEquals(3, row.attempts)
                    assertEquals("pending", row.relayState)
                    assertEquals(42L, row.providerId)
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    /** Undo what 13 -> 14 adds so the file reads as a v13 install. */
    private fun stepBackToThirteen(db: SupportSQLiteDatabase) {
        db.version = 13
    }

    private fun raw(name: String, version: Int): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
}
