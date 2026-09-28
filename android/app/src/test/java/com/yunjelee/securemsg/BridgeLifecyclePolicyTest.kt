package com.yunjelee.securemsg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLifecyclePolicyTest {
    private val base = 1_000_000L

    private fun kick(downMs: Long, lastKickAgoMs: Long? = null, gate: Boolean = true): Boolean {
        val now = base + downMs
        return BridgeLifecyclePolicy.shouldKickRelay(
            nowElapsed = now,
            disconnectedSinceElapsed = base,
            lastKickElapsed = lastKickAgoMs?.let { now - it },
            gateOpen = { gate },
        )
    }

    @Test
    fun `watchdog waits out 90 seconds of disconnection`() {
        assertFalse(kick(downMs = 89_999))
        assertFalse(kick(downMs = 89_000))
        assertTrue(kick(downMs = 90_000))
        assertTrue(kick(downMs = 3_600_000))
    }

    @Test
    fun `watchdog never kicks a connected client`() {
        assertFalse(
            BridgeLifecyclePolicy.shouldKickRelay(base + 999_999, null, null) { true },
        )
    }

    @Test
    fun `watchdog kicks at most once per 90 seconds`() {
        assertFalse(kick(downMs = 200_000, lastKickAgoMs = 0))
        assertFalse(kick(downMs = 200_000, lastKickAgoMs = 89_999))
        assertTrue(kick(downMs = 200_000, lastKickAgoMs = 90_000))
    }

    @Test
    fun `watchdog respects a closed bridge gate`() {
        assertFalse(kick(downMs = 200_000, gate = false))
    }

    @Test
    fun `gate is not queried while the timing says no`() {
        var asked = 0
        BridgeLifecyclePolicy.shouldKickRelay(base + 1_000, base, null) { asked++; true }
        BridgeLifecyclePolicy.shouldKickRelay(base + 200_000, base, base + 199_000) { asked++; true }
        assertTrue(asked == 0)
    }

    @Test
    fun `a young client without connect error is kept`() {
        assertFalse(BridgeLifecyclePolicy.shouldReplaceRelay(connected = false, ageMs = 0, hadConnectError = false))
        assertFalse(BridgeLifecyclePolicy.shouldReplaceRelay(connected = false, ageMs = 9_999, hadConnectError = false))
    }

    @Test
    fun `a young client that already failed to connect is replaced`() {
        assertTrue(BridgeLifecyclePolicy.shouldReplaceRelay(connected = false, ageMs = 1_000, hadConnectError = true))
    }

    @Test
    fun `an old disconnected client is replaced`() {
        assertTrue(BridgeLifecyclePolicy.shouldReplaceRelay(connected = false, ageMs = 10_000, hadConnectError = false))
    }

    @Test
    fun `a connected client is never replaced`() {
        assertFalse(BridgeLifecyclePolicy.shouldReplaceRelay(connected = true, ageMs = 0, hadConnectError = false))
        assertFalse(BridgeLifecyclePolicy.shouldReplaceRelay(connected = true, ageMs = 999_999, hadConnectError = true))
    }

    @Test
    fun `repeated bridge starts within 2 seconds collapse`() {
        assertTrue(BridgeLifecyclePolicy.shouldStartBridge(base, lastStartElapsed = null, urgent = false))
        assertFalse(BridgeLifecyclePolicy.shouldStartBridge(base + 1_999, lastStartElapsed = base, urgent = false))
        assertFalse(BridgeLifecyclePolicy.shouldStartBridge(base, lastStartElapsed = base, urgent = false))
        assertTrue(BridgeLifecyclePolicy.shouldStartBridge(base + 2_000, lastStartElapsed = base, urgent = false))
    }

    @Test
    fun `a login or a queued send bypasses the debounce`() {
        assertTrue(BridgeLifecyclePolicy.shouldStartBridge(base + 1, lastStartElapsed = base, urgent = true))
    }
}
