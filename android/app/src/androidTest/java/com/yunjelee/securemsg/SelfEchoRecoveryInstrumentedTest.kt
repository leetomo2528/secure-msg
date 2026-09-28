package com.yunjelee.securemsg

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SM-2 against the real DAOs: an upload ack has to leave evidence that the
 * logout wipe does not take with it, or a re-login with the same sid pulls its
 * own upload back and stops there. SelfEchoRecoveryTest pins the same flow on
 * the JVM.
 */
@RunWith(AndroidJUnit4::class)
class SelfEchoRecoveryInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: IncomingMessageRepository
    private val phone = "+821012345678"
    private val cid = "sms-live"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = IncomingMessageRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun preparedIncoming(providerId: Long, text: String): RelayOutbox {
        val persisted = repository.persistCarrier(
            kind = ProviderIdentity.SMS,
            direction = "incoming_sms",
            phoneNumber = phone,
            content = RelayContentCodec.text(text),
            providerId = providerId,
            receivedAt = 1_757_000_000_000L + providerId,
        )!!
        db.relayOutboxDao().markPrepared(persisted.outbox.id, cid, "{\"ct\":\"sealed\"}")
        return db.relayOutboxDao().getById(persisted.outbox.id)!!
    }

    private suspend fun evidence(seq: Int) = RelaySyncPolicy.SelfEchoEvidence(
        hasLocalServerKey = db.messageDao().hasServerKey("$cid:$seq"),
        hasAcknowledgedOutbox = db.relayOutboxDao().hasAcknowledgedSequence(cid, seq),
        hasDurableAck = db.relayAckDao().contains(cid, seq),
        hasUnackedUpload = db.relayOutboxDao().hasUnackedUpload(cid),
    )

    @Test
    fun anAckSurvivesLogoutAndTheOwnEchoIsConsumedAfterReLogin() = runBlocking {
        val row = preparedIncoming(77, "첫 문자")
        // flushOutbox's ack transaction.
        db.withTransaction {
            db.messageDao().updateRelayResult(row.localMessageId!!, 12, "text", null, null)
            UploadAck.commit(row, 12, RoomUploadAckWrites(db, repository))
        }
        assertNull(db.relayOutboxDao().getById(row.id))

        // MainActivity's logout wipe.
        db.messageDao().clearAll()
        db.threadDao().clearAll()
        db.blockedSmsDao().clearAll()
        db.relayOutboxDao().clearSentPlaintext()

        // Re-login: the next SMS from the same number is prepared, not acked.
        preparedIncoming(78, "두 번째 문자")

        val seen = evidence(12)
        assertFalse(seen.hasLocalServerKey)
        assertFalse(seen.hasAcknowledgedOutbox)
        assertTrue(seen.hasDurableAck)
        assertTrue(seen.hasUnackedUpload)
        assertTrue(RelaySyncPolicy.canConsumeSelfEcho(seen))

        // What the pull then does with it: the cursor logout reset moves on.
        db.threadDao().upsert(SmsThread(cid = cid, phoneNumber = phone, serverName = null))
        db.threadDao().advanceLastSeq(cid, 12)
        assertEquals(12, db.threadDao().get(cid)!!.lastSeq)
    }

    @Test
    fun onlyAnUploadThatCanStillBeAckedHoldsTheConversation() = runBlocking {
        assertFalse(db.relayOutboxDao().hasUnackedUpload(cid))
        val row = preparedIncoming(90, "대기")
        assertTrue(db.relayOutboxDao().hasUnackedUpload(cid))

        db.relayOutboxDao().markUnsendable(row.id, "sender is not a carrier address")
        assertFalse(db.relayOutboxDao().hasUnackedUpload(cid))

        val outgoing = db.relayOutboxDao().insert(
            RelayOutbox(
                mid = "out-mid-0000000000000001",
                cid = cid,
                payload = "{}",
                plaintext = RelayContentCodec.encode(RelayContentCodec.text("보냄")),
                phoneNumber = phone,
                direction = "outgoing_sms",
                carrierState = "dispatched",
            ),
        )
        assertTrue(db.relayOutboxDao().hasUnackedUpload(cid))
        db.relayOutboxDao().markRelaySent(outgoing, 31)
        assertFalse(db.relayOutboxDao().hasUnackedUpload(cid))
        assertFalse(db.relayOutboxDao().hasUnackedUpload("sms-other"))
    }

    @Test
    fun relayAckedIsBoundedByAgeAndCount() = runBlocking {
        val dao = db.relayAckDao()
        dao.record(RelayAck(cid, 1, ackedAt = 100))
        dao.record(RelayAck(cid, 2, ackedAt = 200))
        dao.record(RelayAck(cid, 3, ackedAt = 300))
        dao.record(RelayAck(cid, 4, ackedAt = 400))
        // A second record for the same ack changes nothing.
        assertEquals(-1L, dao.record(RelayAck(cid, 4, ackedAt = 999)))

        assertEquals(1, dao.pruneOlderThan(150))
        assertFalse(dao.contains(cid, 1))

        assertEquals(1, dao.pruneBeyond(2))
        assertFalse(dao.contains(cid, 2))
        assertTrue(dao.contains(cid, 3))
        assertTrue(dao.contains(cid, 4))
    }

    @Test
    fun aBlankCidReceiptIsCarriedOverToItsOwnRowOnly() = runBlocking {
        // What v0.23.1 left after dispatching sms-live/3: ("", 3) and ":3".
        db.relayReceiptDao().claim(
            RelayReceipt(LegacyRelayReceipt.CID, 3, claimedAt = 1_757_000_001_000L, status = "sent"),
        )
        db.messageDao().insert(
            MessageRow(
                cid = LegacyRelayReceipt.CID,
                seq = 3,
                senderSid = "web-sid",
                plaintext = "to live",
                createdAt = 1_757_000_000_000L,
                mine = true,
                serverKey = LegacyRelayReceipt.serverKey(3),
            ),
        )
        val store = RoomLegacyReceiptStore(db)

        assertEquals(
            LegacyRelayReceipt.Decision.ADOPT,
            LegacyRelayReceipt.carryOver(
                cid, 3, LegacyRelayReceipt.Rendered(1_757_000_000_000L, "to live", "text", null, "web-sid"), store,
            ),
        )
        // The pull's own claim then finds the adopted receipt: no second send.
        assertEquals(-1L, db.relayReceiptDao().claim(RelayReceipt(cid, 3)))
        val adopted = db.relayReceiptDao().get(cid, 3)!!
        assertEquals("sent", adopted.status)
        assertFalse(adopted.statusSynced)
        assertTrue(db.relayReceiptDao().pendingStatuses().any { it.cid == cid && it.seq == 3 })

        // Another conversation's row at seq 3 is not answered by it.
        assertEquals(
            LegacyRelayReceipt.Decision.UNRELATED,
            LegacyRelayReceipt.carryOver(
                "sms-other", 3, LegacyRelayReceipt.Rendered(1_757_000_000_500L, "to other", "text", null, "web-sid"), store,
            ),
        )
        assertNull(db.relayReceiptDao().get("sms-other", 3))
        assertTrue(db.relayReceiptDao().claim(RelayReceipt("sms-other", 3)) > 0)
        // A third conversation matching the rendered row exactly cannot adopt
        // it again (hasCopyElsewhere): recorded as failed, not consumed as sent.
        assertTrue(db.relayReceiptDao().hasCopyElsewhere("sms-twin", 3, 1_757_000_001_000L))
        assertEquals(
            LegacyRelayReceipt.Decision.UNCERTAIN,
            LegacyRelayReceipt.carryOver(
                "sms-twin", 3, LegacyRelayReceipt.Rendered(1_757_000_000_000L, "to live", "text", null, "web-sid"), store,
            ),
        )
        assertEquals("failed", db.relayReceiptDao().get("sms-twin", 3)!!.status)
        // The legacy rows stay for later comparisons and late callbacks.
        assertEquals("sent", db.relayReceiptDao().get(LegacyRelayReceipt.CID, 3)!!.status)
    }
}
