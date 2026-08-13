package com.yunjelee.securemsg

import androidx.room.withTransaction

/**
 * Resolves one immutable carrier event into the current provider-id namespace.
 *
 * A finalized v11 ledger row has a source fingerprint. Observing the same
 * numeric provider id with a different fingerprint is positive reuse evidence,
 * so the namespace advances before the new event is persisted. Migrated v10
 * rows have no fingerprint; they intentionally remain epoch 0 because their
 * old event cannot be distinguished safely from a row already reused before
 * the upgrade.
 */
object ProviderIdentityResolver {
    internal data class LedgerRow(val epoch: Long, val fingerprint: String?)

    internal sealed interface EpochDecision {
        data class Use(val epoch: Long) : EpochDecision
        data object Rotate : EpochDecision
    }

    /**
     * Exact historical fingerprints are retries and keep their original epoch.
     * A conflicting current-epoch row proves reuse and advances the namespace.
     * Fingerprint-less v10 rows remain authoritative tombstones because their
     * original event cannot be reconstructed safely after migration.
     */
    internal fun decideEpoch(
        currentEpoch: Long,
        fingerprint: String,
        ledger: List<LedgerRow>,
    ): EpochDecision {
        ledger.firstOrNull { it.fingerprint == fingerprint }?.let {
            return EpochDecision.Use(it.epoch)
        }
        val current = ledger.filter { it.epoch == currentEpoch }
        if (current.isNotEmpty()) {
            if (current.any { it.fingerprint == null }) return EpochDecision.Use(currentEpoch)
            return EpochDecision.Rotate
        }
        ledger.firstOrNull { it.fingerprint == null }?.let {
            return EpochDecision.Use(it.epoch)
        }
        return EpochDecision.Use(currentEpoch)
    }

    suspend fun resolve(
        db: AppDatabase,
        kind: String,
        providerId: Long?,
        phoneNumber: String,
        receivedAt: Long,
        encodedContent: String,
    ): ProviderIdentity = db.withTransaction {
        val state = db.carrierProviderStateDao()
        var epoch = state.currentEpoch(kind)
        val observation = ProviderIdentity.snapshot(
            kind, epoch, providerId, phoneNumber, receivedAt, encodedContent,
        )
        if (providerId == null) return@withTransaction observation

        val direction = when (kind) {
            ProviderIdentity.SMS -> "incoming_sms"
            ProviderIdentity.MMS -> "incoming_mms"
            else -> error("unsupported provider kind")
        }
        db.relayOutboxDao().findBySourceEventKey(observation.eventKey, direction)?.let { exact ->
            return@withTransaction observation.copy(
                epoch = exact.providerEpoch,
                // A provider-less broadcast can be followed by an inbox scan
                // of the same event. Preserve the newly observed provider id;
                // persistCarrier will attach it to the existing outbox row.
                id = exact.providerId ?: providerId,
            )
        }
        val processedExact = when (kind) {
            ProviderIdentity.SMS -> db.processedSmsDao().findByFingerprint(observation.fingerprint)?.let {
                it.providerEpoch to it.providerId
            }
            ProviderIdentity.MMS -> db.processedMmsDao().findByFingerprint(observation.fingerprint)?.let {
                it.providerEpoch to it.providerId
            }
            else -> error("unsupported provider kind")
        }
        processedExact?.let { (exactEpoch, exactId) ->
            return@withTransaction observation.copy(epoch = exactEpoch, id = exactId)
        }

        val processed = when (kind) {
            ProviderIdentity.SMS -> db.processedSmsDao().history(providerId).map {
                LedgerRow(it.providerEpoch, it.sourceFingerprint)
            }
            ProviderIdentity.MMS -> db.processedMmsDao().history(providerId).map {
                LedgerRow(it.providerEpoch, it.sourceFingerprint)
            }
            else -> error("unsupported provider kind")
        }
        val pending = db.relayOutboxDao().providerHistory(providerId, direction).map {
            LedgerRow(it.providerEpoch, it.sourceFingerprint)
        }
        epoch = when (val decision = decideEpoch(epoch, observation.fingerprint, processed + pending)) {
            is EpochDecision.Use -> decision.epoch
            EpochDecision.Rotate -> state.rotateEpoch(kind)
        }
        if (epoch == observation.epoch) observation else ProviderIdentity.snapshot(
            kind, epoch, providerId, phoneNumber, receivedAt, encodedContent,
        )
    }
}
