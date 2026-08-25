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

data class ProviderMms(
    val id: Long,
    val address: String,
    val subject: String?,
    val body: String,
    val date: Long,
    val parts: List<ProviderMmsPart>,
)

/** Reads MMS rows and parts owned by the default SMS app. */
object MmsProvider {
    private const val TAG = "MmsProvider"
    private const val MAX_PART_BYTES = RelayContentCodec.MAX_ATTACHMENT_BYTES
    private const val MMS_FROM_TYPE = 137

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
                    if (parts.size >= RelayContentCodec.MAX_ATTACHMENTS) continue
                    val name = listOf(
                        if (nameCol >= 0) cursor.getString(nameCol) else null,
                        if (fileCol >= 0) cursor.getString(fileCol) else null,
                    ).firstOrNull { !it.isNullOrBlank() } ?: "attachment-$partId"
                    val bytes = readPart(context, partContentUri(partId))
                    if (bytes.isNotEmpty() && bytes.size <= MAX_PART_BYTES - attachmentBytes) {
                        parts += ProviderMmsPart(name, contentType, bytes)
                        attachmentBytes += bytes.size
                    }
                }
            }
            ProviderMms(id, address, subject, body.toString(), date, parts)
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
