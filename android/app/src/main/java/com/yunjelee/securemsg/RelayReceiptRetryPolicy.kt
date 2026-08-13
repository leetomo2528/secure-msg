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
}
