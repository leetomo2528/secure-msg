package com.yunjelee.securemsg

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable relay idempotency key for a carrier event, including provider-less broadcasts. */
object IncomingMessageIdentity {
    fun mid(
        direction: String,
        providerId: Long?,
        phoneNumber: String,
        receivedAt: Long,
        encodedContent: String,
    ): String {
        val source = if (providerId != null) {
            "$direction\u0000provider\u0000$providerId"
        } else {
            "$direction\u0000broadcast\u0000$phoneNumber\u0000$receivedAt\u0000$encodedContent"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        // Relay mids accept 16..64 ASCII word/dash characters.
        return "in_" + digest.take(61)
    }
}
