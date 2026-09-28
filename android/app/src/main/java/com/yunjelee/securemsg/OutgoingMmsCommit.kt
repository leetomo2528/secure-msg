package com.yunjelee.securemsg

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The half of an outgoing photo send that must not be interrupted: persist the
 * row, fit it to the carrier, dispatch it, and record what the carrier said.
 *
 * Pulled out of [OutgoingSmsDispatcher.queueAndSendMms] as lambdas so the host
 * suite can cancel a caller in the middle of it without Room or a Context.
 *
 * Why the whole sequence is [NonCancellable]: the outbox drain in
 * SmsBridgeService re-dispatches any `outgoing_*` row still carrying
 * carrierState 'unknown' 30 s after it was created, on the assumption that the
 * process died between the insert and the carrier call. A cancellation that
 * landed after [dispatch] returned but before [markDispatched] ran would leave
 * exactly that state on a row the carrier already has, and the drain would send
 * the same MMS a second time. What this guarantees is narrower than "no
 * duplicate ever": a caller's cancellation cannot cut between [dispatch] and
 * the write that records it. When the DAO calls succeed, the dispatched,
 * too-large and rejected paths all leave 'unknown' in the same block that made
 * (or skipped) the carrier call. Cancelling before [persist] commits writes
 * nothing; the caller's refusal phase before this stays cancellable.
 *
 * Not covered, and unchanged by this: a DAO call throwing after a successful
 * dispatch ([Result.Crashed] leaves the row 'unknown'), the process dying
 * between [dispatch] and [markDispatched], and the drain racing an in-process
 * send that takes longer than 30 s. All three are the drain's existing
 * at-least-once window, not something a cancellation can open.
 */
internal object OutgoingMmsCommit {
    /** What [fit] decided: carrier-ready material, or a reason it cannot go. */
    sealed interface Fit<out T> {
        class Ready<T>(val value: T) : Fit<T>
        class TooLarge(val reason: String) : Fit<Nothing>
    }

    /** How the persisted row was resolved. Every value means a row exists. */
    sealed interface Result {
        /** Handed to the carrier and marked off 'unknown'. */
        data object Dispatched : Result

        /** [fit] refused; the row is marked failed with [reason]. */
        data class TooLarge(val reason: String) : Result

        /** The carrier API returned false; the row is marked failed. */
        data object Rejected : Result

        /**
         * Something threw after [persist] committed. The row exists, so this is
         * reported as a persisted failure rather than thrown past a caller that
         * would read an exception as "nothing written".
         */
        data class Crashed(val error: Throwable) : Result
    }

    /** The marker the text path and the outbox drain write for a false dispatch. */
    const val DISPATCH_REJECTED = "carrier dispatch rejected"

    /**
     * Anything [persist] throws propagates: the transaction rolled back, so
     * nothing was written and the caller's "refused" reading is correct.
     */
    suspend fun <K, T> run(
        persist: suspend () -> K,
        fit: suspend (K) -> Fit<T>,
        dispatch: suspend (K, T) -> Boolean,
        markFailed: suspend (K, String) -> Unit,
        markDispatched: suspend (K) -> Unit,
    ): Result = withContext(NonCancellable) {
        val key = persist()
        try {
            val ready = when (val decided = fit(key)) {
                is Fit.TooLarge -> {
                    markFailed(key, decided.reason)
                    return@withContext Result.TooLarge(decided.reason)
                }
                is Fit.Ready -> decided.value
            }
            if (!dispatch(key, ready)) {
                markFailed(key, DISPATCH_REJECTED)
                return@withContext Result.Rejected
            }
            markDispatched(key)
            Result.Dispatched
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Crashed(e)
        } catch (e: LinkageError) {
            Result.Crashed(e)
        }
    }
}
