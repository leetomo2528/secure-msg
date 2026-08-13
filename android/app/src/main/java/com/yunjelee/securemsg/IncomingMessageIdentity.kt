package com.yunjelee.securemsg

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ProviderIdentity(
    val kind: String,
    val epoch: Long,
    val id: Long?,
    val fingerprint: String,
    val eventKey: String,
) {
    init {
        require(kind == SMS || kind == MMS) { "unsupported provider kind" }
        require(epoch >= 0) { "provider epoch must be non-negative" }
    }

    companion object {
        const val SMS = "sms"
        const val MMS = "mms"

        fun snapshot(
            kind: String,
            epoch: Long,
            id: Long?,
            phoneNumber: String,
            receivedAt: Long,
            encodedContent: String,
        ): ProviderIdentity {
            val phone = PhoneNumberNormalizer.normalize(phoneNumber)
            val fingerprint = IncomingMessageIdentity.sourceFingerprint(phone, receivedAt, encodedContent)
            return ProviderIdentity(
                kind = kind,
                epoch = epoch,
                id = id,
                fingerprint = fingerprint,
                eventKey = IncomingMessageIdentity.sourceEventKey(kind, phone, receivedAt, encodedContent),
            )
        }
    }
}

/** Stable relay idempotency key for a carrier event, including provider-less broadcasts. */
object IncomingMessageIdentity {
    fun mid(
        direction: String,
        identity: ProviderIdentity,
        phoneNumber: String,
        receivedAt: Long,
        encodedContent: String,
    ): String {
        val source = if (identity.id != null && identity.epoch == 0L) {
            // Exact pre-v11 preimage. Existing relay idempotency keys must not change.
            "$direction\u0000provider\u0000${identity.id}"
        } else if (identity.id != null) {
            "$direction\u0000provider-v2\u0000${identity.epoch}\u0000${identity.id}"
        } else {
            "$direction\u0000broadcast\u0000$phoneNumber\u0000$receivedAt\u0000$encodedContent"
        }
        return relayMid(source)
    }

    /** Hash of normalized source facts; safe to retain because encoded content is never stored. */
    fun sourceFingerprint(
        normalizedSender: String,
        receivedAt: Long,
        encodedContent: String,
    ): String = sha256("source-fingerprint-v1\u0000$normalizedSender\u0000$receivedAt\u0000$encodedContent")

    /** Kind-namespaced hash for correlating an exact carrier event across ingest stages. */
    fun sourceEventKey(
        kind: String,
        normalizedSender: String,
        receivedAt: Long,
        encodedContent: String,
    ): String = sha256("source-event-v1\u0000$kind\u0000$normalizedSender\u0000$receivedAt\u0000$encodedContent")

    private fun relayMid(source: String): String {
        val digest = sha256(source)
        return "in_" + digest.take(61)
    }

    private fun sha256(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return digest
    }
}
