package com.yunjelee.securemsg

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Small WAP-WSP M-Send.req composer for the public SmsManager MMS API.
 *
 * The framework owns the carrier MMSC/APN. This class only creates the raw
 * multipart/related PDU that SmsManager uploads through that configuration.
 * The layout follows the same WSP rules used by Android's open-source
 * PduComposer: short-integer content types, value-length wrappers, and two
 * uintvar lengths for each multipart body part.
 */
object MmsPduComposer {
    private const val MESSAGE_TYPE = 0x8C
    private const val MESSAGE_TYPE_SEND_REQ = 0x80
    private const val TRANSACTION_ID = 0x98
    private const val MMS_VERSION = 0x8D
    private const val FROM = 0x89
    private const val TO = 0x97
    private const val SUBJECT = 0x96
    private const val CONTENT_TYPE = 0x84

    private const val FROM_ADDRESS_PRESENT_TOKEN = 0x80
    private const val FROM_INSERT_ADDRESS_TOKEN = 0x81

    // PduContentTypes / PduPart well-known WSP tokens.
    private const val MULTIPART_RELATED = 0x33
    private const val TEXT_PLAIN = 0x03
    private const val IMAGE_GIF = 0x1D
    private const val IMAGE_JPEG = 0x1E
    private const val IMAGE_PNG = 0x20
    private const val AUDIO_AMR = 0x23
    private const val VIDEO_3GPP = 0x24

    private const val P_DEP_NAME = 0x85
    private const val P_DEP_START = 0x8A
    private const val P_CT_MR_TYPE = 0x89
    private const val P_CONTENT_ID = 0xC0
    private const val P_CONTENT_LOCATION = 0x8E

    fun compose(
        from: String,
        to: String,
        subject: String?,
        text: String,
        attachments: List<RelayAttachment>,
    ): ByteArray {
        val parts = mutableListOf<Pair<ByteArray, ByteArray>>()
        parts += part("text/plain", "text.txt", text.toByteArray(StandardCharsets.UTF_8), "text")
        attachments.forEachIndexed { index, attachment ->
            parts += part(
                attachment.contentType,
                attachment.name,
                RelayContentCodec.decodeBytes(attachment.data),
                "part-${index + 1}",
            )
        }

        val body = ByteArrayOutputStream()
        appendUintvar(body, parts.size)
        parts.forEach { (headers, data) ->
            // multipart-body = header-length data-length headers data
            appendUintvar(body, headers.size)
            appendUintvar(body, data.size)
            body.write(headers)
            body.write(data)
        }

        val out = ByteArrayOutputStream()
        out.write(MESSAGE_TYPE)
        out.write(MESSAGE_TYPE_SEND_REQ)
        out.write(TRANSACTION_ID)
        appendText(out, UUID.randomUUID().toString().replace("-", ""))
        out.write(MMS_VERSION)
        appendShortInteger(out, 0x03) // MMS 1.2

        appendFrom(out, from)
        out.write(TO)
        appendAddress(out, to)

        if (!subject.isNullOrBlank()) {
            out.write(SUBJECT)
            appendEncodedString(out, subject.take(120))
        }

        // Content-Type = multipart/related; start=<text>; type=text/plain.
        val topContentType = ByteArrayOutputStream()
        appendShortInteger(topContentType, MULTIPART_RELATED)
        topContentType.write(P_DEP_START)
        appendText(topContentType, "<text>")
        topContentType.write(P_CT_MR_TYPE)
        appendText(topContentType, "text/plain")
        out.write(CONTENT_TYPE)
        appendValueLength(out, topContentType.size())
        out.write(topContentType.toByteArray())
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun part(
        contentType: String,
        name: String,
        data: ByteArray,
        contentId: String,
    ): Pair<ByteArray, ByteArray> {
        val safeName = sanitizeName(name)
        val safeContentId = contentId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val safeContentType = contentType.takeIf {
            it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
        } ?: "application/octet-stream"
        val contentTypeValue = ByteArrayOutputStream()
        val token = contentTypeToken(safeContentType)
        if (token != null) appendShortInteger(contentTypeValue, token) else {
            appendText(contentTypeValue, safeContentType.take(120))
        }
        contentTypeValue.write(P_DEP_NAME)
        appendText(contentTypeValue, safeName)

        val headers = ByteArrayOutputStream()
        appendValueLength(headers, contentTypeValue.size())
        headers.write(contentTypeValue.toByteArray())
        headers.write(P_CONTENT_ID)
        appendQuotedString(headers, "<$safeContentId>")
        headers.write(P_CONTENT_LOCATION)
        appendText(headers, safeName)
        return headers.toByteArray() to data
    }

    private fun sanitizeName(value: String): String {
        val sanitized = value
            .replace(Regex("[\\u0000-\\u001F\\u007F<>\"\\\\]"), "_")
            .trim()
            .take(120)
        return sanitized.ifBlank { "attachment" }
    }

    private fun appendFrom(out: ByteArrayOutputStream, from: String) {
        out.write(FROM)
        if (from == "insert-address-token") {
            // From = value-length insert-address-token.
            out.write(1)
            out.write(FROM_INSERT_ADDRESS_TOKEN)
            return
        }
        val value = ByteArrayOutputStream()
        value.write(FROM_ADDRESS_PRESENT_TOKEN)
        appendEncodedString(value, addressWithType(from))
        appendValueLength(out, value.size())
        out.write(value.toByteArray())
    }

    private fun appendAddress(out: ByteArrayOutputStream, address: String) {
        appendEncodedString(out, addressWithType(address))
    }

    private fun addressWithType(address: String): String = "$address/TYPE=PLMN"

    /** Common WSP content-type tokens; string fallback handles OEM-specific types. */
    private fun contentTypeToken(type: String): Int? {
        return when (type.lowercase()) {
            "text/plain" -> TEXT_PLAIN
            "image/gif" -> IMAGE_GIF
            "image/jpeg", "image/jpg" -> IMAGE_JPEG
            "image/png" -> IMAGE_PNG
            "audio/amr" -> AUDIO_AMR
            "video/3gpp" -> VIDEO_3GPP
            else -> null
        }
    }

    private fun appendShortInteger(out: ByteArrayOutputStream, value: Int) {
        require(value in 0..127) { "WSP short-integer out of range: $value" }
        out.write(value or 0x80)
    }

    private fun appendText(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        // WSP 8.4.2.1: a Text-value whose first character is in the range
        // 128-255 must be prefixed with the escape character 0x1B. This is
        // the common case for Korean subjects and file names.
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xff) > 127) out.write(0x1B)
        out.write(bytes)
        out.write(0)
    }

    private fun appendEncodedString(out: ByteArrayOutputStream, value: String) {
        val encoded = ByteArrayOutputStream()
        // UTF-8 is MIBenum 106, encoded as a WSP short-integer.
        appendShortInteger(encoded, 106)
        appendText(encoded, value)
        appendValueLength(out, encoded.size())
        out.write(encoded.toByteArray())
    }

    private fun appendQuotedString(out: ByteArrayOutputStream, value: String) {
        out.write(0x22)
        out.write(value.toByteArray(StandardCharsets.UTF_8))
        out.write(0)
    }

    private fun appendValueLength(out: ByteArrayOutputStream, value: Int) {
        require(value >= 0) { "negative WSP value length" }
        if (value < 31) {
            out.write(value)
        } else {
            out.write(31)
            appendUintvar(out, value)
        }
    }

    private fun appendUintvar(out: ByteArrayOutputStream, value: Int) {
        var v = value.coerceAtLeast(0)
        val bytes = ByteArray(5)
        var index = bytes.lastIndex
        bytes[index] = (v and 0x7F).toByte()
        while (v ushr 7 != 0) {
            v = v ushr 7
            index -= 1
            bytes[index] = ((v and 0x7F) or 0x80).toByte()
        }
        out.write(bytes, index, bytes.size - index)
    }
}
