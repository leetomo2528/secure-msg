package com.yunjelee.securemsg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmMotionTest {
    @Test
    fun animationsOffCollapseEveryDurationToZero() {
        // Developer options and the accessibility toggle both land here. Zero
        // has to be zero and not "fast": Compose runs a 0ms tween as a snap,
        // and anything else leaves motion on a phone that asked for none.
        assertEquals(0, SmMotion.scaledDuration(SmMotion.CONVERSATION_MS, 0f))
        assertEquals(SmDurations(0, 0, 0), SmMotion.durations(0f))
    }

    @Test
    fun eitherGlobalScaleAtZeroTurnsMotionOff() {
        assertEquals(0f, SmMotion.effectiveScale(0f, 1f))
        assertEquals(0f, SmMotion.effectiveScale(1f, 0f))
        assertEquals(0f, SmMotion.effectiveScale(0f, 0f))
        assertEquals(0f, SmMotion.durations(SmMotion.effectiveScale(1f, 0f)).conversationMs.toFloat())
    }

    @Test
    fun theStricterOfTheTwoScalesWins() {
        assertEquals(0.5f, SmMotion.effectiveScale(0.5f, 1f))
        assertEquals(0.5f, SmMotion.effectiveScale(1f, 0.5f))
        assertEquals(1f, SmMotion.effectiveScale(1f, 1f))
        assertEquals(2f, SmMotion.effectiveScale(10f, 2f))
    }

    @Test
    fun unreportableScalesFallBackToThePlatformDefault() {
        // Settings.Global hands back whatever is stored. A value that cannot
        // mean a scale must read as "unset", never as "animations off" —
        // silently killing every transition is the worse of the two failures.
        assertEquals(1f, SmMotion.effectiveScale(Float.NaN, 1f))
        assertEquals(1f, SmMotion.effectiveScale(1f, Float.NaN))
        assertEquals(1f, SmMotion.effectiveScale(-1f, -1f))
        assertEquals(SmMotion.durations(1f), SmMotion.durations(SmMotion.effectiveScale(Float.NaN, -3f)))
    }

    @Test
    fun unscaledDurationsAreTheDeclaredConstants() {
        assertEquals(
            SmDurations(SmMotion.CONVERSATION_MS, SmMotion.TAB_MS, SmMotion.CHROME_MS),
            SmMotion.durations(1f),
        )
    }

    @Test
    fun theTabSwitchStaysLighterThanTheConversation() {
        // The tab transition fires on every 메시지/연락처/설정 tap, so it must
        // never be the slowest thing on screen.
        assertTrue(SmMotion.TAB_MS < SmMotion.CONVERSATION_MS)
        assertTrue(SmMotion.CHROME_MS <= SmMotion.CONVERSATION_MS)
        // A messaging app, not a showcase: nothing here is worth waiting for.
        assertTrue(SmMotion.CONVERSATION_MS <= 300)
    }

    @Test
    fun aSlowedDownScaleStretchesRatherThanRounding() {
        assertEquals(460, SmMotion.scaledDuration(230, 2f))
        assertEquals(115, SmMotion.scaledDuration(230, 0.5f))
    }

    @Test
    fun aSurvivingTransitionKeepsAtLeastOneFrame() {
        // 0.01x is still "animate, but barely". Rounding it to 0 would silently
        // turn a slowed-down device into one with no motion at all.
        assertEquals(16, SmMotion.scaledDuration(230, 0.01f))
        assertTrue(SmMotion.scaledDuration(SmMotion.TAB_MS, 0.05f) > 0)
    }

    @Test
    fun aZeroLengthBaseNeverBecomesAnAnimation() {
        assertEquals(0, SmMotion.scaledDuration(0, 1f))
        assertEquals(0, SmMotion.scaledDuration(-5, 1f))
    }

    @Test
    fun pushesGoForwardAndPopsGoBack() {
        // 메시지(0) → 연락처(1) → 설정(2), and the 메시지 tab's own
        // list(0) → composer(1) → conversation(2) depth, share this rule.
        assertEquals(1, SmMotion.slideDirection(0, 1))
        assertEquals(1, SmMotion.slideDirection(0, 2))
        assertEquals(1, SmMotion.slideDirection(1, 2))
        assertEquals(-1, SmMotion.slideDirection(2, 0))
        assertEquals(-1, SmMotion.slideDirection(1, 0))
        assertEquals(0, SmMotion.slideDirection(1, 1))
    }
}
