package com.yunjelee.securemsg

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pull cursor belongs to the pull.
 *
 * `sms_threads.lastSeq` is the value [SmsBridgeService.syncConversation] starts
 * from when it asks the relay for history, so it means "everything at or below
 * this has been fetched and processed by this device". Acknowledging an UPLOAD
 * used to write the relay-assigned seq into it, which claimed every lower
 * sequence as processed — including a message another device had composed and
 * this gateway had not fetched yet. That message was then never offered again:
 * never put on the carrier, never stored locally, and no failure recorded
 * anywhere. It is the whole reason a text written on the web did not reach the
 * phone while a text written on the phone reached the web.
 *
 * These assertions are about the DAO contract the fix rests on, not about
 * flushOutbox's coroutine: what matters is that recording an upload and
 * advancing the pull cursor stay two separate writes, and that the cursor is
 * monotonic so nothing can quietly walk it backwards either.
 */
@RunWith(AndroidJUnit4::class)
class OutboxCursorInstrumentedTest {
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

    private suspend fun thread(cid: String): SmsThread {
        val row = SmsThread(cid = cid, phoneNumber = "+821012345678", serverName = null)
        db.threadDao().upsert(row)
        return row
    }

    @Test
    fun recordingAnUploadLeavesThePullCursorWhereItWas() = runBlocking {
        val cid = "sms-cursor"
        thread(cid)
        // The gateway has fetched and processed everything up to 457. Two
        // messages the web composed are sitting at 458 and 459, unfetched.
        db.threadDao().advanceLastSeq(cid, 457)

        val outboxId = db.relayOutboxDao().insert(
            RelayOutbox(
                mid = "11111111-2222-3333-4444-555555555555",
                cid = cid,
                payload = "",
                plaintext = RelayContentCodec.encode(RelayContentCodec.text("relayed up")),
                contentType = "text",
                subject = null,
                attachmentsJson = null,
                phoneNumber = "+821012345678",
                providerEpoch = 0,
                providerId = null,
                sourceFingerprint = null,
                sourceEventKey = null,
                localMessageId = null,
                direction = "incoming_sms",
                createdAt = 1_723_456_789_000L,
            ),
        )
        // The relay assigns 460 to this upload. Recording that must not claim
        // 458 and 459 as fetched.
        db.relayOutboxDao().markRelaySent(outboxId, 460)

        assertEquals(457, db.threadDao().get(cid)!!.lastSeq)
    }

    @Test
    fun thePullCursorOnlyEverMovesForward() = runBlocking {
        val cid = "sms-monotonic"
        thread(cid)
        db.threadDao().advanceLastSeq(cid, 120)
        // Out-of-order processing must not re-offer rows already consumed.
        db.threadDao().advanceLastSeq(cid, 80)
        assertEquals(120, db.threadDao().get(cid)!!.lastSeq)
        db.threadDao().advanceLastSeq(cid, 121)
        assertEquals(121, db.threadDao().get(cid)!!.lastSeq)
    }

    @Test
    fun anAcknowledgedUploadIsStillConsumableWhenThePullReachesIt() = runBlocking {
        // Leaving the cursor alone means the gateway pulls its own upload back.
        // That is safe only while the ack is durable evidence of a self echo,
        // which is what lets processRelayEnvelope consume it without
        // re-dispatching it to the carrier.
        val cid = "sms-selfecho"
        thread(cid)
        val outboxId = db.relayOutboxDao().insert(
            RelayOutbox(
                mid = "99999999-8888-7777-6666-555555555555",
                cid = cid,
                payload = "",
                plaintext = RelayContentCodec.encode(RelayContentCodec.text("mine")),
                contentType = "text",
                subject = null,
                attachmentsJson = null,
                phoneNumber = "+821012345678",
                providerEpoch = 0,
                providerId = null,
                sourceFingerprint = null,
                sourceEventKey = null,
                localMessageId = null,
                direction = "outgoing_sms",
                createdAt = 1_723_456_789_000L,
            ),
        )
        db.relayOutboxDao().markRelaySent(outboxId, 460)

        assertEquals(true, db.relayOutboxDao().hasAcknowledgedSequence(cid, 460))
        assertEquals(
            true,
            RelaySyncPolicy.canConsumeSelfEcho(
                hasLocalServerKey = false,
                hasAcknowledgedOutbox = db.relayOutboxDao().hasAcknowledgedSequence(cid, 460),
            ),
        )
    }
}
