package com.yunjelee.securemsg

import androidx.room.withTransaction
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable local boundary for carrier messages.
 *
 * A receiver calls this before posting a notification. The transaction makes the
 * conversation, visible message and relay retry item appear together, so opening a
 * notification never races a relay service that has not written Room yet.
 */
class IncomingMessageRepository(
    private val db: AppDatabase,
) {
    data class Persisted(
        val outbox: RelayOutbox,
        val conversation: ConversationTarget,
    )

    suspend fun persist(
        direction: String,
        phoneNumber: String,
        content: RelayContent,
        providerId: Long?,
        receivedAt: Long,
    ): Persisted {
        val phone = PhoneNumberNormalizer.normalize(phoneNumber)
        require(phone.isNotBlank()) { "phone number is blank" }
        val encoded = RelayContentCodec.encode(content)
        val mid = IncomingMessageIdentity.mid(direction, providerId, phone, receivedAt, encoded)

        return db.withTransaction {
            db.relayOutboxDao().getByMid(mid)?.let { existing ->
                return@withTransaction Persisted(
                    outbox = existing,
                    conversation = ConversationTarget(existing.cid, phone),
                )
            }

            val thread = db.threadDao().getByPhone(phone) ?: SmsThread(
                cid = "local_${UUID.randomUUID().toString().replace("-", "")}",
                phoneNumber = phone,
                serverName = null,
            ).also { db.threadDao().upsert(it) }
            db.threadDao().touch(thread.cid, receivedAt)

            val attachmentsJson = attachmentsJson(content)
            val localMessageId = db.messageDao().insert(
                MessageRow(
                    cid = thread.cid,
                    seq = 0,
                    senderSid = "",
                    plaintext = content.text,
                    createdAt = receivedAt,
                    mine = false,
                    contentType = content.type,
                    subject = content.subject,
                    attachmentsJson = attachmentsJson,
                ),
            )
            val outboxId = db.relayOutboxDao().insert(
                RelayOutbox(
                    mid = mid,
                    cid = thread.cid,
                    payload = "",
                    plaintext = encoded,
                    contentType = content.type,
                    subject = content.subject,
                    attachmentsJson = attachmentsJson,
                    phoneNumber = phone,
                    providerId = providerId,
                    localMessageId = localMessageId,
                    direction = direction,
                    createdAt = receivedAt,
                ),
            )
            val outbox = db.relayOutboxDao().getByMid(mid)
                ?: error("missing incoming outbox row id=$outboxId")
            Persisted(outbox, ConversationTarget(thread.cid, phone))
        }
    }

    private fun attachmentsJson(content: RelayContent): String? {
        if (content.attachments.isEmpty()) return null
        val rows = JSONArray()
        content.attachments.forEach {
            rows.put(
                JSONObject()
                    .put("name", it.name)
                    .put("content_type", it.contentType)
                    .put("data", it.data)
                    .put("size", it.size),
            )
        }
        return rows.toString()
    }
}

/** One-shot destination carried from an SMS notification into Compose. */
data class ConversationTarget(
    val cid: String?,
    val normalizedPhone: String,
    /** Distinguishes repeated taps/messages that target the same conversation. */
    val requestId: String = "",
)

object ConversationTargetResolver {
    fun resolve(threads: List<SmsThread>, target: ConversationTarget): SmsThread? {
        target.cid?.takeIf { it.isNotBlank() }?.let { cid ->
            threads.firstOrNull { it.cid == cid }?.let { return it }
        }
        val phone = PhoneNumberNormalizer.normalize(target.normalizedPhone)
        if (phone.isBlank()) return null
        return threads.firstOrNull {
            PhoneNumberNormalizer.normalize(it.phoneNumber) == phone
        }
    }
}
