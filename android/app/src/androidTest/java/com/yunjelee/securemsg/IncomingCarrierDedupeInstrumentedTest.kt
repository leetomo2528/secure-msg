package com.yunjelee.securemsg

import androidx.room.Room
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

@RunWith(AndroidJUnit4::class)
class IncomingCarrierDedupeInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: IncomingMessageRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = IncomingMessageRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun providerlessEventAliasesProviderIdKeepsMidAndIsDurablyDedupedAfterAck() = runBlocking {
        val phone = "+821012345678"
        val receivedAt = 1_723_456_789_000L
        val content = RelayContentCodec.text("same carrier event")

        val broadcast = repository.persistCarrier(
            kind = ProviderIdentity.SMS,
            direction = "incoming_sms",
            phoneNumber = phone,
            content = content,
            providerId = null,
            receivedAt = receivedAt,
        )
        assertNotNull(broadcast)
        assertTrue(broadcast!!.newlyCreated)
        assertNull(broadcast.outbox.providerId)
        val originalMid = broadcast.outbox.mid

        val providerObservation = repository.persistCarrier(
            kind = ProviderIdentity.SMS,
            direction = "incoming_sms",
            phoneNumber = phone,
            content = content,
            providerId = 77L,
            receivedAt = receivedAt,
        )
        assertNotNull(providerObservation)
        assertFalse(providerObservation!!.newlyCreated)
        assertEquals(originalMid, providerObservation.outbox.mid)
        assertEquals(77L, providerObservation.outbox.providerId)

        repository.acknowledgeIncoming(providerObservation.outbox)

        assertNull(db.relayOutboxDao().getByMid(originalMid))
        assertTrue(db.processedSmsDao().contains(0, 77L))
        assertTrue(
            db.processedCarrierEventDao().contains(
                ProviderIdentity.SMS,
                providerObservation.outbox.sourceEventKey!!,
            ),
        )
        assertNull(
            repository.persistCarrier(
                kind = ProviderIdentity.SMS,
                direction = "incoming_sms",
                phoneNumber = phone,
                content = content,
                providerId = null,
                receivedAt = receivedAt,
            ),
        )
    }

    @Test
    fun providerlessAckWritesEventTombstoneWithoutProviderLedger() = runBlocking {
        val persisted = repository.persistCarrier(
            kind = ProviderIdentity.MMS,
            direction = "incoming_mms",
            phoneNumber = "+821099988877",
            content = RelayContent(
                type = RelayContentCodec.TYPE_MMS,
                text = "provider-less MMS",
            ),
            providerId = null,
            receivedAt = 1_723_456_790_000L,
        )!!

        repository.acknowledgeIncoming(persisted.outbox)

        assertTrue(
            db.processedCarrierEventDao().contains(
                ProviderIdentity.MMS,
                persisted.outbox.sourceEventKey!!,
            ),
        )
        assertNull(db.relayOutboxDao().getByMid(persisted.outbox.mid))
    }
}
