package com.yunjelee.securemsg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateValidationTest {

    @Test
    fun positiveExpectedSizeMustMatchExactly() {
        assertTrue(UpdateValidation.hasExpectedSize(1024, 1024))
        assertFalse(UpdateValidation.hasExpectedSize(1024, 1023))
        assertFalse(UpdateValidation.hasExpectedSize(1024, 1025))
    }

    @Test
    fun unknownExpectedSizeAcceptsDownloadedLength() {
        assertTrue(UpdateValidation.hasExpectedSize(0, 7))
        assertTrue(UpdateValidation.hasExpectedSize(-1, 7))
    }

    @Test
    fun signerDigestsMatchIndependentOfArrayIdentityAndOrder() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        assertTrue(
            UpdateValidation.signersMatch(
                listOf(first, second),
                listOf(second.copyOf(), first.copyOf()),
            ),
        )
    }

    @Test
    fun signerDigestsRejectDifferentOrMissingCertificates() {
        assertFalse(
            UpdateValidation.signersMatch(
                listOf(byteArrayOf(1, 2, 3)),
                listOf(byteArrayOf(1, 2, 4)),
            ),
        )
        assertFalse(UpdateValidation.signersMatch(emptyList(), listOf(byteArrayOf(1))))
        assertFalse(UpdateValidation.signersMatch(listOf(byteArrayOf(1)), emptyList()))
    }

    @Test
    fun fallbackCanOnlyLaunchOnceAfterSessionSubmission() {
        assertTrue(UpdateValidation.shouldLaunchFallback(PendingInstallState.SESSION_SUBMITTED))
        assertFalse(UpdateValidation.shouldLaunchFallback(PendingInstallState.FALLBACK_LAUNCHED))
        assertFalse(UpdateValidation.shouldLaunchFallback(PendingInstallState.READY))
        assertFalse(UpdateValidation.shouldLaunchFallback(PendingInstallState.FAILED))
    }

    @Test
    fun autoCheckIsSkippedWhileAnInstallIsPending() {
        assertFalse(UpdateValidation.shouldAutoCheck(hasPendingInstall = true))
        assertTrue(UpdateValidation.shouldAutoCheck(hasPendingInstall = false))
    }

    @Test
    fun submittedSessionCannotBeStartedAgain() {
        assertFalse(
            UpdateValidation.shouldStartInstallSession(PendingInstallState.SESSION_SUBMITTED),
        )
        assertTrue(UpdateValidation.shouldStartInstallSession(PendingInstallState.READY))
        assertTrue(UpdateValidation.shouldStartInstallSession(PendingInstallState.FAILED))
        assertTrue(UpdateValidation.shouldStartInstallSession(null))
    }
}
