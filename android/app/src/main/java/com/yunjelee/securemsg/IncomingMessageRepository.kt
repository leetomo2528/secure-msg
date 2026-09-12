package com.yunjelee.securemsg

import androidx.room.withTransaction

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

    /**
     * Private because [encoded] has to be the caller's own encoding of [content],
     * and nothing here can check that: a mismatched pair forks the mid and
     * re-delivers a message that was already claimed. [persistCarrier] is the one
     * caller and derives both from the same value.
     *
     * [content] is what the message is *identified* by; [payload] is what the
     * devices actually render, and the two are allowed to differ. For an
     * incoming MMS they do: the payload carries photos re-encoded down to a
     * relayable size plus one Korean line naming what could not be carried,
     * while the identity keeps hashing the provider's own part list byte for
     * byte. That split is not a convenience. Everything derived from [encoded]
     * — the mid, the source fingerprint, the event key — must keep seeing
     * [content] alone, or every message already in the processed ledger re-keys
     * at the upgrade boundary and this gateway relays a duplicate of the
     * owner's entire recent history; the APK installs itself unattended within
     * twelve hours of a release, so that is a certainty rather than a risk. The
     * only columns allowed to follow [payload] are the ones the relay re-reads
     * but never re-hashes: the outbox plaintext, the attachment rows, and the
     * locally rendered message body.
     */
    private suspend fun persist(
        direction: String,
        phoneNumber: String,
        content: RelayContent,
        encoded: String,
        providerIdentity: ProviderIdentity,
        receivedAt: Long,
        payload: RelayContent = content,
    ): Persisted? {
        // The type is the one field both halves write: the visible row takes it
        // from the payload and the outbox row from the identity, and a
        // disagreement would file one as text and its twin as MMS.
        require(payload.type == content.type) { "payload/content type mismatch" }
        val phone = PhoneNumberNormalizer.normalize(phoneNumber)
        require(phone.isNotBlank()) { "phone number is blank" }
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
                return@withTransaction alreadyClaimed(existing)
            }

            val thread = db.threadDao().getByPhone(phone) ?: SmsThread(
                cid = SmsThread.newLocalCid(),
                phoneNumber = phone,
                serverName = null,
            ).also { db.threadDao().upsert(it) }
            db.threadDao().touch(thread.cid, receivedAt)

            val attachmentsJson = RelayContentCodec.attachmentsJson(payload)
            val localMessageId = db.messageDao().insert(
                MessageRow(
                    cid = thread.cid,
                    seq = 0,
                    senderSid = "",
                    plaintext = payload.text,
                    createdAt = receivedAt,
                    mine = false,
                    contentType = payload.type,
                    subject = payload.subject,
                    attachmentsJson = attachmentsJson,
                ),
            )
            val outboxId = db.relayOutboxDao().insert(
                RelayOutbox(
                    mid = mid,
                    cid = thread.cid,
                    payload = "",
                    // Deliberately NOT `encoded`: the relayed body carries the
                    // sealed direction and the payload's own text and
                    // attachments, while the identity above must keep hashing
                    // the direction-less encoding of `content`. Feeding either
                    // into the mid would re-key every incoming message at the
                    // upgrade boundary and let an in-flight one relay twice.
                    // Nothing downstream re-hashes this column — it is read
                    // only to encrypt (SmsBridgeService) and to re-read the
                    // content for a carrier send — so the two may diverge.
                    plaintext = RelayContentCodec.encode(
                        payload.copy(direction = RelayContentCodec.DIR_IN),
                    ),
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
            Persisted(
                outbox,
                // The thread is already in hand here, so the notification title can
                // be the contact name instead of the raw E.164 address.
                ConversationTarget(thread.cid, phone, displayName = thread.displayName),
                newlyCreated = true,
            )
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
        /**
         * What the devices render, when that is not what the message is keyed
         * by. Defaults to [content] so every caller that has only one of them
         * keeps behaving exactly as before.
         */
        payload: RelayContent = content,
    ): Persisted? = db.withTransaction {
        val encoded = RelayContentCodec.encode(content)
        // eventKey does not depend on the provider epoch/id. Check the durable
        // tombstone before namespace resolution so a completed broadcast that
        // later gains a provider id cannot rotate or otherwise mutate ledgers.
        val eventKey = IncomingMessageIdentity.sourceEventKey(
            kind, PhoneNumberNormalizer.normalize(phoneNumber), receivedAt, encoded,
        )
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
            return@withTransaction alreadyClaimed(aliased)
        }
        persist(direction, phoneNumber, content, encoded, identity, receivedAt, payload)
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

    private fun alreadyClaimed(row: RelayOutbox) = Persisted(
        outbox = row,
        conversation = ConversationTarget(row.cid, row.phoneNumber),
        newlyCreated = false,
    )
}

/** One-shot destination carried from an SMS notification into Compose. */
data class ConversationTarget(
    val cid: String?,
    val normalizedPhone: String,
    /** Distinguishes repeated taps/messages that target the same conversation. */
    val requestId: String = "",
    /**
     * Resolved contact name for the notification title, when the caller already
     * held the thread. Optional on purpose: the paths that reuse an existing
     * conversation report `newlyCreated = false` and never notify, so making them
     * pay for a name lookup would only lengthen the broadcast transaction.
     */
    val displayName: String? = null,
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
