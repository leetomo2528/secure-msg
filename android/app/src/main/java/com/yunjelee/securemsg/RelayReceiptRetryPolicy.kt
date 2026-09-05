package com.yunjelee.securemsg

/**
 * Pure recovery policy for a relay-to-carrier idempotency receipt.
 *
 * `attempting` is deliberately not retryable: the process may have died after
 * SmsManager accepted the message but before `dispatched` could be persisted.
 */
internal object RelayReceiptRetryPolicy {
    enum class Action {
        WAIT_FOR_ACTIVE_CLAIM,
        RETRY_STALE_CLAIM,
        REQUIRE_EXPLICIT_RETRY,
        CONSUME_RESOLVED,
    }

    fun action(status: String, claimIsStale: Boolean): Action = when (status) {
        "claimed" -> if (claimIsStale) {
            Action.RETRY_STALE_CLAIM
        } else {
            Action.WAIT_FOR_ACTIVE_CLAIM
        }
        "attempting" -> Action.REQUIRE_EXPLICIT_RETRY
        "dispatched", "sent", "failed", "delivery_failed", "delivered" ->
            Action.CONSUME_RESOLVED
        // Fail closed on an unrecognized persisted value: consuming it would
        // advance the cursor without proving that carrier dispatch resolved.
        else -> Action.REQUIRE_EXPLICIT_RETRY
    }

    /**
     * Errors on a rejected `carrier_status` ack that a later attempt can still
     * resolve, as opposed to ones the relay will refuse forever.
     *
     * The unsynced queue is oldest-first and shared by every conversation, so
     * waiting on a refusal that can never succeed — its conversation was
     * renamed off a phone number, its message row is gone — stops the web from
     * ever seeing sent/delivered again, for every conversation at once. An
     * unrecognized or missing reason stays retryable: dropping a status is
     * worse than repeating one.
     */
    fun isRetryableAckError(error: String): Boolean = error.isBlank() || error in RETRYABLE_ACK_ERRORS

    private val RETRYABLE_ACK_ERRORS = setOf(
        // RelayClient's own synthetic outcomes.
        "relay disconnected",
        "no carrier status ack",
        "carrier status acknowledgement timeout",
        // The socket lost its authenticated session; a reconnect restores it.
        "unauthenticated",
        "device revoked or unknown",
    )
}
