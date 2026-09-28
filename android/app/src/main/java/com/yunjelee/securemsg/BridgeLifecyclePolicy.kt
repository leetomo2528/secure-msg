package com.yunjelee.securemsg

/**
 * Timing decisions of the bridge's lifecycle, kept free of Android calls so the
 * JVM tests can pin their thresholds. All times are
 * `SystemClock.elapsedRealtime()` milliseconds: monotonic, and counting deep
 * sleep, which is exactly when a relay socket goes quietly dead.
 */
object BridgeLifecyclePolicy {
    /** How long a relay client may stay disconnected before the watchdog restarts the bridge. */
    const val WATCHDOG_DOWN_MS = 90_000L

    /** Floor between two watchdog restarts. */
    const val WATCHDOG_MIN_INTERVAL_MS = 90_000L

    /** A client younger than this, with no connect error yet, is still connecting: keep it. */
    const val RELAY_YOUNG_MS = 10_000L

    /** Window in which MainActivity's repeated bridge starts collapse into one. */
    const val START_DEBOUNCE_MS = 2_000L

    /**
     * Whether the outbox loop's watchdog should call `ensureBridgeReady()`
     * now. [disconnectedSinceElapsed] is null while the client is connected.
     * [gateOpen] (BridgeGate.canRun, a binder round trip) is asked last and
     * only when the timing already says yes.
     */
    fun shouldKickRelay(
        nowElapsed: Long,
        disconnectedSinceElapsed: Long?,
        lastKickElapsed: Long?,
        gateOpen: () -> Boolean,
    ): Boolean {
        if (disconnectedSinceElapsed == null) return false
        if (nowElapsed - disconnectedSinceElapsed < WATCHDOG_DOWN_MS) return false
        if (lastKickElapsed != null && nowElapsed - lastKickElapsed < WATCHDOG_MIN_INTERVAL_MS) return false
        return gateOpen()
    }

    /**
     * Whether `startBridge` may disconnect the existing client and make a new
     * one. A connected client is never replaced; a young one is left to finish
     * connecting unless it has already reported a connect error.
     */
    fun shouldReplaceRelay(connected: Boolean, ageMs: Long, hadConnectError: Boolean): Boolean {
        if (connected) return false
        if (ageMs < RELAY_YOUNG_MS && !hadConnectError) return false
        return true
    }

    /**
     * MainActivity's start debounce. onResume and the credentials collector
     * both start the bridge on every open; [urgent] (credentials just
     * appeared, or a send just queued outbox work) always goes through.
     */
    fun shouldStartBridge(nowElapsed: Long, lastStartElapsed: Long?, urgent: Boolean): Boolean {
        if (urgent || lastStartElapsed == null) return true
        return nowElapsed - lastStartElapsed >= START_DEBOUNCE_MS
    }
}
