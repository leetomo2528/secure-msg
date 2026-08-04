package com.yunjelee.securemsg

/** Shared ordering rules for asynchronous carrier callbacks. */
object CarrierState {
    private val order = mapOf(
        "none" to 0,
        "queued" to 1,
        "unknown" to 2,
        "dispatched" to 3,
        "sent" to 4,
        "failed" to 5,
        "delivery_failed" to 5,
        "delivered" to 5,
    )
    private val terminal = setOf("failed", "delivery_failed", "delivered")

    fun isValid(value: String): Boolean = value in order

    fun canAdvance(current: String, next: String): Boolean {
        if (!isValid(next)) return false
        if (current == next) return true
        if (current in terminal) return false
        return (order[next] ?: -1) >= (order[current] ?: 0)
    }
}
