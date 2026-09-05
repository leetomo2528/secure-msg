package com.yunjelee.securemsg

/** Shared ordering rules for asynchronous carrier callbacks. */
object CarrierState {
    private val order = mapOf(
        "none" to 0,
        "queued" to 1,
        "unknown" to 2,
        "attempting" to 3,
        "dispatched" to 4,
        "sent" to 5,
        "failed" to 6,
        "delivery_failed" to 6,
        "delivered" to 6,
    )
    private val terminal = setOf("failed", "delivery_failed", "delivered")

    fun isValid(value: String): Boolean = value in order

    /** Whether the provider row must be moved to its failed-message box. */
    fun isFailure(value: String): Boolean = value == "failed" || value == "delivery_failed"

    /** How a carrier delivery report resolved, once its TP-Status octet is readable. */
    enum class DeliveryOutcome { SUCCEEDED, PENDING, FAILED }

    /**
     * Classifies the TP-Status of a GSM SMS-STATUS-REPORT (3GPP TS 23.040 9.2.3.11).
     *
     * Null means unclassifiable, and the caller must then keep the resultCode
     * behaviour the phones on the current APK already run: guessing an outcome out of
     * a reserved value would regress the working delivered path on a real handset,
     * and no JVM test here can catch that.
     */
    fun classifyDeliveryReport(tpStatus: Int): DeliveryOutcome? = when (tpStatus) {
        in 0x00..0x1F -> DeliveryOutcome.SUCCEEDED
        in 0x20..0x3F -> DeliveryOutcome.PENDING
        // 0x60..0x7F is "temporary error, SC is not making any more transfer
        // attempts": no further report is coming, so the safer half of that
        // ambiguity is a failure the user is shown, not a row left pending forever.
        in 0x40..0x7F -> DeliveryOutcome.FAILED
        else -> null
    }

    fun canAdvance(current: String, next: String): Boolean {
        if (!isValid(next)) return false
        if (current == next) return true
        if (current in terminal) return false
        return (order[next] ?: -1) >= (order[current] ?: 0)
    }
}

internal data class CarrierCallbackAggregate(
    val status: String,
    val error: String?,
) {
    companion object {
        fun resolve(
            action: String,
            partCount: Int,
            results: List<CarrierPartResult>,
        ): CarrierCallbackAggregate {
            val failedPart = results.filterNot { it.successful }.minByOrNull { it.part }
            val status = when {
                action == CarrierStatusReceiver.ACTION_DELIVERED && failedPart == null -> "delivered"
                action == CarrierStatusReceiver.ACTION_DELIVERED -> "delivery_failed"
                failedPart == null -> "sent"
                else -> "failed"
            }
            val error = failedPart?.let {
                "carrier result=${it.resultCode} part=${it.part + 1}/$partCount"
            }
            return CarrierCallbackAggregate(status, error)
        }
    }
}
