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
