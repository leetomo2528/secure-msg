package com.yunjelee.securemsg

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The identity/payload split, against a real Room database.
 *
 * An incoming MMS is *identified* by the provider's own part list and
 * *rendered* from a second content object carrying photos re-encoded down to a
 * relayable size plus a Korean line naming what could not be carried. The
 * whole design rests on one claim: nothing the payload touches can reach the
 * mid, the source fingerprint or the event key. If that claim is wrong, the
 * first unattended update re-keys every message already in the processed
 * ledger and the gateway relays the owner a duplicate of their own history.
 *
 * RelayContentTest pins the pure encoding half. This is the durable half,
 * which needs the DAOs and therefore a device.
 */
@RunWith(AndroidJUnit4::class)
class IncomingPayloadSplitInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: IncomingMessageRepository

    private val phone = "+821012345678"
    private val receivedAt = 1_723_456_791_000L

    /** What the provider's part list produced: the oversized photo is not in it. */
    private val identity = RelayContent(
        type = RelayContentCodec.TYPE_MMS,
        text = "사진 봐",
        subject = "가족",
    )

    /** What the devices render: the photo, shrunk, plus the notice for a lost clip. */
    private val payload = identity.copy(
        text = "사진 봐\n[동영상 1개는 받지 못했습니다]",
        attachments = listOf(
            RelayAttachment(
                name = "photo.jpg",
                contentType = "image/jpeg",
                data = RelayContentCodec.encodeBytes(ByteArray(4096) { 0x5A }),
                size = 4096,
            ),
        ),
    )

    @Before
    fun setUp() {
        db = newDatabase()
        repository = IncomingMessageRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun aDivergingPayloadLeavesTheIdentityKeysExactlyWhereTheyWere() = runBlocking {
        val withPayload = persist(repository, payload)!!

        // The control: the same carrier event persisted the way every build
        // before the split persisted it, in a database of its own.
        val controlDb = newDatabase()
        val control = try {
            persist(IncomingMessageRepository(controlDb), identity)!!
        } finally {
            controlDb.close()
        }

        assertEquals(control.outbox.mid, withPayload.outbox.mid)
        assertEquals(control.outbox.sourceFingerprint, withPayload.outbox.sourceFingerprint)
        assertEquals(control.outbox.sourceEventKey, withPayload.outbox.sourceEventKey)
    }

    @Test
    fun theRelayedBodyAndTheVisibleRowCarryThePayload() = runBlocking {
        val persisted = persist(repository, payload)!!

        val relayed = RelayContentCodec.decode(persisted.outbox.plaintext)
        assertEquals(payload.text, relayed.text)
        assertEquals(1, relayed.attachments.size)
        assertEquals(4096, relayed.attachments[0].size)
        // The sealed direction still rides along; the split did not displace it.
        assertEquals(RelayContentCodec.DIR_IN, relayed.direction)

        val row = db.messageDao().getById(persisted.outbox.localMessageId!!)!!
        assertEquals(payload.text, row.plaintext)
        assertEquals(RelayContentCodec.TYPE_MMS, row.contentType)
        assertTrue(row.attachmentsJson!!.contains("image/jpeg"))
    }

    @Test
    fun aMessageAlreadyAcknowledgedStaysAcknowledgedWhenThePayloadChanges() = runBlocking {
        // Exactly the upgrade boundary: the old build processed this provider
        // row, and the new one now produces a richer payload for the same
        // event. It must not relay a second time.
        val first = persist(repository, identity)!!
        repository.acknowledgeIncoming(first.outbox)

        assertNull(persist(repository, payload))
    }

    private fun newDatabase(): AppDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private suspend fun persist(
        repository: IncomingMessageRepository,
        payload: RelayContent,
    ): IncomingMessageRepository.Persisted? = repository.persistCarrier(
        kind = ProviderIdentity.MMS,
        direction = "incoming_mms",
        phoneNumber = phone,
        content = identity,
        providerId = 501L,
        receivedAt = receivedAt,
        payload = payload,
    )
}
