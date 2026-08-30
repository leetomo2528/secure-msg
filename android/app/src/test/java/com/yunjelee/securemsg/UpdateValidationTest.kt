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
    fun unchangedSingleSignerPasses() {
        assertTrue(
            UpdateValidation.signingCertificatesMatch(
                installedCurrentDigests = setOf("A"),
                installedHasMultipleSigners = false,
                archiveCurrentDigests = setOf("A"),
                archiveHasMultipleSigners = false,
                archiveHistoryDigests = listOf("A"),
            ),
        )
    }

    @Test
    fun forwardSingleSignerRotationPasses() {
        assertTrue(
            UpdateValidation.signingCertificatesMatch(
                installedCurrentDigests = setOf("A"),
                installedHasMultipleSigners = false,
                archiveCurrentDigests = setOf("B"),
                archiveHasMultipleSigners = false,
                archiveHistoryDigests = listOf("A", "B"),
            ),
        )
    }

    @Test
    fun unrelatedSingleSignerHistoryIsRejected() {
        assertFalse(singleSignerMatch(installed = "A", current = "C", history = listOf("B", "C")))
        assertFalse(singleSignerMatch(installed = "A", current = "B", history = emptyList()))
    }

    @Test
    fun malformedOrReversedArchiveHistoryIsRejected() {
        // History is oldest-to-current. Its final entry must be the APK's current signer.
        assertFalse(singleSignerMatch(installed = "A", current = "B", history = listOf("B", "A")))
        assertFalse(singleSignerMatch(installed = "A", current = "B", history = listOf("A", "B", "A")))
        // An APK signed by the old key with no lineage containing installed B is a downgrade.
        assertFalse(singleSignerMatch(installed = "B", current = "A", history = listOf("A")))
    }

    @Test
    fun multipleSignersRequireExactCurrentSet() {
        assertTrue(multiSignerMatch(setOf("A", "B"), setOf("B", "A")))
        assertFalse(multiSignerMatch(setOf("A", "B"), setOf("A", "C")))
        assertFalse(multiSignerMatch(setOf("A", "B"), setOf("A")))
    }

    @Test
    fun signerModeMismatchAndMissingSignersAreRejected() {
        assertFalse(
            UpdateValidation.signingCertificatesMatch(
                setOf("A"), false, setOf("A", "B"), true, emptyList(),
            ),
        )
        assertFalse(
            UpdateValidation.signingCertificatesMatch(
                emptySet(), false, setOf("A"), false, listOf("A"),
            ),
        )
    }

    private fun singleSignerMatch(installed: String, current: String, history: List<String>) =
        UpdateValidation.signingCertificatesMatch(
            setOf(installed), false, setOf(current), false, history,
        )

    private fun multiSignerMatch(installed: Set<String>, archive: Set<String>) =
        UpdateValidation.signingCertificatesMatch(
            installed, true, archive, true, emptyList(),
        )

    @Test
    fun fallbackCanOnlyLaunchOnceAfterSessionSubmission() {
        assertTrue(UpdateValidation.shouldLaunchFallback(PendingInstallState.SESSION_SUBMITTED))
        assertFalse(UpdateValidation.shouldLaunchFallback(PendingInstallState.FALLBACK_LAUNCHED))
        assertFalse(UpdateValidation.shouldLaunchFallback(PendingInstallState.READY))
        assertFalse(UpdateValidation.shouldLaunchFallback(PendingInstallState.FAILED))
    }

    @Test
    fun pendingUserActionIsOnlyHonoredWhileSessionIsSubmitted() {
        assertTrue(
            UpdateValidation.shouldHonorPendingUserAction(PendingInstallState.SESSION_SUBMITTED),
        )
        // A cancelled/abandoned flow must not launch a confirm for a dead session.
        assertFalse(UpdateValidation.shouldHonorPendingUserAction(PendingInstallState.FAILED))
        assertFalse(UpdateValidation.shouldHonorPendingUserAction(PendingInstallState.READY))
        assertFalse(
            UpdateValidation.shouldHonorPendingUserAction(PendingInstallState.FALLBACK_LAUNCHED),
        )
        assertFalse(
            UpdateValidation.shouldHonorPendingUserAction(PendingInstallState.AWAITING_PERMISSION),
        )
        assertFalse(UpdateValidation.shouldHonorPendingUserAction(null))
    }

    @Test
    fun installCallbackRequiresTheExactPersistedToken() {
        assertTrue(UpdateValidation.isAuthorizedInstallCallback("t-1", "t-1"))
        assertFalse(UpdateValidation.isAuthorizedInstallCallback("t-2", "t-1"))
        assertFalse(UpdateValidation.isAuthorizedInstallCallback(null, "t-1"))
        // A pre-token pending entry can never be completed by broadcast.
        assertFalse(UpdateValidation.isAuthorizedInstallCallback("", ""))
        assertFalse(UpdateValidation.isAuthorizedInstallCallback(null, ""))
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

    @Test
    fun retryFromBlockedBannerResubmitsAWedgedSession() {
        // The watchdog keeps SESSION_SUBMITTED persisted; only the explicit
        // retry on the blocked banner may push past it and resubmit.
        assertTrue(
            UpdateValidation.shouldStartInstallSession(
                PendingInstallState.SESSION_SUBMITTED,
                retryingBlockedSession = true,
            ),
        )
        assertTrue(
            UpdateValidation.shouldStartInstallSession(
                PendingInstallState.FAILED,
                retryingBlockedSession = true,
            ),
        )
    }

    @Test
    fun installedTargetReconcilesWhenPackageReplacementKilledCallback() {
        assertTrue(
            UpdateValidation.installedTargetSatisfied("0.9.0", "0.9.0", 2_000, 1_000),
        )
        assertTrue(
            UpdateValidation.installedTargetSatisfied("0.9.0", "0.10.0", 1, 2_000),
        )
    }

    @Test
    fun pendingTargetIsNotClearedBeforeInstallOrWhenStillOlder() {
        assertFalse(
            UpdateValidation.installedTargetSatisfied("0.9.0", "0.9.0", 1_000, 2_000),
        )
        assertFalse(
            UpdateValidation.installedTargetSatisfied("0.10.0", "0.9.0", 3_000, 2_000),
        )
    }
}
