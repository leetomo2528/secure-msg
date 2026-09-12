package com.yunjelee.securemsg

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class ProviderMmsPart(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * One non-text part row as the relay path sees it, recorded without reading a
 * single byte of it.
 *
 * [ProviderMms.parts] and this list are deliberately not the same thing. parts
 * is the identity preimage and may never move; this is everything the message
 * actually carried, including the rows parts dropped for being over budget or
 * past [RelayContentCodec.MAX_ATTACHMENTS] -- which is exactly the set that
 * used to disappear without a word.
 */
data class ProviderMmsCandidate(
    val partId: Long,
    val name: String,
    val contentType: String,
    /** From openAssetFileDescriptor().length, or -1 when the provider will not say. */
    val declaredSize: Int,
)

data class ProviderMms(
    val id: Long,
    val address: String,
    val subject: String?,
    val body: String,
    val date: Long,
    /**
     * The identity part list. Feeds [identityContent] and, through it, the mid
     * and the provider fingerprint. Nothing may change what lands here.
     */
    val parts: List<ProviderMmsPart>,
    /**
     * Every non-text part row, for the payload path alone. Never reaches an
     * identity hash -- see [identityContent].
     */
    val relayCandidates: List<ProviderMmsCandidate> = emptyList(),
)

/** What one incoming MMS relays: the parts that fit, and the ones that did not. */
data class RelayMaterial(
    val parts: List<ProviderMmsPart>,
    val omissions: List<IncomingOmissionNotice.Omission>,
)

/** Reads MMS rows and parts owned by the default SMS app. */
object MmsProvider {
    private const val TAG = "MmsProvider"
    private const val MAX_PART_BYTES = RelayContentCodec.MAX_ATTACHMENT_BYTES
    private const val MMS_FROM_TYPE = 137

    /**
     * How much of one part the *relay* reader will pull into memory before it
     * gives up, four times the largest ceiling any MMSC in service enforces
     * (see [MmsAttachmentBudget.MAX_MAX_MESSAGE_SIZE]).
     *
     * It has to be far above [MAX_PART_BYTES]: a photo can only be re-encoded
     * down to the budget if its original bytes are in hand, and the identity
     * reader's 512 KiB ceiling is precisely what made every real camera photo
     * unreadable. It still has to be bounded -- this runs inside the default
     * SMS app, and a malformed row claiming to be a gigabyte must not take the
     * process down with an OOM.
     */
    internal const val RELAY_SOURCE_MAX_BYTES = 8 * 1024 * 1024

    internal fun normalizePartContentType(value: String?): String {
        val mediaType = value.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        return mediaType.takeIf {
            it.length <= 120 &&
                it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))
        } ?: "application/octet-stream"
    }

    /**
     * The `charset=` parameter of a raw provider Content-Type, lowercased.
     *
     * [normalizePartContentType] drops every parameter, so this is the second
     * source for the part charset when the `chset` column is empty — see
     * [decodeTextBytes]. Only the parameter value is returned because the same
     * header can carry `name="<user file name>"`, and part metadata must not
     * reach logcat.
     */
    internal fun contentTypeCharsetParam(value: String?): String? =
        value.orEmpty()
            .split(';')
            .drop(1) // the media type itself carries no parameter
            .asSequence()
            .map { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            .firstOrNull { (name, _) -> name.equals("charset", ignoreCase = true) }
            ?.second
            ?.trim('"')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && it.length <= 40 }

    /**
     * IANA MIBenum -> charset, for the `chset`/`sub_cs` columns telephony fills
     * from the PDU.
     *
     * Only the values a phone actually sees are mapped by hand; anything else
     * goes through [Charset.forName], which knows the IANA names the registry
     * uses. 0 means "the carrier declared nothing" and returns null so the
     * caller can apply its own default.
     */
    internal fun charsetForMib(mib: Int): Charset? = when (mib) {
        0 -> null
        3 -> Charsets.US_ASCII
        4 -> Charsets.ISO_8859_1
        106 -> Charsets.UTF_8
        // UCS-2 without a BOM is big-endian; Korean gateways still emit it.
        1000, 1013 -> Charsets.UTF_16BE
        1014 -> Charsets.UTF_16LE
        1015 -> Charsets.UTF_16
        36, 38 -> charsetForName("EUC-KR")
        37 -> charsetForName("ISO-2022-KR")
        else -> null
    }

    /** A charset by name, or null when this device does not have it. */
    internal fun charsetForName(name: String?): Charset? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 40) return null
        return try {
            Charset.forName(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Re-decodes a provider text column the platform stored as an ISO-8859-1
     * view of the still-encoded bytes.
     *
     * `PduPersister` writes the MMS subject as `toIsoString(rawBytes)` and puts
     * the real MIBenum in a companion column, so the Korean subject of an
     * EUC-KR or UCS-2 MMS arrives here as Latin-1 mojibake. ISO-8859-1 is a
     * byte<->char bijection, which is exactly why the original bytes are still
     * recoverable — unlike a part body, which the platform has already decoded.
     *
     * Left untouched when the value cannot be a byte view (it holds a character
     * above U+00FF, so some OEM stored it decoded) or when the bytes are not
     * valid in the declared charset, because keeping the platform's string is
     * always better than manufacturing replacement characters.
     */
    internal fun decodeIsoStoredText(value: String?, mib: Int): String? {
        if (value.isNullOrEmpty()) return value
        val charset = charsetForMib(mib) ?: return value
        if (charset == Charsets.ISO_8859_1) return value
        if (value.any { it.code > 0xFF }) return value
        return strictDecode(value.toByteArray(Charsets.ISO_8859_1), charset) ?: value
    }

    /**
     * Part bytes -> text, using the charset the part declares.
     *
     * The `chset` column wins, the Content-Type `charset=` parameter is the
     * fallback, and UTF-8 is the default only when neither says anything. A
     * strict decode that fails falls back rather than emitting U+FFFD (or, for
     * UCS-2 read as UTF-8, a NUL for every ASCII character) into Room, the
     * relay payload and the notification.
     */
    internal fun decodeTextBytes(bytes: ByteArray, mib: Int, contentTypeCharset: String?): String {
        if (bytes.isEmpty()) return ""
        val declared = charsetForMib(mib) ?: charsetForName(contentTypeCharset)
        declared?.let { charset -> strictDecode(bytes, charset)?.let { return it } }
        return strictDecode(bytes, Charsets.UTF_8) ?: String(bytes, Charsets.ISO_8859_1)
    }

    /** Decodes, or null when [bytes] are not valid in [charset]. */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    fun createDownloadTarget(context: Context): Pair<Long, android.net.Uri>? {
        return try {
            val values = ContentValues().apply {
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L)
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.CREATOR, context.packageName)
            }
            val uri = context.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values)
                ?: return null
            val id = ContentUris.parseId(uri)
            id to uri
        } catch (e: Exception) {
            Log.e(TAG, "failed to create MMS download target", e)
            null
        }
    }

    fun read(context: Context, id: Long): ProviderMms? {
        if (id <= 0) return null
        return try {
            val messageUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
            var subject: String? = null
            var date = System.currentTimeMillis()
            context.contentResolver.query(
                messageUri,
                arrayOf(
                    Telephony.Mms.SUBJECT,
                    Telephony.Mms.SUBJECT_CHARSET,
                    Telephony.Mms.DATE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val subjectCol = cursor.getColumnIndex(Telephony.Mms.SUBJECT)
                    val subjectCharsetCol = cursor.getColumnIndex(Telephony.Mms.SUBJECT_CHARSET)
                    val dateCol = cursor.getColumnIndex(Telephony.Mms.DATE)
                    // The subject is the one MMS text field whose original bytes
                    // survive in the provider, so it is also the one that can be
                    // put back together; it feeds the notification, the relay
                    // payload and the blocklist keyword match.
                    subject = decodeIsoStoredText(
                        if (subjectCol >= 0) cursor.getString(subjectCol) else null,
                        if (subjectCharsetCol >= 0) cursor.getInt(subjectCharsetCol) else 0,
                    )
                    val seconds = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    if (seconds > 0) date = seconds * 1000L
                }
            }

            val address = context.contentResolver.query(
                android.net.Uri.parse("content://mms/addr"),
                arrayOf(Telephony.Mms.Addr.ADDRESS),
                "${Telephony.Mms.Addr.MSG_ID} = ? AND ${Telephony.Mms.Addr.TYPE} = ?",
                arrayOf(id.toString(), MMS_FROM_TYPE.toString()),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()

            val parts = mutableListOf<ProviderMmsPart>()
            val candidates = mutableListOf<ProviderMmsCandidate>()
            val body = StringBuilder()
            var attachmentBytes = 0
            val partUri = Telephony.Mms.Part.getPartUriForMessage(id.toString())
            context.contentResolver.query(
                partUri,
                arrayOf(
                    BaseColumns._ID,
                    Telephony.Mms.Part.CONTENT_TYPE,
                    Telephony.Mms.Part.NAME,
                    Telephony.Mms.Part.FILENAME,
                    Telephony.Mms.Part.TEXT,
                    Telephony.Mms.Part.CHARSET,
                ),
                null,
                null,
                "${Telephony.Mms.Part.SEQ} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val typeCol = cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                val nameCol = cursor.getColumnIndex(Telephony.Mms.Part.NAME)
                val fileCol = cursor.getColumnIndex(Telephony.Mms.Part.FILENAME)
                val textCol = cursor.getColumnIndex(Telephony.Mms.Part.TEXT)
                val charsetCol = cursor.getColumnIndex(Telephony.Mms.Part.CHARSET)
                var inspectedParts = 0
                while (cursor.moveToNext() && inspectedParts < 64) {
                    inspectedParts += 1
                    val partId = cursor.getLong(idCol)
                    val rawContentType = if (typeCol >= 0) cursor.getString(typeCol) else null
                    val contentType = normalizePartContentType(rawContentType)
                    val text = if (textCol >= 0) cursor.getString(textCol).orEmpty() else ""
                    if (contentType.startsWith("text/")) {
                        val mib = if (charsetCol >= 0) cursor.getInt(charsetCol) else 0
                        val ctCharset = contentTypeCharsetParam(rawContentType)
                        // A TEXT column value was decoded by the platform
                        // PduPersister, which applied the part charset before
                        // writing it, so it is taken as-is. Everything the
                        // persister does not put in that column (vCard, iCal,
                        // csv, ...) is still raw bytes in a file, and those have
                        // to be decoded with the charset the part declares --
                        // reading UCS-2 as UTF-8 turns every ASCII character
                        // into a NUL. No body text is logged.
                        Log.i(
                            TAG,
                            "mms part id=$partId ct=$contentType chset=$mib " +
                                "ctCharset=$ctCharset textEmpty=${text.isEmpty()}",
                        )
                        val decodedText = if (text.isNotEmpty()) {
                            text
                        } else {
                            decodeTextBytes(
                                readPart(context, partContentUri(partId)),
                                mib,
                                ctCharset,
                            )
                        }
                        if (decodedText.isNotBlank() && body.length < 20_000) {
                            if (body.isNotEmpty()) body.append('\n')
                            body.append(decodedText.take(20_000 - body.length))
                        }
                        continue
                    }
                    val name = listOf(
                        if (nameCol >= 0) cursor.getString(nameCol) else null,
                        if (fileCol >= 0) cursor.getString(fileCol) else null,
                    ).firstOrNull { !it.isNullOrBlank() } ?: "attachment-$partId"
                    // Recorded BEFORE the MAX_ATTACHMENTS early-out below, on
                    // purpose: a row the identity list refuses is exactly a row
                    // the user was never told about, and only the payload path
                    // can still announce it. Metadata alone -- no part byte is
                    // read here, so a message that is about to be deduped away
                    // pays nothing beyond one descriptor open per part.
                    candidates += ProviderMmsCandidate(
                        partId = partId,
                        name = name,
                        contentType = contentType,
                        declaredSize = declaredPartSize(context, partContentUri(partId)),
                    )
                    if (parts.size >= RelayContentCodec.MAX_ATTACHMENTS) continue
                    val bytes = readPart(context, partContentUri(partId))
                    if (bytes.isNotEmpty() && bytes.size <= MAX_PART_BYTES - attachmentBytes) {
                        parts += ProviderMmsPart(name, contentType, bytes)
                        attachmentBytes += bytes.size
                    }
                }
            }
            ProviderMms(id, address, subject, body.toString(), date, parts, candidates)
        } catch (e: Exception) {
            Log.e(TAG, "failed to read MMS id=$id", e)
            null
        }
    }

    fun recentInbox(context: Context, limit: Int = 20): List<Long> {
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                Telephony.Mms.Inbox.CONTENT_URI,
                arrayOf(BaseColumns._ID),
                null,
                null,
                "${Telephony.Mms.DATE} DESC LIMIT ${limit.coerceIn(1, 50)}",
            )?.use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getLong(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to list MMS inbox", e)
        }
        return ids
    }

    fun delete(context: Context, id: Long) {
        try {
            context.contentResolver.delete(
                ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id), null, null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "failed to delete MMS id=$id", e)
        }
    }

    /**
     * The URI a part's bytes can actually be opened from.
     *
     * Parts are *queried* through `content://mms/<msgId>/part`, but telephony's
     * `openFile` only matches the `part/#` pattern -- passing the per-message
     * form back to `openInputStream` always throws FileNotFoundException, which
     * is why MMS attachments never made it out of here.
     */
    private fun partContentUri(partId: Long): android.net.Uri =
        ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, partId)

    /**
     * The content whose encoding is the incoming-MMS identity preimage.
     *
     * It lives next to the list it reads because this exact expression --
     * [ProviderMms.parts] in provider order, each part's name and normalized
     * MIME as read, base64 of the bytes as stored -- is what every message
     * already in `processed_mms` on the owner's phone was keyed by. Change what
     * goes in and every one of them re-keys at the upgrade boundary, and the
     * gateway relays a duplicate burst of messages the owner already has; the
     * APK installs itself unattended within twelve hours of a release, so that
     * is a certainty, not a risk. RelayContentTest pins the encoding against a
     * frozen literal for that reason.
     *
     * [ProviderMms.relayCandidates] is deliberately not consulted here.
     */
    fun identityContent(mms: ProviderMms): RelayContent = RelayContent(
        type = RelayContentCodec.TYPE_MMS,
        text = mms.body,
        subject = mms.subject,
        attachments = mms.parts.map {
            RelayAttachment(
                it.name,
                it.contentType,
                RelayContentCodec.encodeBytes(it.bytes),
                it.bytes.size,
            )
        },
    )

    /**
     * The parts this message can actually relay, plus one omission per part it
     * cannot -- the payload half of the identity/payload split.
     *
     * Runs only after the dedupe and blocklist gates have passed: it decodes
     * and re-encodes multi-megapixel bitmaps, and the caller holds the mutex
     * that serializes every incoming carrier event while it does.
     *
     * Never throws. A failure here must degrade to exactly today's behaviour --
     * the message relays with whatever the identity list already held -- rather
     * than abort a message the gateway could otherwise deliver.
     */
    fun materializeRelayParts(context: Context, mms: ProviderMms, budget: Int): RelayMaterial = try {
        materialize(
            candidates = mms.relayCandidates,
            budget = budget,
            read = { readRelayPart(context, partContentUri(it.partId), RELAY_SOURCE_MAX_BYTES) },
            readTruncated = {
                readRelayPartPrefix(context, partContentUri(it.partId), RELAY_SOURCE_MAX_BYTES)
            },
            shrink = { candidate, bytes, allowance ->
                ImageShrinker.shrink(bytes, candidate.contentType, allowance)?.let {
                    // The name is kept as the sender wrote it even when the
                    // re-encode changed the MIME: both clients render by
                    // content_type and use the name only as a download file
                    // name, and rewriting it would make the same photo look
                    // like a different attachment to a user comparing devices.
                    ProviderMmsPart(candidate.name, it.contentType, it.bytes)
                }
            },
        )
    } catch (e: Exception) {
        Log.e(TAG, "failed to materialize relay parts for MMS id=${mms.id}", e)
        RelayMaterial(emptyList(), emptyList())
    }

    /**
     * The decision table, with every framework call hoisted into a lambda so the
     * whole thing runs in the host unit suite.
     *
     * @param read pulls a part's bytes, distinguishing "too big to hold" from
     *   "not there yet"; see [ImageShrinkPolicy.PartRead].
     * @param readTruncated best-effort prefix of a part that blew past the read
     *   ceiling, empty when nothing could be read. A truncated JPEG still
     *   decodes to a partial image on Android, and a partial photo beats
     *   announcing a loss.
     * @param shrink re-encodes one image into an allowance, or returns null when
     *   it cannot.
     */
    internal fun materialize(
        candidates: List<ProviderMmsCandidate>,
        budget: Int,
        read: (ProviderMmsCandidate) -> ImageShrinkPolicy.PartRead,
        readTruncated: (ProviderMmsCandidate) -> ByteArray,
        shrink: (ProviderMmsCandidate, ByteArray, Int) -> ProviderMmsPart?,
    ): RelayMaterial {
        // Clamped to the codec cap whatever the caller asked for: everything
        // below guarantees the relayed total fits `cap`, and that guarantee is
        // what keeps RelayContentCodec.encode from throwing on the payload and
        // taking a deliverable message down with it.
        val cap = budget.coerceIn(0, RelayContentCodec.MAX_ATTACHMENT_BYTES)
        val allowances = ImageShrinkPolicy.allocate(
            candidates.map { ImageShrinkPolicy.Candidate(it.contentType, it.declaredSize) },
            cap,
        )
        val parts = mutableListOf<ProviderMmsPart>()
        val omissions = mutableListOf<IncomingOmissionNotice.Omission>()
        var used = 0

        fun accept(part: ProviderMmsPart): Boolean {
            if (part.bytes.isEmpty()) return false
            if (parts.size >= RelayContentCodec.MAX_ATTACHMENTS) return false
            if (part.bytes.size > cap - used) return false
            parts += part
            used += part.bytes.size
            return true
        }

        fun omit(contentType: String, bytes: Int?) {
            omissions += IncomingOmissionNotice.Omission(
                ImageShrinkPolicy.omissionKind(contentType),
                bytes,
            )
        }

        candidates.forEachIndexed { index, candidate ->
            val type = candidate.contentType
            // The MMS layout script: consumes no budget, takes no slot, and is
            // never announced. Telling the user a smil was lost would report a
            // failure on a message that arrived complete.
            if (ImageShrinkPolicy.isIgnorable(type)) return@forEachIndexed
            val allowance = allowances.getOrElse(index) { 0 }
            val passThrough = ImageShrinkPolicy.isPassThrough(type)
            val shrinkable = ImageShrinkPolicy.isShrinkable(type)
            // Video, audio and documents cannot be made smaller here, but they
            // can still FIT: the previous build relayed any part under the wire
            // cap, and the web renders it as a download link. Judging one by its
            // type alone dropped a 40 KB voice clip that had eight times the
            // room it needed -- and told the owner it was lost, on a phone whose
            // own MMS store still held it.
            //
            // The descriptor's declared size is what keeps this cheap: a clip
            // too big to carry is omitted without ever being opened, so reading
            // a 30 MB video to learn it is 30 MB remains a cost this path does
            // not pay.
            if (!passThrough && !shrinkable && candidate.declaredSize > allowance) {
                omit(type, candidate.declaredSize)
                return@forEachIndexed
            }

            val outcome = read(candidate)
            // An empty successful read is a part that opened but held nothing:
            // a placeholder the download has not filled in. It is treated as
            // Failed rather than as a loss, for the same reason -- there is no
            // photo to mourn yet.
            if (outcome is ImageShrinkPolicy.PartRead.Ok && outcome.bytes.isEmpty()) {
                return@forEachIndexed
            }
            // What the notice may honestly claim was weighed. A truncated read
            // measures the prefix, not the part, so it does not count; the
            // provider's own declared size does.
            val measured = when (outcome) {
                is ImageShrinkPolicy.PartRead.Ok -> outcome.bytes.size
                ImageShrinkPolicy.PartRead.TooLarge -> candidate.declaredSize.takeIf { it >= 0 }
                ImageShrinkPolicy.PartRead.Failed -> null
            }
            val source = when (outcome) {
                is ImageShrinkPolicy.PartRead.Ok -> outcome.bytes
                // Still downloading, or an I/O error: say nothing at all and
                // let the caller keep deferring. Announcing a loss seconds
                // before the part lands would tell the owner a photo is gone
                // when it is not, and this being the default SMS app there is
                // no second copy to check it against.
                ImageShrinkPolicy.PartRead.Failed -> return@forEachIndexed
                ImageShrinkPolicy.PartRead.TooLarge ->
                    if (shrinkable) readTruncated(candidate) else ByteArray(0)
            }

            if (passThrough || !shrinkable) {
                // Verbatim or not at all. A GIF because both encoders flatten
                // an animation to its first frame, so "shrinking" one destroys
                // the only thing it was; a clip or a document because nothing
                // here can re-encode it at all.
                //
                // Judged against what is actually left rather than against the
                // pre-split allowance: these bytes cannot be made to fit a
                // share, so a share is the wrong question, and the allocator
                // reserves nothing for a part whose size the provider would not
                // declare. `accept` enforces the same bound again.
                if (!accept(ProviderMmsPart(candidate.name, type, source))) omit(type, measured)
                return@forEachIndexed
            }

            if (source.isEmpty()) {
                omit(type, measured)
                return@forEachIndexed
            }
            // A part that fits as it stands travels as it stands -- but only if
            // it is whole. A truncated prefix is a corrupt file, and relaying
            // one byte for byte would put a broken image in the bubble, which
            // is a worse lie than the omission notice: only a re-encode can
            // turn what was salvaged back into something that opens.
            val whole = outcome !is ImageShrinkPolicy.PartRead.TooLarge
            if (whole && source.size <= allowance &&
                accept(ProviderMmsPart(candidate.name, type, source))
            ) {
                return@forEachIndexed
            }
            // Aim at what is actually left, not merely at what was allocated:
            // a rung chosen for an allowance the running total can no longer
            // hold would spend a full decode and encode on a photo that is
            // then refused anyway.
            val room = minOf(allowance, cap - used)
            val shrunk = if (room > 0) shrink(candidate, source, room) else null
            if (shrunk == null || !accept(shrunk)) omit(type, measured)
        }
        return RelayMaterial(parts, omissions)
    }

    /** Declared byte length of a part, or -1 when the provider will not say. */
    private fun declaredPartSize(context: Context, uri: android.net.Uri): Int = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            val length = descriptor.length
            // UNKNOWN_LENGTH is -1, and a length past Int range is a provider
            // lying about a part no phone ever received; both mean "unmeasured".
            if (length in 0..Int.MAX_VALUE.toLong()) length.toInt() else -1
        } ?: -1
    } catch (_: Exception) {
        // A part that has not finished downloading has no file yet, which is
        // an expected state here and not worth a log line per sweep.
        -1
    }

    /**
     * The relay path's own reader. Deliberately NOT [readPart]: that one feeds
     * the identity hash and must keep returning exactly what it always has,
     * empty ByteArray and all.
     *
     * The three outcomes are the point. Today an oversized photo, an I/O error
     * and a part the carrier is still downloading all collapse into one empty
     * array, which is why a message could never tell "lost" from "not here
     * yet" -- and why it silently chose the wrong one.
     */
    private fun readRelayPart(
        context: Context,
        uri: android.net.Uri,
        limit: Int,
    ): ImageShrinkPolicy.PartRead = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            var overran = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) {
                    overran = true
                    break
                }
                out.write(buf, 0, n)
            }
            if (overran) {
                ImageShrinkPolicy.PartRead.TooLarge
            } else {
                ImageShrinkPolicy.PartRead.Ok(out.toByteArray())
            }
        } ?: ImageShrinkPolicy.PartRead.Failed
    } catch (e: Exception) {
        Log.w(TAG, "failed to read MMS part for relay", e)
        ImageShrinkPolicy.PartRead.Failed
    }

    /**
     * At most [limit] bytes of a part, for the one case worth a second open: a
     * part that overran the ceiling but is still an image. The decoder can
     * usually make a partial bitmap out of a truncated JPEG, and half a photo
     * is worth more to the owner than a line saying it is gone.
     */
    private fun readRelayPartPrefix(context: Context, uri: android.net.Uri, limit: Int): ByteArray = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < limit) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, minOf(n, limit - out.size()))
            }
            out.toByteArray()
        } ?: ByteArray(0)
    } catch (e: Exception) {
        Log.w(TAG, "failed to read truncated MMS part for relay", e)
        ByteArray(0)
    }

    private fun readPart(context: Context, uri: android.net.Uri): ByteArray {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_PART_BYTES) return ByteArray(0)
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: ByteArray(0)
        } catch (e: Exception) {
            Log.w(TAG, "failed to read MMS part", e)
            ByteArray(0)
        }
    }
}
