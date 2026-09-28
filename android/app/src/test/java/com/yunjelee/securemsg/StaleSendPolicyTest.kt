package com.yunjelee.securemsg

import com.yunjelee.securemsg.StaleSendPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaleSendPolicyTest {
    private companion object {
        const val CREATED_SEC = 1_790_000_000L
        const val CREATED_MS = CREATED_SEC * 1000L
        const val HOUR_MS = 3_600_000L
    }

    private fun verdictAt(ageMs: Long, createdAtSec: Long? = CREATED_SEC) =
        StaleSendPolicy.evaluate(
            isCarrierSendRequest = true,
            priorReceiptStatus = null,
            createdAtSec = createdAtSec,
            referenceNowMs = CREATED_MS + ageMs,
        )

    @Test
    fun `exactly one hour after the relay received it is still sent`() {
        assertEquals(Verdict.DISPATCH, verdictAt(3_600L * 1000L))
        assertEquals(Verdict.DISPATCH, verdictAt(0L))
        assertEquals(Verdict.DISPATCH, verdictAt(HOUR_MS - 1))
    }

    @Test
    fun `one second or one millisecond past the hour is refused as stale`() {
        assertEquals(Verdict.REFUSE_STALE, verdictAt(3_601L * 1000L))
        assertEquals(Verdict.REFUSE_STALE, verdictAt(HOUR_MS + 1))
        assertEquals(Verdict.REFUSE_STALE, verdictAt(3L * 24 * HOUR_MS))
        assertEquals(StaleSendPolicy.STALE_ERROR, StaleSendPolicy.errorFor(Verdict.REFUSE_STALE))
        assertEquals(
            "오래된 요청이라 보내지 않았습니다(접수 후 1시간 초과). 필요하면 다시 보내세요.",
            StaleSendPolicy.STALE_ERROR,
        )
    }

    @Test
    fun `a created_at ahead of the reference clock is sent`() {
        assertEquals(Verdict.DISPATCH, verdictAt(-1L))
        assertEquals(Verdict.DISPATCH, verdictAt(-5 * HOUR_MS))
    }

    @Test
    fun `a row without a usable created_at is never sent`() {
        assertEquals(Verdict.REFUSE_UNKNOWN_AGE, verdictAt(0L, createdAtSec = null))
        assertEquals(Verdict.REFUSE_UNKNOWN_AGE, verdictAt(0L, createdAtSec = 0L))
        assertEquals(Verdict.REFUSE_UNKNOWN_AGE, verdictAt(0L, createdAtSec = -1L))
        assertEquals(Verdict.REFUSE_UNKNOWN_AGE, verdictAt(0L, createdAtSec = Long.MAX_VALUE))
        assertEquals(
            "접수 시각을 알 수 없어 보내지 않았습니다. 필요하면 다시 보내세요.",
            StaleSendPolicy.errorFor(Verdict.REFUSE_UNKNOWN_AGE),
        )
        assertNull(StaleSendPolicy.errorFor(Verdict.DISPATCH))
        assertNull(StaleSendPolicy.errorFor(Verdict.NOT_APPLICABLE))
    }

    @Test
    fun `the relay's Date header wins over a phone clock hours off, in either direction`() {
        val serverNow = CREATED_MS + 10 * 60_000L // ten minutes after the relay got it
        for (phoneOffset in listOf(-5 * HOUR_MS, 5 * HOUR_MS)) {
            val now = StaleSendPolicy.referenceNowMs(serverNow, 0L, serverNow + phoneOffset)
            assertEquals(serverNow, now)
            assertEquals(Verdict.DISPATCH, StaleSendPolicy.evaluate(true, null, CREATED_SEC, now))
        }
        // A day-old row is stale even on a phone whose clock is a day behind.
        val oldServerNow = CREATED_MS + 24 * HOUR_MS
        val now = StaleSendPolicy.referenceNowMs(oldServerNow, 0L, CREATED_MS)
        assertEquals(Verdict.REFUSE_STALE, StaleSendPolicy.evaluate(true, null, CREATED_SEC, now))
    }

    @Test
    fun `without the header the phone clock is used`() {
        val phoneNow = CREATED_MS + 2 * HOUR_MS
        assertEquals(phoneNow, StaleSendPolicy.referenceNowMs(null, 30_000L, phoneNow))
        val clock = StaleSendPolicy.ResponseClock(serverDateMs = null, elapsedRealtimeAtResponseMs = 1_000L)
        assertEquals(phoneNow, clock.nowMs(elapsedRealtimeNowMs = 99_000L, phoneNowMs = phoneNow))
    }

    @Test
    fun `time spent on the page since the response arrived is added to the header`() {
        val serverDate = CREATED_MS + HOUR_MS - 5_000L
        val clock = StaleSendPolicy.ResponseClock(serverDate, elapsedRealtimeAtResponseMs = 50_000L)
        // Four seconds later the row is still inside the hour...
        val early = clock.nowMs(elapsedRealtimeNowMs = 54_000L, phoneNowMs = 0L)
        assertEquals(serverDate + 4_000L, early)
        assertEquals(Verdict.DISPATCH, StaleSendPolicy.evaluate(true, null, CREATED_SEC, early))
        // ...six seconds later (a slow MMS earlier on the page) it is not.
        val late = clock.nowMs(elapsedRealtimeNowMs = 56_000L, phoneNowMs = 0L)
        assertEquals(Verdict.REFUSE_STALE, StaleSendPolicy.evaluate(true, null, CREATED_SEC, late))
        // The monotonic clock never runs backwards; a bogus reading does not either.
        assertEquals(serverDate, StaleSendPolicy.referenceNowMs(serverDate, -10_000L, 0L))
    }

    @Test
    fun `rows that are not another device's send request are not affected`() {
        val old = CREATED_MS + 30 * 24 * HOUR_MS
        // Self echo and gateway uploads (incoming SMS, phone-composed sends).
        assertEquals(
            Verdict.NOT_APPLICABLE,
            StaleSendPolicy.evaluate(RelaySyncPolicy.isCarrierSendRequest("android_gateway", "none"), null, CREATED_SEC, old),
        )
        // A row a gateway already reported on.
        for (status in listOf("dispatched", "sent", "failed", "delivered", "delivery_failed")) {
            assertEquals(
                status,
                Verdict.NOT_APPLICABLE,
                StaleSendPolicy.evaluate(RelaySyncPolicy.isCarrierSendRequest("web", status), null, CREATED_SEC, old),
            )
        }
        // The web's own request is.
        assertEquals(
            Verdict.REFUSE_STALE,
            StaleSendPolicy.evaluate(RelaySyncPolicy.isCarrierSendRequest("web", "none"), null, CREATED_SEC, old),
        )
        assertEquals(Verdict.NOT_APPLICABLE, StaleSendPolicy.evaluate(false, null, null, old))
    }

    @Test
    fun `a receipt that records an outcome is never overwritten`() {
        val old = CREATED_MS + 30 * 24 * HOUR_MS
        for (status in listOf("sent", "failed", "delivered", "delivery_failed", "attempting", "dispatched")) {
            assertEquals(status, Verdict.NOT_APPLICABLE, StaleSendPolicy.evaluate(true, status, CREATED_SEC, old))
            assertEquals(status, Verdict.NOT_APPLICABLE, StaleSendPolicy.evaluate(true, status, null, old))
        }
        // A reclaimed pre-dispatch claim never reached the carrier API.
        assertEquals(Verdict.REFUSE_STALE, StaleSendPolicy.evaluate(true, "claimed", CREATED_SEC, old))
        assertEquals(Verdict.DISPATCH, StaleSendPolicy.evaluate(true, "claimed", CREATED_SEC, CREATED_MS))
    }
}
