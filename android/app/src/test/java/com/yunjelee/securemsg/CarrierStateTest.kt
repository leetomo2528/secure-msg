package com.yunjelee.securemsg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun readsPermanentFailureOutOfTheDeliveryReportStatus() {
        assertEquals(
            CarrierState.DeliveryOutcome.SUCCEEDED,
            CarrierState.classifyDeliveryReport(0x00),
        )
        assertEquals(
            CarrierState.DeliveryOutcome.FAILED,
            CarrierState.classifyDeliveryReport(0x41),
        )
        // "Temporary error, SC is not making any more transfer attempts" is final
        // for the user, so it must not read as a delivery still in flight.
        assertEquals(
            CarrierState.DeliveryOutcome.FAILED,
            CarrierState.classifyDeliveryReport(0x60),
        )
    }

    @Test
    fun keepsAnInterimReportOutOfTerminalStateAndGuessesNothingElse() {
        assertEquals(
            CarrierState.DeliveryOutcome.PENDING,
            CarrierState.classifyDeliveryReport(0x20),
        )
        assertEquals(
            CarrierState.DeliveryOutcome.PENDING,
            CarrierState.classifyDeliveryReport(0x3F),
        )
        // Reserved values fall back to the resultCode path instead of inventing an
        // outcome; a wrong guess here regresses delivered ticks on real handsets.
        assertNull(CarrierState.classifyDeliveryReport(0x80))
    }

    @Test
    fun filesPermanentDeliveryFailureWithTheCarrierStatusByte() {
        val result = CarrierCallbackAggregate.resolve(
            CarrierStatusReceiver.ACTION_DELIVERED,
            1,
            listOf(
                CarrierPartResult(
                    "mid",
                    CarrierStatusReceiver.ACTION_DELIVERED,
                    0,
                    1,
                    false,
                    0x41,
                ),
            ),
        )

        assertEquals("delivery_failed", result.status)
        assertEquals("carrier result=65 part=1/1", result.error)
    }
}
