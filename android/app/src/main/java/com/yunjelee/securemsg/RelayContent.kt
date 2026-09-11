package com.yunjelee.securemsg

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Cleartext content format inside the already encrypted envelope.
 * The relay server never sees this JSON; it only stores the outer envelope.
 */
data class RelayAttachment(
    val name: String,
    val contentType: String,
    val data: String,
    val size: Int,
)

data class RelayContent(
    val type: String = "text",
    val text: String,
    val subject: String? = null,
    /**
     * "in" for something the carrier delivered, "out" for something this
     * account sent. Sealed inside the envelope so the relay can neither read
     * nor forge it: every message this gateway relays — received and sent
     * alike — reaches the other devices under this gateway's own sid, and
     * without this they cannot tell the two apart.
     *
     * Null on anything minted before the field existed, and deliberately
     * omitted from [encode] when null so an identity hash taken over the
     * encoding of a direction-less content stays byte-identical to what
     * earlier builds produced.
     */
    val direction: String? = null,
    val attachments: List<RelayAttachment> = emptyList(),
)

object RelayContentCodec {
    const val TYPE_TEXT = "text"
    const val TYPE_MMS = "mms"
    const val DIR_IN = "in"
    const val DIR_OUT = "out"
    const val MAX_ATTACHMENT_BYTES = 512 * 1024
    const val MAX_ATTACHMENTS = 8

    fun text(value: String): RelayContent = RelayContent(text = value)

    fun encode(content: RelayContent): String {
        require(content.type == TYPE_TEXT || content.type == TYPE_MMS) { "unsupported content type" }
        require(content.text.length <= 20_000) { "message text is too long" }
        require(content.attachments.size <= MAX_ATTACHMENTS) { "too many attachments" }
        var totalBytes = 0
        content.attachments.forEach { item ->
            require(item.size in 0..MAX_ATTACHMENT_BYTES) { "invalid attachment size" }
            require(isSafeMimeType(item.contentType)) { "invalid attachment content type" }
            val decoded = try {
                decodeBytes(item.data)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("invalid attachment data", e)
            }
            require(decoded.size == item.size) { "attachment size mismatch" }
            require(totalBytes <= MAX_ATTACHMENT_BYTES - item.size) { "attachments are too large" }
            totalBytes += item.size
        }
        val attachments = JSONArray()
        content.attachments.forEach { item ->
            attachments.put(
                JSONObject()
                    .put("name", item.name.take(120))
                    .put("content_type", item.contentType.take(120))
                    .put("data", item.data)
                    .put("size", item.size),
            )
        }
        return JSONObject()
            // Stays 1 even though this now carries `dir`. Both decoders reject
            // any other version and fall back to rendering the raw JSON as the
            // message body, so bumping it would turn every message into
            // gibberish on a client that has not updated yet; an unknown key,
            // by contrast, is simply ignored by both.
            .put("v", 1)
            .put("type", content.type)
            .put("text", content.text)
            .putOpt("subject", content.subject?.take(120))
            .putOpt("dir", content.direction?.takeIf { it == DIR_IN || it == DIR_OUT })
            .put("attachments", attachments)
            .toString()
    }

    fun decode(value: String): RelayContent {
        val obj = try { JSONObject(value) } catch (_: Exception) {
            return text(value.take(20_000))
        }
        if (obj.optInt("v", 0) != 1) return text(value.take(20_000))
        val type = obj.optString("type", TYPE_TEXT)
        if (type != TYPE_TEXT && type != TYPE_MMS) return text(value.take(20_000))
        val out = mutableListOf<RelayAttachment>()
        var totalBytes = 0
        val rows = obj.optJSONArray("attachments") ?: JSONArray()
        var inspected = 0
        var index = 0
        while (index < rows.length() && out.size < MAX_ATTACHMENTS && inspected < 64) {
            val i = index++
            inspected += 1
            val row = rows.optJSONObject(i) ?: continue
            val data = row.optString("data")
            val size = row.optInt("size", -1)
            val contentType = row.optString("content_type", "application/octet-stream")
            if (data.isBlank() || size < 0 || size > MAX_ATTACHMENT_BYTES ||
                !isSafeMimeType(contentType)
            ) continue
            val actualSize = try {
                decodeBytes(data).size
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (actualSize != size || totalBytes + actualSize > MAX_ATTACHMENT_BYTES) continue
            out += RelayAttachment(
                name = row.optString("name", "attachment").take(120),
                contentType = contentType.take(120),
                data = data,
                size = size,
            )
            totalBytes += actualSize
        }
        return RelayContent(
            type = type,
            text = obj.optString("text").take(20_000),
            subject = obj.optString("subject").take(120).takeIf { it.isNotBlank() },
            direction = obj.optString("dir").takeIf { it == DIR_IN || it == DIR_OUT },
            attachments = out,
        )
    }

    /**
     * The attachment rows as they are stored in `messages.attachmentsJson`.
     *
     * Deliberately not [encode]'s array: that one truncates name/content_type
     * to 120 chars for the wire, and reusing it here would silently rewrite
     * already rendered local rows under cover of a shared helper.
     */
    fun attachmentsJson(content: RelayContent): String? {
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

    fun encodeBytes(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBytes(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun isSafeMimeType(value: String): Boolean =
        value.length <= 120 &&
            value.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
}
