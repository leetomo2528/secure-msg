package com.yunjelee.securemsg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CarrierStateTest {
    @Test
    fun acceptsForwardCarrierProgress() {
        assertTrue(CarrierState.canAdvance("unknown", "attempting"))
        assertTrue(CarrierState.canAdvance("attempting", "dispatched"))
        assertTrue(CarrierState.canAdvance("queued", "dispatched"))
        assertTrue(CarrierState.canAdvance("dispatched", "sent"))
        assertTrue(CarrierState.canAdvance("sent", "delivered"))
        assertTrue(CarrierState.canAdvance("sent", "delivery_failed"))
    }

    @Test
    fun rejectsLateCallbacksThatRegressOrReplaceTerminalState() {
        assertFalse(CarrierState.canAdvance("sent", "dispatched"))
        assertFalse(CarrierState.canAdvance("delivered", "sent"))
        assertFalse(CarrierState.canAdvance("delivery_failed", "delivered"))
        assertFalse(CarrierState.canAdvance("failed", "sent"))
    }

    @Test
    fun rejectsUnknownStatus() {
        assertFalse(CarrierState.canAdvance("sent", "mystery"))
    }

    @Test
    fun mapsBothSendAndDeliveryFailuresToFailedProviderMessage() {
        assertTrue(CarrierState.isFailure("failed"))
        assertTrue(CarrierState.isFailure("delivery_failed"))
        assertFalse(CarrierState.isFailure("sent"))
        assertFalse(CarrierState.isFailure("delivered"))
    }

    @Test
    fun resolvesMultipartFailureWithStablePartDiagnostic() {
        val result = CarrierCallbackAggregate.resolve(
            CarrierStatusReceiver.ACTION_DELIVERED,
            3,
            listOf(
                CarrierPartResult("mid", "action", 0, 3, true, -1),
                CarrierPartResult("mid", "action", 2, 3, false, 8),
                CarrierPartResult("mid", "action", 1, 3, false, 5),
            ),
        )

        assertEquals("delivery_failed", result.status)
        assertEquals("carrier result=5 part=2/3", result.error)
    }
}
