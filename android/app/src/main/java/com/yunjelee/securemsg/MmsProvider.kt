package com.yunjelee.securemsg

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Log
import java.io.ByteArrayOutputStream
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
                arrayOf(Telephony.Mms.SUBJECT, Telephony.Mms.DATE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val subjectCol = cursor.getColumnIndex(Telephony.Mms.SUBJECT)
                    val dateCol = cursor.getColumnIndex(Telephony.Mms.DATE)
                    subject = if (subjectCol >= 0) cursor.getString(subjectCol) else null
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
                var inspectedParts = 0
                while (cursor.moveToNext() && inspectedParts < 64) {
                    inspectedParts += 1
                    val partId = cursor.getLong(idCol)
                    val contentType = normalizePartContentType(
                        if (typeCol >= 0) cursor.getString(typeCol) else null,
                    )
                    val text = if (textCol >= 0) cursor.getString(textCol).orEmpty() else ""
                    if (contentType.startsWith("text/")) {
                        val decodedText = if (text.isNotEmpty()) {
                            text
                        } else {
                            readPart(context, ContentUris.withAppendedId(partUri, partId))
                                .toString(Charsets.UTF_8)
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
                    val bytes = readPart(context, ContentUris.withAppendedId(partUri, partId))
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
