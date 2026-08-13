package com.yunjelee.securemsg

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrustMigrationInstrumentedTest {
    @Test
    fun migrationNineToTenPreservesExistingRowsAndCreatesTrustTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "trust-migration-${System.nanoTime()}.db"
        try {
            val v9 = helper(name, 9, object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE preserved_fixture(id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
                    db.execSQL("INSERT INTO preserved_fixture(id,body) VALUES(7,'v9-message')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            v9.writableDatabase
            v9.close()

            val v10 = helper(name, 10, object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(9, oldVersion)
                    assertEquals(10, newVersion)
                    AppDatabase.MIGRATION_9_10.migrate(db)
                }
            })
            val db = v10.writableDatabase
            db.query("SELECT body FROM preserved_fixture WHERE id=7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("v9-message", cursor.getString(0))
            }
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND " +
                    "name IN ('trusted_device_pins','trust_directory_state') ORDER BY name",
            ).use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(0)
                assertEquals(listOf("trust_directory_state", "trusted_device_pins"), names)
            }
            v10.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationTenToElevenPreservesProviderLedgersAndPendingOutbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "provider-migration-${System.nanoTime()}.db"
        try {
            val v10 = helper(name, 10, object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE processed_sms(" +
                            "providerId INTEGER NOT NULL PRIMARY KEY, processedAt INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE processed_mms(" +
                            "providerId INTEGER NOT NULL PRIMARY KEY, processedAt INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE relay_outbox(" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                            "mid TEXT NOT NULL,cid TEXT NOT NULL,payload TEXT NOT NULL,plaintext TEXT NOT NULL," +
                            "contentType TEXT NOT NULL,subject TEXT,attachmentsJson TEXT,phoneNumber TEXT NOT NULL," +
                            "providerId INTEGER,localMessageId INTEGER,direction TEXT NOT NULL," +
                            "carrierState TEXT NOT NULL,carrierStatusPending INTEGER NOT NULL," +
                            "relayState TEXT NOT NULL,serverSeq INTEGER,attempts INTEGER NOT NULL," +
                            "lastError TEXT,createdAt INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE UNIQUE INDEX index_relay_outbox_mid ON relay_outbox(mid)")
                    db.execSQL(
                        "CREATE INDEX index_relay_outbox_relayState_createdAt " +
                            "ON relay_outbox(relayState,createdAt)",
                    )
                    db.execSQL("INSERT INTO processed_sms VALUES(42,111)")
                    db.execSQL("INSERT INTO processed_mms VALUES(42,222)")
                    db.execSQL(
                        "INSERT INTO relay_outbox(" +
                            "id,mid,cid,payload,plaintext,contentType,subject,attachmentsJson," +
                            "phoneNumber,providerId,localMessageId,direction,carrierState," +
                            "carrierStatusPending,relayState,serverSeq,attempts,lastError,createdAt) " +
                            "VALUES(7,'legacy-mid-byte-exact','local_cid','opaque-payload','local-cleartext'," +
                            "'text',NULL,NULL,'+821012345678',42,9,'incoming_sms','not_applicable'," +
                            "0,'pending',NULL,3,'offline',333)",
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            v10.writableDatabase
            v10.close()

            val v11 = helper(name, 11, object : SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(10, oldVersion)
                    assertEquals(11, newVersion)
                    AppDatabase.MIGRATION_10_11.migrate(db)
                }
            })
            val db = v11.writableDatabase
            for (table in listOf("processed_sms", "processed_mms")) {
                db.query("SELECT providerEpoch,providerId,sourceFingerprint,processedAt FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                    assertEquals(42L, cursor.getLong(1))
                    assertTrue(cursor.isNull(2))
                    assertEquals(if (table == "processed_sms") 111L else 222L, cursor.getLong(3))
                }
            }
            db.query(
                "SELECT mid,payload,plaintext,providerEpoch,providerId,sourceFingerprint,sourceEventKey " +
                    "FROM relay_outbox WHERE id=7",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-mid-byte-exact", cursor.getString(0))
                assertEquals("opaque-payload", cursor.getString(1))
                assertEquals("local-cleartext", cursor.getString(2))
                assertEquals(0L, cursor.getLong(3))
                assertEquals(42L, cursor.getLong(4))
                assertTrue(cursor.isNull(5))
                assertTrue(cursor.isNull(6))
            }
            db.query("SELECT kind,epoch FROM carrier_provider_state ORDER BY kind").use { cursor ->
                val states = mutableListOf<Pair<String, Long>>()
                while (cursor.moveToNext()) states += cursor.getString(0) to cursor.getLong(1)
                assertEquals(listOf("mms" to 0L, "sms" to 0L), states)
            }
            db.query(
                "SELECT sql FROM sqlite_master WHERE type='table' AND " +
                    "name='processed_carrier_events'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val sql = cursor.getString(0)
                assertTrue(sql.contains("kind TEXT NOT NULL"))
                assertTrue(sql.contains("eventKey TEXT NOT NULL"))
                assertTrue(sql.contains("processedAt INTEGER NOT NULL"))
                assertTrue(sql.contains("PRIMARY KEY(kind, eventKey)"))
            }
            v11.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun helper(
        name: String,
        version: Int,
        callback: SupportSQLiteOpenHelper.Callback,
    ): SupportSQLiteOpenHelper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build(),
        )
    }
}
