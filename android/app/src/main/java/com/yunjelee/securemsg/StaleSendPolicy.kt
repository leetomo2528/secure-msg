package com.yunjelee.securemsg

/**
 * Whether a send request another device (the web) left on the relay is still
 * fresh enough to put on the carrier.
 *
 * Up to v0.23.1 the pull stopped at the first own echo of every conversation,
 * so web send requests piled up on the relay for days without ever reaching
 * the carrier. Once that is fixed, or whenever this phone comes back after a
 * long time offline, the pull meets such rows again; sending them hours or
 * days late to the real recipient is worse than not sending them. So a row
 * the relay received more than [MAX_AGE_MS] ago is not dispatched: it is
 * recorded as failed with [STALE_ERROR], which the web shows, and the owner
 * can send it again.
 *
 * The rule is evaluated only where processRelayEnvelope holds a fresh claim on
 * the row (a new receipt, or a reclaimed 'claimed' one that never reached the
 * carrier API): a row with any other receipt -- adopted from a pre-update
 * dispatch, UNCERTAIN, sent, failed, delivered, attempting -- keeps it.
 */
internal object StaleSendPolicy {
    /** Exactly one hour after the relay received the row is still sent. */
    const val MAX_AGE_MS = 3_600_000L

    const val STALE_ERROR =
        "오래된 요청이라 보내지 않았습니다(접수 후 1시간 초과). 필요하면 다시 보내세요."

    const val UNKNOWN_AGE_ERROR =
        "접수 시각을 알 수 없어 보내지 않았습니다. 필요하면 다시 보내세요."

    enum class Verdict { NOT_APPLICABLE, DISPATCH, REFUSE_STALE, REFUSE_UNKNOWN_AGE }

    /**
     * The clock a pulled row's age is measured on.
     *
     * created_at is the relay's own clock (seconds). The HTTP `Date` header of
     * the history response that carried the row is the same relay chain's
     * clock (gunicorn, Caddy and Cloudflare all send one, so this needs no
     * server change and works with relays already deployed), advanced by the
     * monotonic time since that response arrived. Its error is the header's
     * one-second resolution, the transfer and parse time between the header
     * being stamped and the elapsed-time capture (which makes the age read
     * slightly low), and any skew between the host that stamped `Date` and
     * the one that stamped created_at (NTP-synced, normally well under a
     * second) -- seconds at worst, far inside the one-hour window. Without
     * the header the phone's wall clock is used: on a phone with automatic
     * time (NITZ/NTP) it is normally within seconds of the relay too.
     *
     * @param serverDateMs the response's parsed `Date` header, or null.
     * @param elapsedSinceResponseMs monotonic time since the response arrived.
     * @param phoneNowMs the phone's wall clock now.
     */
    fun referenceNowMs(serverDateMs: Long?, elapsedSinceResponseMs: Long, phoneNowMs: Long): Long =
        if (serverDateMs != null) serverDateMs + elapsedSinceResponseMs.coerceAtLeast(0L) else phoneNowMs

    /** One history response's clock reading, captured as it arrives. */
    data class ResponseClock(val serverDateMs: Long?, val elapsedRealtimeAtResponseMs: Long) {
        fun nowMs(elapsedRealtimeNowMs: Long, phoneNowMs: Long): Long =
            referenceNowMs(serverDateMs, elapsedRealtimeNowMs - elapsedRealtimeAtResponseMs, phoneNowMs)
    }

    /**
     * @param isCarrierSendRequest [RelaySyncPolicy.isCarrierSendRequest] for the row.
     * @param priorReceiptStatus the relay_receipts status the claim found for
     *   (cid, seq): null when the claim inserted a new receipt, 'claimed' when
     *   a stale pre-dispatch claim was reclaimed. Anything else is an outcome
     *   this rule never overwrites.
     * @param createdAtSec the row's created_at (relay seconds); null or
     *   non-positive when the row has none.
     * @param referenceNowMs [referenceNowMs] at the moment of the decision.
     */
    fun evaluate(
        isCarrierSendRequest: Boolean,
        priorReceiptStatus: String?,
        createdAtSec: Long?,
        referenceNowMs: Long,
    ): Verdict {
        if (!isCarrierSendRequest) return Verdict.NOT_APPLICABLE
        if (priorReceiptStatus != null && priorReceiptStatus != "claimed") return Verdict.NOT_APPLICABLE
        val ageMs = ageMs(createdAtSec, referenceNowMs) ?: return Verdict.REFUSE_UNKNOWN_AGE
        // A negative age (created_at ahead of the reference clock) is sent.
        return if (ageMs <= MAX_AGE_MS) Verdict.DISPATCH else Verdict.REFUSE_STALE
    }

    /** The carrier error a refusal is recorded with; null when the row is not refused. */
    fun errorFor(verdict: Verdict): String? = when (verdict) {
        Verdict.REFUSE_STALE -> STALE_ERROR
        Verdict.REFUSE_UNKNOWN_AGE -> UNKNOWN_AGE_ERROR
        Verdict.NOT_APPLICABLE, Verdict.DISPATCH -> null
    }

    /** The row's age in ms, or null when created_at is missing or cannot be a time. */
    fun ageMs(createdAtSec: Long?, referenceNowMs: Long): Long? {
        if (createdAtSec == null || createdAtSec <= 0L || createdAtSec > Long.MAX_VALUE / 1000L) return null
        return referenceNowMs - createdAtSec * 1000L
    }
}
