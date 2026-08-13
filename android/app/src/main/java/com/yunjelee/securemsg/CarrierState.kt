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
