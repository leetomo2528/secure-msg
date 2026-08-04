package com.yunjelee.securemsg

/** Phone-number comparison for blocked-sender matching. Digits only, and the
 * last 9 digits are enough — covers +82 10-… vs 010… vs carrier rewrites. */
object SenderMatcher {
    fun matches(incoming: String, blocked: String): Boolean {
        val a = incoming.replace(Regex("[^0-9*#]"), "")
        val b = blocked.replace(Regex("[^0-9*#]"), "")
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val tailA = a.takeLast(9)
        val tailB = b.takeLast(9)
        return tailA.length >= 9 && tailB.length >= 9 && tailA == tailB
    }
}
