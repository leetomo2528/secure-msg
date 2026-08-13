package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderIdentityResolverTest {
    private fun row(epoch: Long, fingerprint: String?) =
        ProviderIdentityResolver.LedgerRow(epoch, fingerprint)

    @Test
    fun exactPendingOrProcessedReplayKeepsHistoricalEpoch() {
        val decision = ProviderIdentityResolver.decideEpoch(
            currentEpoch = 3,
            fingerprint = "same-event",
            ledger = listOf(row(1, "same-event"), row(3, "newer-event")),
        )

        assertEquals(ProviderIdentityResolver.EpochDecision.Use(1), decision)
    }

    @Test
    fun conflictingCurrentPendingEventRotatesBeforeMidCollision() {
        val decision = ProviderIdentityResolver.decideEpoch(
            currentEpoch = 3,
            fingerprint = "second-event",
            ledger = listOf(row(3, "first-pending-event")),
        )

        assertSame(ProviderIdentityResolver.EpochDecision.Rotate, decision)
    }

    @Test
    fun unchangedHistoricalEventIsDedupedAfterAnotherIdRotatesNamespace() {
        val decision = ProviderIdentityResolver.decideEpoch(
            currentEpoch = 4,
            fingerprint = "old-event",
            ledger = listOf(row(3, "old-event")),
        )

        assertEquals(ProviderIdentityResolver.EpochDecision.Use(3), decision)
    }

    @Test
    fun legacyFingerprintlessTombstoneRemainsAuthoritativeAcrossEpochs() {
        val decision = ProviderIdentityResolver.decideEpoch(
            currentEpoch = 2,
            fingerprint = "unrecoverable-v10-event",
            ledger = listOf(row(0, null)),
        )

        assertEquals(ProviderIdentityResolver.EpochDecision.Use(0), decision)
    }

    @Test
    fun newObservationUsesAlreadyRotatedNamespaceWhenOnlyOldMismatchExists() {
        val decision = ProviderIdentityResolver.decideEpoch(
            currentEpoch = 2,
            fingerprint = "reused-id-event",
            ledger = listOf(row(1, "old-event")),
        )

        assertEquals(ProviderIdentityResolver.EpochDecision.Use(2), decision)
    }
}
