package com.yunjelee.securemsg

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Persists an outgoing carrier SMS before invoking SmsManager.
 *
 * Relay preparation is deliberately deferred to SmsBridgeService. This keeps
 * normal SMS and the system quick-reply contract working while Oracle or the
 * network is offline, without losing the later encrypted multi-device sync.
 */
object OutgoingSmsDispatcher {
    private const val TAG = "OutgoingSmsDispatcher"

    suspend fun queueAndSend(
        context: Context,
        credentials: SavedCredentials,
        phoneNumber: String,
        text: String,
    ): Boolean {
        val phone = PhoneNumberNormalizer.normalize(phoneNumber)
        require(PhoneNumberNormalizer.isSmsAddress(phone)) { "invalid phone number" }
        require(text.isNotBlank()) { "SMS body is empty" }
        require(text.length <= 20_000) { "SMS body is too long" }

        val db = AppDatabase.get(context)
        // Sealed direction: this row reaches every other device under the
        // gateway's own sid, exactly like an SMS the carrier delivered.
        val content = RelayContentCodec.text(text).copy(direction = RelayContentCodec.DIR_OUT)
        val contentJson = RelayContentCodec.encode(content)
        val mid = UUID.randomUUID().toString()
        // The presentation row and the durable outbox row must appear or fail
        // together; a crash between them would orphan a relay-only phantom.
        // The thread get-or-create belongs inside for the same reason it does in
        // IncomingMessageRepository: sms_threads is keyed on cid alone, so two
        // senders racing the first-ever message to a number would otherwise each
        // insert their own local_ row and split the conversation in two.
        val (cid, localId, outboxId) = db.withTransaction {
            val thread = db.threadDao().getByPhone(phone) ?: SmsThread(
                cid = SmsThread.newLocalCid(),
                phoneNumber = phone,
                serverName = null,
            ).also { db.threadDao().upsert(it) }
            db.threadDao().touch(thread.cid, System.currentTimeMillis())
            val localId = db.messageDao().insert(
                MessageRow(
                    cid = thread.cid,
                    seq = 0,
                    senderSid = credentials.sid,
                    plaintext = text,
                    createdAt = System.currentTimeMillis(),
                    mine = true,
                    contentType = content.type,
                    carrierStatus = "queued",
                ),
            )
            val outboxId = db.relayOutboxDao().insert(
                RelayOutbox(
                    mid = mid,
                    cid = thread.cid,
                    payload = "",
                    plaintext = contentJson,
                    contentType = content.type,
                    phoneNumber = phone,
                    localMessageId = localId,
                    direction = "outgoing_sms",
                    carrierState = "unknown",
                ),
            )
            Triple(thread.cid, localId, outboxId)
        }

        val dispatched = SmsSender.send(context, phone, text, mid, cid, 0)
        if (!dispatched) {
            db.relayOutboxDao().markCarrierState(
                outboxId,
                "failed",
                "carrier dispatch rejected",
            )
            db.messageDao().setCarrierStatusById(
                localId,
                "failed",
                "carrier dispatch rejected",
            )
            return false
        }

        // A very fast carrier callback may already have advanced this row. Only
        // replace the unknown pre-call marker and mirror the resulting state.
        db.relayOutboxDao().markCarrierDispatchedIfUnknown(outboxId)
        val current = db.relayOutboxDao().getByMid(mid)
        val state = current?.carrierState ?: "dispatched"
        db.messageDao().advanceCarrierStatus(localId, state, current?.lastError)
        Log.i(TAG, "Queued encrypted relay for carrier SMS mid=$mid")
        return true
    }

    /**
     * How a photo send ended, and what to tell the owner.
     *
     * A Boolean is what the text path returns and it is not enough here. Every
     * way an MMS can fail to leave the device has a *specific* cause the owner
     * can act on -- the caption is too long to leave room, one pick is a 50 MB
     * video, nine photos were chosen -- and the whole reason this path exists is
     * that the alternative is a photo silently absent on the other device.
     *
     * [Refused] and [Failed] differ in what is on disk, which is what the caller
     * needs to know beyond the words: a refusal wrote nothing at all, while a
     * failure left a visible row in the thread carrying its own failed badge.
     */
    sealed interface MmsSend {
        object Sent : MmsSend

        /** Nothing persisted, nothing dispatched. [reason] is Korean. */
        class Refused(val reason: String) : MmsSend

        /** Persisted and visible in the thread; the carrier would not take it. */
        class Failed(val reason: String) : MmsSend
    }

    /**
     * The photo sibling of [queueAndSend], and deliberately not a fork of it.
     *
     * A call with no pictures delegates to [queueAndSend] verbatim, so a
     * photoless send keeps taking exactly the SMS path it takes today -- same
     * content type, same `text` RelayContent, same single-segment carrier call.
     * Only a call that actually carries pictures builds an MMS.
     *
     * The order matters and is the same order [queueAndSend] uses: everything
     * that can still refuse this message cheaply happens BEFORE the transaction,
     * because past that write the message exists in the owner's thread and on
     * every other device, and the only remaining exits are a carrier callback or
     * a failed badge. Reading, shrinking and budgeting the photos is exactly
     * such a refusal, so all of it runs first.
     *
     * Never throws. A refusal is a returned value, and the catches below cover
     * the two framework boundaries that can still surprise us, because this is
     * called from a Compose handler on a phone that installs its own updates
     * unattended.
     */
    suspend fun queueAndSendMms(
        context: Context,
        credentials: SavedCredentials,
        phoneNumber: String,
        text: String,
        subject: String?,
        sources: List<Uri>,
    ): MmsSend {
        val phone = PhoneNumberNormalizer.normalize(phoneNumber)
        OutgoingAttachmentPlanner.preflight(
            phoneIsSmsAddress = PhoneNumberNormalizer.isSmsAddress(phone),
            text = text,
            sourceCount = sources.size,
        )?.let { return MmsSend.Refused(it) }
        if (!OutgoingAttachmentPlanner.needsMms(sources.size, subject)) {
            // The unchanged SMS path, reached through the same function the UI
            // already calls. Nothing about a photoless send moves.
            return if (queueAndSend(context, credentials, phoneNumber, text)) {
                MmsSend.Sent
            } else {
                MmsSend.Failed(CARRIER_REJECTED_KO)
            }
        }

        val described = sources.map { describe(context, it) }
        val plan = OutgoingAttachmentPlanner.plan(
            sources = described,
            budget = OutgoingAttachmentPlanner.budgetFor(
                maxMessageSize = MmsSender.carrierMaxMessageSize(context),
                text = text,
                subject = subject,
                sourceCount = sources.size,
            ),
            read = { index -> readSource(context, sources[index]) },
            shrink = { source, bytes, room ->
                ImageShrinker.shrink(bytes, source.contentType, room)
                    ?.let { MmsSender.ReEncoded(it.bytes, it.contentType) }
            },
        )
        val attachments = when (plan) {
            is OutgoingAttachmentPlanner.Plan.Refused -> {
                Log.w(TAG, "Outgoing MMS refused before persistence: ${plan.reason}")
                return MmsSend.Refused(plan.reason)
            }
            is OutgoingAttachmentPlanner.Plan.Ready -> plan.attachments
        }

        val db = AppDatabase.get(context)
        // Sealed direction, exactly as the text path seals it: this row reaches
        // every other device under the gateway's own sid, and without it they
        // cannot tell a photo this account sent from one it received.
        val content = RelayContent(
            type = RelayContentCodec.TYPE_MMS,
            text = text,
            subject = subject?.takeIf { it.isNotBlank() },
            direction = RelayContentCodec.DIR_OUT,
            attachments = attachments,
        )
        // The planner's whole job is to make this call safe, and it is still
        // wrapped: encode() throws on a cap the planner failed to honour, and a
        // throw here would take down a Compose handler instead of naming a
        // photo that did not send.
        val contentJson = runCatching { RelayContentCodec.encode(content) }.getOrElse { e ->
            Log.e(TAG, "Outgoing MMS content rejected by codec", e)
            return MmsSend.Refused("사진을 메시지에 담지 못했습니다")
        }
        val attachmentsJson = RelayContentCodec.attachmentsJson(content)
        val mid = UUID.randomUUID().toString()
        // One transaction, for the reasons queueAndSend states: the presentation
        // row and the durable outbox row must appear or fail together, and the
        // thread get-or-create belongs inside because sms_threads is keyed on
        // cid alone.
        val (cid, localId, outboxId) = db.withTransaction {
            val thread = db.threadDao().getByPhone(phone) ?: SmsThread(
                cid = SmsThread.newLocalCid(),
                phoneNumber = phone,
                serverName = null,
            ).also { db.threadDao().upsert(it) }
            db.threadDao().touch(thread.cid, System.currentTimeMillis())
            val localId = db.messageDao().insert(
                MessageRow(
                    cid = thread.cid,
                    seq = 0,
                    senderSid = credentials.sid,
                    plaintext = content.text,
                    createdAt = System.currentTimeMillis(),
                    mine = true,
                    contentType = content.type,
                    subject = content.subject,
                    attachmentsJson = attachmentsJson,
                    carrierStatus = "queued",
                ),
            )
            val outboxId = db.relayOutboxDao().insert(
                RelayOutbox(
                    mid = mid,
                    cid = thread.cid,
                    payload = "",
                    // Carries the photo to the web: SmsBridgeService encrypts
                    // this column for every other device on this account, which
                    // is the only reason a sent photo appears there at all.
                    plaintext = contentJson,
                    contentType = content.type,
                    subject = content.subject,
                    attachmentsJson = attachmentsJson,
                    phoneNumber = phone,
                    localMessageId = localId,
                    // Every outbox query keys on `direction LIKE 'outgoing_%'`,
                    // so this names the medium the way the incoming rows name
                    // theirs without changing what any of them match.
                    direction = "outgoing_mms",
                    carrierState = "unknown",
                ),
            )
            Triple(thread.cid, localId, outboxId)
        }

        // Past this point the message is the owner's, visible in the thread and
        // bound for their other devices; every exit below resolves it rather
        // than pretending it never happened.
        val ready = when (val fit = MmsSender.fit(context, content)) {
            is MmsSender.Fit.TooLarge -> {
                // The planner sized these bytes to this same ceiling, so this is
                // a disagreement between the two, not an over-large photo. It is
                // still resolved the way the relay path resolves it: the carrier
                // API is never called, so no callback can ever finish this row.
                Log.e(TAG, "Carrier refused outgoing MMS mid=$mid: ${fit.reason}")
                db.relayOutboxDao().markCarrierState(outboxId, "failed", fit.reason)
                db.messageDao().setCarrierStatusById(localId, "failed", fit.reason)
                return MmsSend.Failed(fit.reason)
            }
            is MmsSender.Fit.Ready -> fit
        }
        val dispatched = MmsSender.send(context, phone, content, ready, mid, cid, 0)
        if (!dispatched) {
            // The same marker string the text path and the outbox drain write,
            // so one carrier state means one thing across all three.
            db.relayOutboxDao().markCarrierState(outboxId, "failed", "carrier dispatch rejected")
            db.messageDao().setCarrierStatusById(localId, "failed", "carrier dispatch rejected")
            return MmsSend.Failed(CARRIER_REJECTED_KO)
        }

        // A very fast carrier callback may already have advanced this row. Only
        // replace the unknown pre-call marker and mirror the resulting state.
        db.relayOutboxDao().markCarrierDispatchedIfUnknown(outboxId)
        val current = db.relayOutboxDao().getByMid(mid)
        val state = current?.carrierState ?: "dispatched"
        db.messageDao().advanceCarrierStatus(localId, state, current?.lastError)
        Log.i(TAG, "Queued encrypted relay for carrier MMS mid=$mid parts=${attachments.size}")
        return MmsSend.Sent
    }

    private const val CARRIER_REJECTED_KO = "통신사가 메시지를 받지 않았습니다"

    /**
     * What the resolver says this pick is.
     *
     * The type comes from `getType` and never from the URI's last segment: a
     * photo-picker URI is an opaque id with no name at all, and the ones that
     * do carry a name call an HEIC a `.jpg` often enough to matter. An
     * unresolvable type is reported as octet-stream, which the planner treats as
     * un-shrinkable and refuses with a reason rather than handing the decoder a
     * file it cannot read.
     */
    private fun describe(context: Context, uri: Uri): OutgoingAttachmentPlanner.Source =
        OutgoingAttachmentPlanner.Source(
            contentType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                ?: "application/octet-stream",
            declaredSize = declaredSize(context, uri),
        )

    /** Declared byte length of a pick, or -1 when the provider will not say. */
    private fun declaredSize(context: Context, uri: Uri): Int = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            val length = descriptor.length
            // UNKNOWN_LENGTH is -1, and a length past Int range is larger than
            // anything this path will read; both mean "unmeasured" here, and the
            // bounded read below is what actually stops an absurd file.
            if (length in 0..Int.MAX_VALUE.toLong()) length.toInt() else -1
        } ?: -1
    } catch (e: Exception) {
        Log.w(TAG, "attachment length unavailable: ${e.javaClass.simpleName}")
        -1
    }

    /**
     * One pick's bytes, bounded.
     *
     * The ceiling is enforced while reading rather than after, so a provider
     * that declared nothing cannot pull 50 MB into the activity's heap before
     * anyone objects. The three outcomes are [ImageShrinkPolicy.PartRead]'s
     * because they mean the same three things they mean on the incoming side,
     * and the planner acts on the distinction: too big is the owner's file
     * choice, unreadable is a URI that no retry will fix.
     */
    private fun readSource(context: Context, uri: Uri): ImageShrinkPolicy.PartRead = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(8192)
            var total = 0
            var overran = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > OutgoingAttachmentPlanner.SOURCE_MAX_BYTES) {
                    overran = true
                    break
                }
                out.write(buf, 0, n)
            }
            if (overran) ImageShrinkPolicy.PartRead.TooLarge
            else ImageShrinkPolicy.PartRead.Ok(out.toByteArray())
        } ?: ImageShrinkPolicy.PartRead.Failed
    } catch (t: Throwable) {
        // Throwable, not Exception: a 32 MiB array plus whatever the provider
        // buffers is the largest allocation this activity makes, and an
        // OutOfMemoryError here must name a photo that did not send rather than
        // take the process down.
        Log.w(TAG, "attachment read failed: ${t.javaClass.simpleName}")
        ImageShrinkPolicy.PartRead.Failed
    }
}
