package com.yunjelee.securemsg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduComposerTest {
    @Test
    fun composeUsesMultipartHeaderAndTwoPartLengths() {
        val pdu = MmsPduComposer.compose(
            to = "+821012345678",
            subject = null,
            text = "hello",
            attachments = listOf(
                RelayAttachment(
                    name = "photo.jpg",
                    contentType = "image/jpeg",
                    data = RelayContentCodec.encodeBytes(byteArrayOf(1, 2, 3)),
                    size = 3,
                ),
            ),
        )
        var cursor = 0
        assertEquals(0x8C, pdu[cursor++].u8())
        assertEquals(0x80, pdu[cursor++].u8())
        assertEquals(0x98, pdu[cursor++].u8())
        cursor = skipText(pdu, cursor)
        assertEquals(0x8D, pdu[cursor++].u8())
        assertEquals(0x92, pdu[cursor++].u8()) // MMS 1.2 short-integer (0x12 | 0x80)
        assertEquals(0x89, pdu[cursor++].u8())
        assertEquals(1, pdu[cursor++].u8())
        assertEquals(0x81, pdu[cursor++].u8())
        assertEquals(0x97, pdu[cursor++].u8())
        cursor += pdu[cursor].u8() + 1 // encoded-string value length
        assertEquals(0x84, pdu[cursor++].u8())
        val contentTypeLength = pdu[cursor++].u8()
        assertEquals(0xB3, pdu[cursor].u8()) // multipart/related token 0x33
        cursor += contentTypeLength
        assertEquals(2, readUintvar(pdu, cursor).first)
        cursor = readUintvar(pdu, cursor).second

        repeat(2) {
            val header = readUintvar(pdu, cursor)
            cursor = header.second
            val data = readUintvar(pdu, cursor)
            cursor = data.second
            assertTrue(header.first > 0)
            assertTrue(data.first >= 0)
            cursor += header.first + data.first
        }
        assertEquals(pdu.size, cursor)
    }

    @Test
    fun nonAsciiSubjectUsesWspTextEscape() {
        val pdu = MmsPduComposer.compose(
            to = "+821012345678",
            subject = "사진",
            text = "hi",
            attachments = emptyList(),
        )
        // Subject header = 0x96, value-length, charset short-integer (106|0x80),
        // WSP Quote (0x7F) for the >127 first byte, UTF-8 bytes, NUL.
        val expected = byteArrayOf(
            0x96.toByte(), 0x09, 0xEA.toByte(), 0x7F,
            0xEC.toByte(), 0x82.toByte(), 0xAC.toByte(),
            0xEC.toByte(), 0xA7.toByte(), 0x84.toByte(), 0x00,
        )
        assertTrue(containsSequence(pdu, expected))
    }

    @Test
    fun asciiSubjectIsNotEscaped() {
        val pdu = MmsPduComposer.compose(
            to = "+821012345678",
            subject = "Photo",
            text = "hi",
            attachments = emptyList(),
        )
        // Quote must appear only in front of a >127 first octet; an ASCII
        // subject has to stay byte-for-byte identical to its own text.
        val expected = byteArrayOf(
            0x96.toByte(), 0x07, 0xEA.toByte(),
            'P'.code.toByte(), 'h'.code.toByte(), 'o'.code.toByte(),
            't'.code.toByte(), 'o'.code.toByte(), 0x00,
        )
        assertTrue(containsSequence(pdu, expected))
    }

    @Test
    fun textPartDeclaresUtf8CharsetAndBinaryPartDoesNot() {
        val pdu = MmsPduComposer.compose(
            to = "+821012345678",
            subject = null,
            text = "한글 본문",
            attachments = listOf(
                RelayAttachment(
                    name = "photo.jpg",
                    contentType = "image/jpeg",
                    data = RelayContentCodec.encodeBytes(byteArrayOf(1, 2, 3)),
                    size = 3,
                ),
            ),
        )
        val headers = partHeaders(pdu)
        assertEquals(2, headers.size)

        // Text part Content-Type value: text/plain token (0x03|0x80), then the
        // charset parameter P_CHARSET + UTF-8 MIBenum (106|0x80), then the name
        // parameter. Without the charset a spec-following receiver reads the
        // UTF-8 body as us-ascii.
        val textValue = contentTypeValue(headers[0])
        assertEquals(0x83, textValue[0].u8())
        assertEquals(0x81, textValue[1].u8())
        assertEquals(0xEA, textValue[2].u8())
        assertEquals(0x85, textValue[3].u8())

        // Image part: media-type token then the name parameter, no charset.
        val imageValue = contentTypeValue(headers[1])
        assertEquals(0x9E, imageValue[0].u8())
        assertEquals(0x85, imageValue[1].u8())
        assertFalse(containsSequence(imageValue, byteArrayOf(0x81.toByte(), 0xEA.toByte())))

        // The declaration is only worth anything if the bytes match it: a body
        // written in any other encoding while the header says utf-8 is the very
        // failure the parameter exists to prevent.
        assertArrayEquals("한글 본문".toByteArray(Charsets.UTF_8), partData(pdu)[0])
    }

    @Test
    fun textAttachmentBytesAreNotDeclaredUtf8() {
        // A forwarded EUC-KR .csv is opaque to the composer: it never encoded
        // those bytes, so it must not certify their charset.
        val eucKr = byteArrayOf(0xC7.toByte(), 0xD1.toByte(), 0xB1.toByte(), 0xDB.toByte())
        val pdu = MmsPduComposer.compose(
            to = "+821012345678",
            subject = null,
            text = "hi",
            attachments = listOf(
                RelayAttachment(
                    name = "a.csv",
                    contentType = "text/csv",
                    data = RelayContentCodec.encodeBytes(eucKr),
                    size = eucKr.size,
                ),
            ),
        )
        val attachmentValue = contentTypeValue(partHeaders(pdu)[1])
        assertFalse(
            "attachment bytes must not carry a composer-invented charset",
            containsSequence(attachmentValue, byteArrayOf(0x81.toByte(), 0xEA.toByte())),
        )
        assertArrayEquals(eucKr, partData(pdu)[1])
    }

    @Test
    fun unassignedMediaTypeIsWrittenAsTextNotAWspToken() {
        // audio/amr has no assigned WSP token: 0x23/0x24 are the multipart family,
        // so a short-integer here makes the receiver parse the clip as body parts.
        val pdu = MmsPduComposer.compose(
            to = "+821012345678",
            subject = null,
            text = "hi",
            attachments = listOf(
                RelayAttachment(
                    name = "voice.amr",
                    contentType = "audio/amr",
                    data = RelayContentCodec.encodeBytes(byteArrayOf(1, 2, 3)),
                    size = 3,
                ),
            ),
        )
        val value = contentTypeValue(partHeaders(pdu)[1])
        val expected = "audio/amr".toByteArray(Charsets.US_ASCII) + 0.toByte()
        assertArrayEquals(expected, value.copyOfRange(0, expected.size))
    }

    @Test
    fun longPartNameUsesExtendedValueLength() {
        val longName = "a".repeat(40)
        val pdu = MmsPduComposer.compose(
            to = "+821012345678",
            subject = null,
            text = "hi",
            attachments = listOf(
                RelayAttachment(
                    name = longName,
                    contentType = "application/octet-stream",
                    data = RelayContentCodec.encodeBytes(byteArrayOf(1)),
                    size = 1,
                ),
            ),
        )
        var cursor = 0
        assertEquals(0x8C, pdu[cursor++].u8())
        assertEquals(0x80, pdu[cursor++].u8())
        assertEquals(0x98, pdu[cursor++].u8())
        cursor = skipText(pdu, cursor)
        assertEquals(0x8D, pdu[cursor++].u8())
        cursor += 1 // MMS version value
        cursor = skipFrom(pdu, cursor)
        assertEquals(0x97, pdu[cursor++].u8())
        cursor += pdu[cursor].u8() + 1 // To encoded-string
        assertEquals(0x84, pdu[cursor++].u8())
        val contentTypeLength = pdu[cursor++].u8()
        cursor += contentTypeLength
        assertEquals(2, readUintvar(pdu, cursor).first)
        cursor = readUintvar(pdu, cursor).second

        // Part 1: text part headers begin with a plain value-length byte.
        val textHeader = readUintvar(pdu, cursor)
        cursor = textHeader.second
        val textData = readUintvar(pdu, cursor)
        cursor = textData.second
        assertTrue(pdu[cursor].u8() < 31)
        cursor = textData.second + textHeader.first + textData.first

        // Part 2: long octet-stream name — content-type value-length must use
        // the extended form (31 marker + uintvar).
        val attachmentHeader = readUintvar(pdu, cursor)
        cursor = attachmentHeader.second
        val attachmentData = readUintvar(pdu, cursor)
        cursor = attachmentData.second
        assertEquals(31, pdu[cursor].u8())
        cursor += 1
        val extended = readUintvar(pdu, cursor)
        assertTrue(extended.first > 30)
    }

    /** Walks the fixed M-Send.req headers and returns each body part's headers. */
    private fun partHeaders(pdu: ByteArray): List<ByteArray> = parts(pdu).map { it.first }

    /** The body bytes of each part, in the same order as [partHeaders]. */
    private fun partData(pdu: ByteArray): List<ByteArray> = parts(pdu).map { it.second }

    /** Walks the fixed M-Send.req headers and returns each body part as headers to data. */
    private fun parts(pdu: ByteArray): List<Pair<ByteArray, ByteArray>> {
        var cursor = 0
        assertEquals(0x8C, pdu[cursor++].u8()) // X-Mms-Message-Type
        assertEquals(0x80, pdu[cursor++].u8())
        assertEquals(0x98, pdu[cursor++].u8()) // X-Mms-Transaction-Id
        cursor = skipText(pdu, cursor)
        assertEquals(0x8D, pdu[cursor++].u8()) // X-Mms-MMS-Version
        cursor += 1
        cursor = skipFrom(pdu, cursor)
        assertEquals(0x97, pdu[cursor++].u8()) // To
        cursor += pdu[cursor].u8() + 1
        if (pdu[cursor].u8() == 0x96) { // Subject is optional
            cursor += 1
            cursor += pdu[cursor].u8() + 1
        }
        assertEquals(0x84, pdu[cursor++].u8()) // Content-Type
        cursor += pdu[cursor].u8() + 1

        val count = readUintvar(pdu, cursor)
        cursor = count.second
        val parts = mutableListOf<Pair<ByteArray, ByteArray>>()
        repeat(count.first) {
            val headerLength = readUintvar(pdu, cursor)
            cursor = headerLength.second
            val dataLength = readUintvar(pdu, cursor)
            cursor = dataLength.second
            val headers = pdu.copyOfRange(cursor, cursor + headerLength.first)
            cursor += headerLength.first
            parts += headers to pdu.copyOfRange(cursor, cursor + dataLength.first)
            cursor += dataLength.first
        }
        assertEquals(pdu.size, cursor)
        return parts
    }

    /** The Content-Type value of a part header, minus its value-length wrapper. */
    private fun contentTypeValue(header: ByteArray): ByteArray {
        val length = header[0].u8()
        assertTrue("expected a short-form value length, got $length", length < 31)
        return header.copyOfRange(1, 1 + length)
    }

    private fun skipFrom(bytes: ByteArray, start: Int): Int {
        var cursor = start
        assertEquals(0x89, bytes[cursor++].u8())
        val valueLength = bytes[cursor++].u8()
        return if (valueLength < 31) {
            cursor + valueLength
        } else {
            val extended = readUintvar(bytes, cursor)
            extended.second + extended.first
        }
    }

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return true
        }
        return false
    }

    private fun skipText(bytes: ByteArray, start: Int): Int {
        var cursor = start
        while (bytes[cursor++].u8() != 0) Unit
        return cursor
    }

    private fun readUintvar(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var cursor = start
        var value = 0
        while (true) {
            val octet = bytes[cursor++].u8()
            value = (value shl 7) or (octet and 0x7F)
            if (octet and 0x80 == 0) return value to cursor
        }
    }

    private fun Byte.u8(): Int = toInt() and 0xFF
}
