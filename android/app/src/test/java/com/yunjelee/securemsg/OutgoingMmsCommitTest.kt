package com.yunjelee.securemsg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The persist -> fit -> dispatch -> mark sequence of an outgoing photo send.
 *
 * The property that matters is the outbox drain's view: it re-dispatches any
 * outgoing row still 'unknown' 30 s after creation. So after the caller is
 * cancelled at any point once the row exists, the carrier must have been called
 * exactly once and the row must have left 'unknown' — otherwise the drain sends
 * the same MMS again (or the row is only ever sent late, by the drain).
 */
class OutgoingMmsCommitTest {
    /** Stand-in for the outbox row: what the drain would read. */
    private class Row {
        var persisted = false
        var carrierState = "none"
        var lastError: String? = null
        var dispatches = 0
    }

    private suspend fun commit(
        row: Row,
        fit: OutgoingMmsCommit.Fit<String> = OutgoingMmsCommit.Fit.Ready("pdu"),
        persistHook: suspend () -> Unit = {},
        dispatchHook: suspend () -> Boolean = { true },
        markHook: suspend () -> Unit = {},
    ): OutgoingMmsCommit.Result = OutgoingMmsCommit.run(
        persist = {
            persistHook()
            row.persisted = true
            row.carrierState = "unknown"
            42L
        },
        fit = { key ->
            assertEquals(42L, key)
            fit
        },
        dispatch = { _, ready ->
            assertEquals("pdu", ready)
            row.dispatches++
            dispatchHook()
        },
        markFailed = { _, reason ->
            markHook()
            row.carrierState = "failed"
            row.lastError = reason
        },
        markDispatched = {
            // A suspension point, as every Room DAO call is: without the
            // NonCancellable guard this is where a cancelled caller stops.
            markHook()
            row.carrierState = "dispatched"
        },
    )

    private fun assertResolvedOnce(row: Row) {
        assertTrue("row must exist", row.persisted)
        assertEquals("carrier must be called exactly once", 1, row.dispatches)
        assertEquals("dispatched", row.carrierState)
    }

    @Test
    fun cancellingTheCallerWhileTheCarrierCallIsInFlightStillMarksTheRow() = runBlocking {
        val row = Row()
        val inDispatch = CompletableDeferred<Unit>()
        val carrierAnswers = CompletableDeferred<Unit>()
        val caller = launch(Dispatchers.Default) {
            commit(
                row,
                dispatchHook = {
                    inDispatch.complete(Unit)
                    // Suspended inside the carrier call when the pane goes away.
                    carrierAnswers.await()
                    true
                },
                markHook = { yield() },
            )
        }
        inDispatch.await()
        caller.cancel()
        carrierAnswers.complete(Unit)
        caller.join()
        assertTrue(caller.isCancelled)
        // The caller was cancelled, but the work under it finished: one
        // carrier call, and the row is off 'unknown'.
        assertResolvedOnce(row)
    }

    @Test
    fun cancellingTheCallerJustAfterTheCarrierCallReturnsStillMarksTheRow() = runBlocking {
        val row = Row()
        lateinit var caller: Job
        caller = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            commit(
                row,
                dispatchHook = {
                    // The narrow window of the report: the carrier has it, and
                    // the cancellation lands before the marker is written.
                    caller.cancel()
                    true
                },
                markHook = { yield() },
            )
        }
        caller.start()
        caller.join()
        assertTrue(caller.isCancelled)
        assertResolvedOnce(row)
    }

    @Test
    fun cancellingTheCallerDuringTheWriteStillDispatchesTheCommittedRow() = runBlocking {
        val row = Row()
        lateinit var caller: Job
        caller = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            commit(
                row,
                persistHook = {
                    caller.cancel()
                    yield()
                },
                markHook = { yield() },
            )
        }
        caller.start()
        caller.join()
        assertResolvedOnce(row)
    }

    @Test
    fun aRowTheCarrierCeilingRefusesIsMarkedFailedWithoutACarrierCall() = runBlocking {
        val row = Row()
        val result = commit(row, fit = OutgoingMmsCommit.Fit.TooLarge("너무 큽니다"))
        assertEquals(OutgoingMmsCommit.Result.TooLarge("너무 큽니다"), result)
        assertEquals(0, row.dispatches)
        assertEquals("failed", row.carrierState)
        assertEquals("너무 큽니다", row.lastError)
    }

    @Test
    fun aCarrierThatWillNotTakeItLeavesTheSharedFailedMarker() = runBlocking {
        val row = Row()
        val result = commit(row, dispatchHook = { false })
        assertSame(OutgoingMmsCommit.Result.Rejected, result)
        assertEquals(1, row.dispatches)
        assertEquals("failed", row.carrierState)
        // The same string the text path and the outbox drain write.
        assertEquals("carrier dispatch rejected", row.lastError)
    }

    @Test
    fun aSentRowIsReportedDispatched() = runBlocking {
        val row = Row()
        assertSame(OutgoingMmsCommit.Result.Dispatched, commit(row))
        assertResolvedOnce(row)
    }

    @Test
    fun aThrowAfterTheWriteIsAPersistedFailureNotAnException() = runBlocking {
        // An exception reads as "nothing written" to the caller, which would
        // keep the composer's draft over a row that already exists.
        val row = Row()
        val boom = IllegalStateException("disk")
        val result = commit(row, markHook = { throw boom })
        assertTrue(row.persisted)
        assertEquals(OutgoingMmsCommit.Result.Crashed(boom), result)
    }

    @Test
    fun aThrowInsideTheWriteStillPropagatesBecauseNothingWasWritten() = runBlocking {
        val row = Row()
        try {
            commit(row, persistHook = { throw IllegalStateException("rolled back") })
            fail("a failed write must not be reported as a persisted failure")
        } catch (e: IllegalStateException) {
            assertEquals("rolled back", e.message)
        }
        assertFalse(row.persisted)
        assertEquals(0, row.dispatches)
    }
}
