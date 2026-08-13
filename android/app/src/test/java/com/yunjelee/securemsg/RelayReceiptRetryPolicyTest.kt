package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayReceiptRetryPolicyTest {
    @Test
    fun `fresh claim waits for current worker`() {
        assertEquals(
            RelayReceiptRetryPolicy.Action.WAIT_FOR_ACTIVE_CLAIM,
            RelayReceiptRetryPolicy.action("claimed", claimIsStale = false),
        )
    }

    @Test
    fun `stale claim before dispatch may be retried`() {
        assertEquals(
            RelayReceiptRetryPolicy.Action.RETRY_STALE_CLAIM,
            RelayReceiptRetryPolicy.action("claimed", claimIsStale = true),
        )
    }

    @Test
    fun `attempting receipt is never automatically redispatched`() {
        assertEquals(
            RelayReceiptRetryPolicy.Action.REQUIRE_EXPLICIT_RETRY,
            RelayReceiptRetryPolicy.action("attempting", claimIsStale = true),
        )
        assertEquals(
            RelayReceiptRetryPolicy.Action.REQUIRE_EXPLICIT_RETRY,
            RelayReceiptRetryPolicy.action("attempting", claimIsStale = false),
        )
    }

    @Test
    fun `resolved receipt is consumed without redispatch`() {
        listOf("dispatched", "sent", "failed", "delivery_failed", "delivered").forEach { status ->
            assertEquals(
                status,
                RelayReceiptRetryPolicy.Action.CONSUME_RESOLVED,
                RelayReceiptRetryPolicy.action(status, claimIsStale = true),
            )
        }
    }

    @Test
    fun `unknown receipt state cannot skip cursor`() {
        assertEquals(
            RelayReceiptRetryPolicy.Action.REQUIRE_EXPLICIT_RETRY,
            RelayReceiptRetryPolicy.action("corrupt", claimIsStale = true),
        )
    }
}
