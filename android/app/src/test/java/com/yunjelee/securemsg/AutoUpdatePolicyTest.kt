package com.yunjelee.securemsg

import com.yunjelee.securemsg.AutoUpdatePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoUpdatePolicyTest {

    private fun decide(
        autoCheckEnabled: Boolean = true,
        autoInstallEnabled: Boolean = true,
        dueForCheck: Boolean = true,
        unmeteredValidatedNetwork: Boolean = true,
        pendingState: PendingInstallState? = null,
        appVisible: Boolean = false,
        pendingStale: Boolean = false,
        submittedInThisProcess: Boolean = false,
    ) = AutoUpdatePolicy.decide(
        autoCheckEnabled = autoCheckEnabled,
        autoInstallEnabled = autoInstallEnabled,
        dueForCheck = dueForCheck,
        unmeteredValidatedNetwork = unmeteredValidatedNetwork,
        pendingState = pendingState,
        appVisible = appVisible,
        pendingStale = pendingStale,
        submittedInThisProcess = submittedInThisProcess,
    )

    @Test
    fun idleDueUnmeteredTickDownloads() {
        assertEquals(Action.CHECK_AND_DOWNLOAD, decide())
    }

    @Test
    fun eitherToggleOffSkipsEverything() {
        assertEquals(Action.SKIP, decide(autoCheckEnabled = false))
        assertEquals(Action.SKIP, decide(autoInstallEnabled = false))
        // Even a READY entry stays untouched once the user opts out.
        assertEquals(
            Action.SKIP,
            decide(autoInstallEnabled = false, pendingState = PendingInstallState.READY),
        )
        // So does a wedged one: opting out means hands off entirely.
        assertEquals(
            Action.SKIP,
            decide(
                autoInstallEnabled = false,
                pendingState = PendingInstallState.FAILED,
                pendingStale = true,
            ),
        )
    }

    @Test
    fun notDueSkipsWithoutNetworkWork() {
        assertEquals(Action.SKIP, decide(dueForCheck = false))
    }

    @Test
    fun meteredOrUnvalidatedNetworkSkipsDownload() {
        // SKIP (not a check with no download): check() writes the 12h stamp,
        // and a metered tick must not consume it.
        assertEquals(Action.SKIP, decide(unmeteredValidatedNetwork = false))
    }

    @Test
    fun visibleAppDoesNotBlockCheckAndDownload() {
        assertEquals(Action.CHECK_AND_DOWNLOAD, decide(appVisible = true))
    }

    @Test
    fun readyEntryCommitsOnlyWhileOffScreen() {
        assertEquals(
            Action.COMMIT_READY,
            decide(pendingState = PendingInstallState.READY, appVisible = false),
        )
        // Visible covers started-but-paused (split-screen): the silent commit
        // kills the process and must defer whenever the UI can be seen.
        assertEquals(
            Action.SKIP,
            decide(pendingState = PendingInstallState.READY, appVisible = true),
        )
    }

    @Test
    fun readyCommitIgnoresStampAndNetwork() {
        // The APK is already on disk; a purely local commit needs neither the
        // 12h window nor an unmetered network.
        assertEquals(
            Action.COMMIT_READY,
            decide(
                pendingState = PendingInstallState.READY,
                dueForCheck = false,
                unmeteredValidatedNetwork = false,
            ),
        )
    }

    @Test
    fun freshInFlightOrUserResolvedStatesSkip() {
        for (state in listOf(
            PendingInstallState.SESSION_SUBMITTED,
            PendingInstallState.AWAITING_PERMISSION,
            PendingInstallState.FALLBACK_LAUNCHED,
            PendingInstallState.FAILED,
        )) {
            assertEquals(Action.SKIP, decide(pendingState = state))
            // Visibility does not change it: FAILED needs the user's banner
            // decision, the rest have a flow in flight.
            assertEquals(Action.SKIP, decide(pendingState = state, appVisible = true))
        }
    }

    @Test
    fun staleFailedEntryIsReclaimedWhileUnattended() {
        assertEquals(
            Action.CLEAR_WEDGED,
            decide(pendingState = PendingInstallState.FAILED, pendingStale = true),
        )
        // No stamp/network condition: reclaiming is purely local, and the
        // retry it enables is gated on its own tick.
        assertEquals(
            Action.CLEAR_WEDGED,
            decide(
                pendingState = PendingInstallState.FAILED,
                pendingStale = true,
                dueForCheck = false,
                unmeteredValidatedNetwork = false,
            ),
        )
    }

    @Test
    fun staleFailedEntryStaysWhileAppVisible() {
        // A visible banner is the user's to resolve.
        assertEquals(
            Action.SKIP,
            decide(
                pendingState = PendingInstallState.FAILED,
                pendingStale = true,
                appVisible = true,
            ),
        )
    }

    @Test
    fun staleOrphanedSubmissionIsReclaimed() {
        // The submitting process is gone: its confirm is never coming back on
        // an unattended phone, and the entry would otherwise wedge every tick.
        assertEquals(
            Action.CLEAR_WEDGED,
            decide(
                pendingState = PendingInstallState.SESSION_SUBMITTED,
                pendingStale = true,
                submittedInThisProcess = false,
            ),
        )
    }

    @Test
    fun staleSubmissionIsReclaimedEvenWhenThisProcessSubmittedIt() {
        // The submitting process is the START_STICKY bridge, so it is still
        // alive on the very device this recovery exists for: an untapped
        // confirm must not stay wedged just because the flag is set.
        assertEquals(
            Action.CLEAR_WEDGED,
            decide(
                pendingState = PendingInstallState.SESSION_SUBMITTED,
                pendingStale = true,
                submittedInThisProcess = true,
            ),
        )
        // A visible app still owns the decision: the confirm dialog may be on
        // screen right now.
        assertEquals(
            Action.SKIP,
            decide(
                pendingState = PendingInstallState.SESSION_SUBMITTED,
                pendingStale = true,
                submittedInThisProcess = true,
                appVisible = true,
            ),
        )
    }

    @Test
    fun manualFlowStatesAreNeverReclaimed() {
        // The user created these while present; only the user resolves them.
        for (state in listOf(
            PendingInstallState.AWAITING_PERMISSION,
            PendingInstallState.FALLBACK_LAUNCHED,
        )) {
            assertEquals(Action.SKIP, decide(pendingState = state, pendingStale = true))
        }
    }
}
