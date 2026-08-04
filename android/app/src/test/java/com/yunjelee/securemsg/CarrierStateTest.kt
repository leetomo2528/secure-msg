package com.yunjelee.securemsg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierStateTest {
    @Test
    fun acceptsForwardCarrierProgress() {
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
}
