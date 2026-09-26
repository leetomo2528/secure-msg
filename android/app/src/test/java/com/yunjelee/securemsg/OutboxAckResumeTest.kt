package com.yunjelee.securemsg

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The post-ack pull resume in SmsBridgeService.flushOutbox.
 *
 * An upload ack deliberately does not move the pull cursor (v0.23.1), so the
 * pull must consume this phone's own echo -- and a pull that reached the echo
 * before the ack committed stops there, holding back whatever the web queued
 * behind it. The flush resumes those pulls. What these pin is the part that can
 * go wrong quietly: resuming while the outbox lock is still held, resuming a
 * thread once per row, or forgetting the acks that did commit when the flush
 * dies on a later row.
 */
class OutboxAckResumeTest {
    @Test
    fun `each acknowledged conversation is resumed once, in ack order, after the flush`() = runBlocking {
        val resumed = mutableListOf<String>()

        OutboxAckResume.run(resume = { resumed += it }) { acked ->
            acked += "sms-a"
            acked += "sms-b"
            acked += "sms-a"
            // Never from inside the flush: it still holds the outbox lock.
            assertTrue(resumed.isEmpty())
        }

        assertEquals(listOf("sms-a", "sms-b"), resumed)
    }

    @Test
    fun `resume runs with the outbox lock released`() = runBlocking {
        val outboxLock = Mutex()
        val syncLock = Mutex()
        val synced = mutableListOf<String>()

        withTimeout(5_000) {
            OutboxAckResume.run(
                resume = { cid ->
                    assertFalse("outbox lock still held while resuming", outboxLock.isLocked)
                    // The production shape: a launched pull that queues on the
                    // sync lock and never touches the outbox lock.
                    launch { syncLock.withLock { synced += cid } }
                },
            ) { acked ->
                outboxLock.withLock { acked += "sms-a" }
            }
        }

        assertEquals(listOf("sms-a"), synced)
    }

    @Test
    fun `a flush that dies partway still resumes what it did acknowledge`() = runBlocking {
        val resumed = mutableListOf<String>()

        try {
            OutboxAckResume.run(resume = { resumed += it }) { acked ->
                acked += "sms-a"
                error("the next row blew up")
            }
            fail("the flush failure must still propagate")
        } catch (e: IllegalStateException) {
            assertEquals("the next row blew up", e.message)
        }

        assertEquals(listOf("sms-a"), resumed)
    }

    @Test
    fun `nothing is resumed when nothing was acknowledged`() = runBlocking {
        val resumed = mutableListOf<String>()

        OutboxAckResume.run(resume = { resumed += it }) { }

        assertTrue(resumed.isEmpty())
    }
}
