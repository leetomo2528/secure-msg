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
        /** True only for the transaction that inserted the visible message/outbox pair. */
        val newlyCreated: Boolean,
    )

    suspend fun persist(
        direction: String,
        phoneNumber: String,
        content: RelayContent,
        providerIdentity: ProviderIdentity,
        receivedAt: Long,
    ): Persisted? {
        val phone = PhoneNumberNormalizer.normalize(phoneNumber)
        require(phone.isNotBlank()) { "phone number is blank" }
        val encoded = RelayContentCodec.encode(content)
        val mid = IncomingMessageIdentity.mid(direction, providerIdentity, phone, receivedAt, encoded)

        return db.withTransaction {
            providerIdentity.id?.let { providerId ->
                val alreadyProcessed = when (direction) {
                    "incoming_sms" -> db.processedSmsDao().contains(providerIdentity.epoch, providerId)
                    "incoming_mms" -> db.processedMmsDao().contains(providerIdentity.epoch, providerId)
                    else -> false
                }
                if (alreadyProcessed) return@withTransaction null
            }
            db.relayOutboxDao().getByMid(mid)?.let { existing ->
                return@withTransaction Persisted(
                    outbox = existing,
                    conversation = ConversationTarget(existing.cid, existing.phoneNumber),
                    newlyCreated = false,
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
                    providerEpoch = providerIdentity.epoch,
                    providerId = providerIdentity.id,
                    sourceFingerprint = providerIdentity.fingerprint,
                    sourceEventKey = providerIdentity.eventKey,
                    localMessageId = localMessageId,
                    direction = direction,
                    createdAt = receivedAt,
                ),
            )
            val outbox = db.relayOutboxDao().getByMid(mid)
                ?: error("missing incoming outbox row id=$outboxId")
            Persisted(outbox, ConversationTarget(thread.cid, phone), newlyCreated = true)
        }
    }

    /** Resolves the provider namespace and claims the visible/outbox pair atomically. */
    suspend fun persistCarrier(
        kind: String,
        direction: String,
        phoneNumber: String,
        content: RelayContent,
        providerId: Long?,
        receivedAt: Long,
    ): Persisted? = db.withTransaction {
        val encoded = RelayContentCodec.encode(content)
        // eventKey does not depend on the provider epoch/id. Check the durable
        // tombstone before namespace resolution so a completed broadcast that
        // later gains a provider id cannot rotate or otherwise mutate ledgers.
        val eventKey = ProviderIdentity.snapshot(
            kind, 0, null, phoneNumber, receivedAt, encoded,
        ).eventKey
        if (db.processedCarrierEventDao().contains(kind, eventKey)) {
            return@withTransaction null
        }
        val identity = ProviderIdentityResolver.resolve(
            db, kind, providerId, phoneNumber, receivedAt, encoded,
        )

        val directionForKind = when (kind) {
            ProviderIdentity.SMS -> "incoming_sms"
            ProviderIdentity.MMS -> "incoming_mms"
            else -> error("unsupported provider kind")
        }
        require(direction == directionForKind) { "carrier kind/direction mismatch" }
        db.relayOutboxDao().findBySourceEventKey(identity.eventKey, direction)?.let { existing ->
            if (existing.providerId == null && identity.id != null) {
                db.relayOutboxDao().aliasProviderIdentity(
                    existing.id,
                    identity.epoch,
                    identity.id,
                    identity.fingerprint,
                )
            }
            val aliased = db.relayOutboxDao().getByMid(existing.mid) ?: existing
            return@withTransaction Persisted(
                outbox = aliased,
                conversation = ConversationTarget(aliased.cid, aliased.phoneNumber),
                newlyCreated = false,
            )
        }
        persist(direction, phoneNumber, content, identity, receivedAt)
    }

    /** Commits all incoming-event dedupe records before removing retry state. */
    suspend fun acknowledgeIncoming(row: RelayOutbox) = db.withTransaction {
        require(row.direction.startsWith("incoming_")) { "not an incoming outbox row" }
        val kind = when (row.direction) {
            "incoming_sms" -> ProviderIdentity.SMS
            "incoming_mms" -> ProviderIdentity.MMS
            else -> error("unsupported incoming direction")
        }
        row.sourceEventKey?.let { eventKey ->
            db.processedCarrierEventDao().insert(
                ProcessedCarrierEvent(kind = kind, eventKey = eventKey),
            )
        }
        row.providerId?.let { providerId ->
            if (kind == ProviderIdentity.MMS) {
                db.processedMmsDao().insert(
                    ProcessedMms(row.providerEpoch, providerId, row.sourceFingerprint),
                )
            } else {
                db.processedSmsDao().insert(
                    ProcessedSms(row.providerEpoch, providerId, row.sourceFingerprint),
                )
            }
        }
        db.relayOutboxDao().delete(row.id)
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
