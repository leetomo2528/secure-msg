package com.yunjelee.securemsg

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarrierCallbackPersistenceInstrumentedTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun completedClaimAndAllCarrierStatesCommitTogether() = runBlocking {
        val fixture = insertFixture()

        val committed = CarrierCallbackPersistence.commitCompleted(
            db,
            fixture.mid,
            CarrierStatusReceiver.ACTION_SENT,
            2,
            fixture.cid,
            fixture.seq,
        )

        assertNotNull(committed)
        assertEquals("sent", committed!!.status)
        assertEquals(emptyList<CarrierPartResult>(), partResults(fixture.mid))
        assertEquals("sent", db.relayReceiptDao().get(fixture.cid, fixture.seq)!!.status)
        assertEquals("sent", db.relayOutboxDao().getByMid(fixture.mid)!!.carrierState)
        assertEquals("sent", db.messageDao().getById(fixture.localId)!!.carrierStatus)
    }

    @Test
    fun rollbackRestoresCompletedClaimAndEveryCarrierState() = runBlocking {
        val fixture = insertFixture()

        runCatching {
            db.withTransaction {
                assertNotNull(
                    CarrierCallbackPersistence.commitCompleted(
                        db,
                        fixture.mid,
                        CarrierStatusReceiver.ACTION_SENT,
                        2,
                        fixture.cid,
                        fixture.seq,
                    ),
                )
                error("force outer transaction rollback")
            }
        }

        assertEquals(2, partResults(fixture.mid).size)
        assertNull(db.relayReceiptDao().get(fixture.cid, fixture.seq))
        assertEquals("dispatched", db.relayOutboxDao().getByMid(fixture.mid)!!.carrierState)
        assertEquals("dispatched", db.messageDao().getById(fixture.localId)!!.carrierStatus)
    }

    private suspend fun insertFixture(): Fixture {
        val mid = "callback_mid_123"
        val cid = "callback_cid"
        val seq = 42
        val localId = db.messageDao().insert(
            MessageRow(
                cid = cid,
                seq = seq,
                senderSid = "device",
                plaintext = "hello",
                createdAt = 1L,
                mine = true,
                carrierStatus = "dispatched",
            ),
        )
        db.relayOutboxDao().insert(
            RelayOutbox(
                mid = mid,
                cid = cid,
                payload = "",
                plaintext = "",
                phoneNumber = "+821012345678",
                localMessageId = localId,
                direction = "outgoing_sms",
                carrierState = "dispatched",
            ),
        )
        repeat(2) { part ->
            db.carrierPartResultDao().upsert(
                CarrierPartResult(
                    mid = mid,
                    action = CarrierStatusReceiver.ACTION_SENT,
                    part = part,
                    partCount = 2,
                    successful = true,
                    resultCode = -1,
                ),
            )
        }
        return Fixture(mid, cid, seq, localId)
    }

    private suspend fun partResults(mid: String) = db.carrierPartResultDao().getAll(
        mid,
        CarrierStatusReceiver.ACTION_SENT,
    )

    private data class Fixture(
        val mid: String,
        val cid: String,
        val seq: Int,
        val localId: Long,
    )
}
