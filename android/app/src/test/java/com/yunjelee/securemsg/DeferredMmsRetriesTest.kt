package com.yunjelee.securemsg

import com.yunjelee.securemsg.DeferredMmsRetries.Decision
import com.yunjelee.securemsg.DeferredMmsRetries.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-id bookkeeping behind SmsBridgeService's deferred MMS retries.
 *
 * The defect it closes: the budget counted calls, not queued retries. The live
 * broadcast, every sweep that saw the row and every retry each spent a unit
 * and launched a coroutine of its own, so a live MMS whose photo was still
 * landing, met by the broadcast, an app foreground and a relay reconnect in
 * its first seconds, had the whole budget gone at once -- and the first retry
 * stored it with a missing-photo notice fifteen seconds in, not after the ~5
 * minutes the backoff spans. The forked chains also carried the sweeps' rescan
 * flag, so the live message could be stored by one of those and lose its alert.
 */
class DeferredMmsRetriesTest {
    private val delays = longArrayOf(15_000L, 60_000L, 240_000L)

    @Test
    fun `the first deferral queues a retry after the first delay`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 512)

        val ticket = launched(retries.schedule(ID, rescan = false))

        assertEquals(ID, ticket.id)
        assertEquals(15_000L, ticket.delayMs)
    }

    @Test
    fun `callers that find a retry queued join it, spending nothing and launching nothing`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        val ticket = launched(retries.schedule(ID, rescan = false))

        // App foregrounded, relay reconnected: two sweeps meet the row.
        assertSame(Decision.Joined, retries.schedule(ID, rescan = true))
        assertSame(Decision.Joined, retries.schedule(ID, rescan = true))

        retries.begin(ticket)
        val next = launched(retries.schedule(ID, rescan = false))
        assertEquals("the joins spent no budget: this is only the second retry", 60_000L, next.delayMs)
    }

    @Test
    fun `three callers in the first seconds still get the whole backoff, not fifteen seconds`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        val waited = mutableListOf<Long>()

        // t0 live broadcast, +2s app foregrounded, +4s relay reconnect.
        var ticket = launched(retries.schedule(ID, rescan = false))
        retries.schedule(ID, rescan = true)
        retries.schedule(ID, rescan = true)

        // Each retry still finds the photo unreadable and queues the next link.
        while (true) {
            waited += ticket.delayMs
            retries.begin(ticket)
            val next = retries.schedule(ID, rescan = false)
            if (next is Decision.Refused) {
                assertEquals(Refusal.SPENT, next.reason)
                break
            }
            ticket = launched(next)
        }

        assertEquals(listOf(15_000L, 60_000L, 240_000L), waited)
    }

    @Test
    fun `live wins - a sweep's retry that a broadcast joins runs live`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        val ticket = launched(retries.schedule(ID, rescan = true))

        retries.schedule(ID, rescan = false)

        assertFalse("runs as the live message it is", retries.begin(ticket))
    }

    @Test
    fun `live wins - a sweep joining a live retry does not demote it`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        val ticket = launched(retries.schedule(ID, rescan = false))

        retries.schedule(ID, rescan = true)

        assertFalse(retries.begin(ticket))
    }

    @Test
    fun `a sweep's own retry that nobody live joins stays a rescan`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        val ticket = launched(retries.schedule(ID, rescan = true))

        assertTrue(retries.begin(ticket))
    }

    @Test
    fun `a retry that begins leaves the queue, so its run can queue the next link`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        val first = launched(retries.schedule(ID, rescan = false))

        retries.begin(first)
        val second = launched(retries.schedule(ID, rescan = false))

        assertNotSame(first, second)
        assertEquals(60_000L, second.delayMs)
    }

    @Test
    fun `a retry cancelled before it began leaves the queue too`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        val cancelled = launched(retries.schedule(ID, rescan = false))

        retries.abandon(cancelled)

        // Not joined to a coroutine that will never run.
        assertTrue(retries.schedule(ID, rescan = false) is Decision.Launch)
    }

    @Test
    fun `a finished retry's abandon cannot take its successor off the queue`() {
        // The retry coroutine's finally always abandons; after begin that must
        // be a no-op even though its run queued the next link for the same id.
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        val first = launched(retries.schedule(ID, rescan = false))
        retries.begin(first)
        launched(retries.schedule(ID, rescan = false))

        retries.abandon(first)

        assertSame(Decision.Joined, retries.schedule(ID, rescan = true))
        assertTrue(retries.queuedLive(ID))
    }

    @Test
    fun `a full table refuses a new id but not one it already tracks`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 1)
        val tracked = launched(retries.schedule(ID, rescan = false))

        val refused = retries.schedule(ID + 1, rescan = false)
        assertEquals(Refusal.TABLE_FULL, (refused as Decision.Refused).reason)

        retries.begin(tracked)
        assertTrue(retries.schedule(ID, rescan = false) is Decision.Launch)
    }

    @Test
    fun `the budget is never given back`() {
        // A permanently unreadable part must end with a notice, not reschedule
        // itself forever, however its retries ended.
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        repeat(3) {
            val ticket = launched(retries.schedule(ID, rescan = false))
            if (it % 2 == 0) retries.abandon(ticket) else retries.begin(ticket)
        }

        val refused = retries.schedule(ID, rescan = false)

        assertEquals(Refusal.SPENT, (refused as Decision.Refused).reason)
    }

    @Test
    fun `queuedLive answers only for a live retry still waiting`() {
        val retries = DeferredMmsRetries(delays, trackedMax = 512)
        assertFalse(retries.queuedLive(ID))

        val sweep = launched(retries.schedule(ID, rescan = true))
        assertFalse("a sweep's retry does not make the row live", retries.queuedLive(ID))

        retries.schedule(ID, rescan = false)
        assertTrue(retries.queuedLive(ID))

        retries.begin(sweep)
        assertFalse("once it runs, its own flag decides", retries.queuedLive(ID))
    }

    private fun launched(decision: Decision): DeferredMmsRetries.Ticket =
        (decision as? Decision.Launch)?.ticket ?: throw AssertionError("expected a launch, got $decision")

    private companion object {
        const val ID = 42L
    }
}
