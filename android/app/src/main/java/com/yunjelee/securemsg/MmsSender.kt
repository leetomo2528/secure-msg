package com.yunjelee.securemsg

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Carrier MMS sender using the Android framework's carrier-configured MMSC. */
object MmsSender {
    private const val TAG = "MmsSender"
    private const val AUTHORITY = "com.yunjelee.securemsg.mms"

    /** One re-encoded attachment: the bytes, and the type they came back as. */
    data class ReEncoded(val bytes: ByteArray, val contentType: String)

    /**
     * Whether the carrier can be handed this message, and with which bytes.
     *
     * The split exists because the two answers travel to different places. The
     * [Ready] attachment list reaches [MmsPduComposer] and nothing else -- the
     * Room row and the relay outbox keep the ORIGINAL content, so the phone's
     * durable copy and the copy re-encrypted for this account's other devices
     * are never degraded by whatever the carrier happens to allow on this SIM.
     * [TooLarge] reaches the caller's predispatch rejection route, which is the
     * only way to resolve a message the carrier API is never called for.
     */
    sealed interface Fit {
        /**
         * @param attachments what to compose with: the content's own list when
         *   nothing needed shrinking (identical instance, so the common path
         *   decodes and re-encodes nothing), otherwise the same list with the
         *   over-budget images replaced by their re-encodes.
         * @param maxMessageSize the ceiling those bytes were fitted to, kept so
         *   the composed PDU can be measured against the estimate that chose it.
         */
        class Ready(val attachments: List<RelayAttachment>, val maxMessageSize: Int) : Fit

        /**
         * @param reason Korean and user-visible. It is written to the receipt's
         *   carrier_error and relayed to the web, where the only other thing
         *   the owner would see is the word "발송 실패" -- and where the
         *   alternative today is a carrier result integer from a message that
         *   was dispatched only to be refused by the MMSC.
         */
        class TooLarge(val reason: String) : Fit
    }

    /**
     * The carrier's MMS ceiling on the default SMS subscription.
     *
     * carrierConfigValues needs no permission and no MMS APN lookup; it is the
     * same Bundle the platform's own MmsService sizes messages against. Nothing
     * in this app has ever read it, which is why an attachment set this app
     * accepts (up to [RelayContentCodec.MAX_ATTACHMENT_BYTES], 512 KiB) could
     * be handed to an MMSC that refuses anything over 300 KiB and come back as
     * an opaque failure integer.
     */
    fun carrierMaxMessageSize(context: Context): Int = carrierMaxMessageSize {
        context.getSystemService(SmsManager::class.java)
            .createForSubscriptionId(SmsManager.getDefaultSmsSubscriptionId())
            .carrierConfigValues
            .getInt(
                SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE,
                MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            )
    }

    /**
     * The read above with the framework lifted out so the fallback is testable.
     *
     * Every failure mode collapses to the AOSP default on purpose: a handset
     * with no telephony, a SIM-less tablet, an OEM build whose SmsManager
     * throws, and a stripped-down build where the class is missing altogether
     * all arrive here as a Throwable. Falling back to 300 KiB keeps photos
     * sendable at the size AOSP itself assumes; propagating the throw would
     * make every MMS on that handset fail before it was composed.
     */
    internal fun carrierMaxMessageSize(read: () -> Int): Int =
        runCatching(read).getOrDefault(MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE)

    /** [plan] against this handset's real ceiling, re-encoding through [ImageShrinker]. */
    fun fit(context: Context, content: RelayContent): Fit =
        plan(content, carrierMaxMessageSize(context), ::shrinkImage)

    /**
     * What this message costs against the carrier's ceiling, and how to pay it.
     *
     * Pure, and framework-free, so the whole decision table runs in the host
     * suite; [fit] supplies the two things that are not ([carrierMaxMessageSize]
     * and [ImageShrinker]).
     *
     * Outgoing draws two classes of part where [ImageShrinkPolicy] draws three.
     * A part that cannot be re-encoded is reserved at its full size rather than
     * dropped: inbound, a photo that will not fit is announced as an omission
     * because the alternative is losing the whole message; outbound, the owner
     * picked every attachment themselves, so quietly sending a subset of what
     * they attached is the same silent loss this change exists to end. Either
     * everything travels -- shrunk where it can be -- or nothing does and the
     * owner is told why.
     */
    internal fun plan(
        content: RelayContent,
        maxMessageSize: Int,
        shrink: (ByteArray, String, Int) -> ReEncoded?,
    ): Fit {
        val attachments = content.attachments
        val budget = MmsAttachmentBudget.forCarrier(
            maxMessageSize = maxMessageSize,
            textBytes = content.text.toByteArray(Charsets.UTF_8).size,
            // Truncated exactly as MmsPduComposer truncates it, and only when
            // the composer will actually write a Subject header at all.
            subjectBytes = content.subject?.takeIf { it.isNotBlank() }
                ?.take(120)?.toByteArray(Charsets.UTF_8)?.size ?: 0,
            // The text part is always emitted, so the PDU carries one more part
            // than there are attachments.
            partCount = attachments.size + 1,
        )
        val total = attachments.sumOf { it.size.toLong() }
        // The overwhelmingly common case, and it must stay free: no base64
        // decode, no bitmap, no re-encode. A message that already fits is
        // handed to the composer as the identical list it arrived as.
        if (total <= budget) return Fit.Ready(attachments, maxMessageSize)

        val shrinkable = mutableListOf<Int>()
        var reserved = 0L
        attachments.forEachIndexed { index, attachment ->
            if (ImageShrinkPolicy.isShrinkable(attachment.contentType)) {
                shrinkable += index
            } else {
                reserved += attachment.size
            }
        }
        // Reserved first: an un-shrinkable part travels whole or not at all, so
        // budget it cannot release must not be offered to the images beside it.
        // This also covers "nothing here is an image", where reserved == total.
        if (reserved > budget) return Fit.TooLarge(unshrinkableReason(total, budget))

        val budgets = ImageShrinkPolicy.allocate(
            shrinkable.map {
                ImageShrinkPolicy.Candidate(attachments[it].contentType, attachments[it].size)
            },
            (budget - reserved).toInt(),
        )
        val out = attachments.toMutableList()
        shrinkable.forEachIndexed { slot, index ->
            val original = attachments[index]
            val room = budgets[slot]
            // Already within its share: left byte-for-byte alone. Re-encoding a
            // photo that fits would cost quality for nothing.
            if (original.size <= room) return@forEachIndexed
            if (room <= 0) return Fit.TooLarge(unshrinkableReason(total, budget))
            val source = runCatching { RelayContentCodec.decodeBytes(original.data) }.getOrNull()
                // Not a size problem, but it is deterministic: the composer
                // decodes the same base64 and would throw, and a throw there
                // returns a bare false that strands the receipt at 'attempting'
                // forever. Naming it here resolves the message instead.
                ?: return Fit.TooLarge(UNREADABLE_REASON)
            val shrunk = shrink(source, original.contentType, room)
                ?: return Fit.TooLarge(shrinkFailedReason(total, budget))
            // ImageShrinker gives up rather than overshoot, but it is the only
            // thing here that is not pure and the cost of trusting it wrongly
            // is a carrier rejection, so the measurement is re-checked.
            if (shrunk.bytes.size > room) return Fit.TooLarge(shrinkFailedReason(total, budget))
            out[index] = RelayAttachment(
                name = renamed(original.name, original.contentType, shrunk.contentType),
                contentType = shrunk.contentType,
                data = RelayContentCodec.encodeBytes(shrunk.bytes),
                size = shrunk.bytes.size,
            )
        }
        return Fit.Ready(out, maxMessageSize)
    }

    fun send(
        context: Context,
        phoneNumber: String,
        content: RelayContent,
        fit: Fit.Ready,
        messageId: String,
        cid: String,
        seq: Int,
    ): Boolean {
        var pduId: String? = null
        return try {
            require(content.type == RelayContentCodec.TYPE_MMS) { "not an MMS content" }
            val id = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
            require(id.length >= 8) { "invalid MMS message id" }
            pduId = id
            val dir = File(context.cacheDir, "mms-pdu").apply { mkdirs() }
            val file = File(dir, "$id.pdu")
            val pdu = MmsPduComposer.compose(
                to = phoneNumber,
                subject = content.subject,
                text = content.text,
                // The fitted list, never content.attachments: this is the one
                // place the carrier's ceiling is allowed to change the bytes.
                attachments = fit.attachments,
            )
            if (pdu.size > fit.maxMessageSize) {
                // MmsAttachmentBudget's PDU overhead is a deliberate
                // over-estimate, so this cannot fire on arithmetic alone -- it
                // fires if the composer grows a header the estimate does not
                // know about. Logged rather than refused: the message is
                // already resolvable through the normal carrier callback, and
                // failing it here would cost a send that the MMSC may well
                // accept.
                Log.w(
                    TAG,
                    "composed PDU ${pdu.size}B exceeds carrier ceiling ${fit.maxMessageSize}B " +
                        "mid=$messageId",
                )
            }
            FileOutputStream(file).use { it.write(pdu) }
            val uri = Uri.parse("content://$AUTHORITY/$id")
            listOf("com.android.phone", "com.android.mms.service").forEach { pkg ->
                try {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // OEMs use different package names; the framework may retain
                    // the creator permission while consuming this PendingIntent.
                }
            }
            val status = PendingIntent.getBroadcast(
                context,
                id.hashCode() and 0x7fffffff,
                Intent(context, CarrierStatusReceiver::class.java)
                    .setAction(CarrierStatusReceiver.ACTION_SENT)
                    // Extras are NOT part of PendingIntent equality; the data
                    // URI gives each send its own callback identity so
                    // FLAG_UPDATE_CURRENT cannot merge two overlapping sends.
                    .setData(Uri.parse("securemsg://mms-send/$id"))
                    .putExtra(CarrierStatusReceiver.EXTRA_MID, messageId)
                    .putExtra(CarrierStatusReceiver.EXTRA_CID, cid)
                    .putExtra(CarrierStatusReceiver.EXTRA_SEQ, seq)
                    .putExtra(CarrierStatusReceiver.EXTRA_PART, 0)
                    .putExtra(CarrierStatusReceiver.EXTRA_PART_COUNT, 1)
                    .putExtra(CarrierStatusReceiver.EXTRA_PDU_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val sms = context.getSystemService(SmsManager::class.java)
            sms.sendMultimediaMessage(context, uri, null, Bundle(), status, messageId.hashCode().toLong())
            Log.i(
                TAG,
                "MMS dispatch accepted to ${PhoneNumberNormalizer.redact(phoneNumber)} mid=$messageId",
            )
            true
        } catch (e: Exception) {
            deletePdu(context, pduId)
            Log.e(TAG, "MMS dispatch failed", e)
            false
        }
    }

    fun deletePdu(context: Context, id: String?) {
        if (id.isNullOrBlank() || !id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) return
        try {
            val uri = Uri.parse("content://$AUTHORITY/$id")
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            File(context.cacheDir, "mms-pdu/$id.pdu").delete()
        } catch (e: Exception) {
            Log.w(TAG, "failed to delete temporary MMS PDU", e)
        }
    }

    /**
     * Not a method reference to [ImageShrinker] itself so that loading this
     * object in the host suite never pulls android.graphics onto the stack:
     * [plan] takes the re-encoder as a parameter, and only [fit] names the one
     * that needs a real decoder.
     */
    private fun shrinkImage(bytes: ByteArray, contentType: String, budget: Int): ReEncoded? =
        ImageShrinker.shrink(bytes, contentType, budget)?.let { ReEncoded(it.bytes, it.contentType) }

    private const val UNREADABLE_REASON = "첨부 파일을 읽지 못해 보내지 않았습니다"

    private fun unshrinkableReason(totalBytes: Long, budget: Int): String =
        "첨부 ${kib(totalBytes)}KB가 통신사 MMS 첨부 한도 ${kib(budget.toLong())}KB를 넘습니다. " +
            "사진이 아닌 첨부는 용량을 줄일 수 없어 보내지 않았습니다"

    private fun shrinkFailedReason(totalBytes: Long, budget: Int): String =
        "사진 ${kib(totalBytes)}KB를 통신사 MMS 첨부 한도 ${kib(budget.toLong())}KB까지 " +
            "줄이지 못해 보내지 않았습니다"

    /** Rounded up, so a reason never reports a limit larger than the real one. */
    private fun kib(bytes: Long): Long = (bytes + 1023) / 1024

    /**
     * The attachment name after a re-encode that changed the media type.
     *
     * [MmsPduComposer] writes the name twice -- the Content-Type name parameter
     * and the Content-Location -- so a receiving stack that saves the part by
     * that name would otherwise write JPEG bytes into a file called .heic.
     * Only the extension moves; the stem the owner chose stays.
     */
    private fun renamed(name: String, from: String, to: String): String {
        val target = mediaType(to)
        if (mediaType(from) == target) return name
        val extension = EXTENSIONS[target] ?: target.substringAfter('/', "")
        if (extension.isBlank()) return name
        val stem = name.substringBeforeLast('.', name).ifBlank { "attachment" }
        return "$stem.$extension"
    }

    private val EXTENSIONS = mapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
    )

    /** Media type alone: a `; charset=` or `; name=` parameter never classifies a part. */
    private fun mediaType(value: String): String =
        value.substringBefore(';').trim().lowercase(Locale.ROOT)
}
