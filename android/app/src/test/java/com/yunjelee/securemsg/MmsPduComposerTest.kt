package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduComposerTest {
    @Test
    fun composeUsesMultipartHeaderAndTwoPartLengths() {
        val pdu = MmsPduComposer.compose(
            from = "insert-address-token",
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
        assertEquals(0x83, pdu[cursor++].u8())
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
            from = "insert-address-token",
            to = "+821012345678",
            subject = "사진",
            text = "hi",
            attachments = emptyList(),
        )
        // Subject header = 0x96, value-length, charset short-integer (106|0x80),
        // WSP 0x1B escape for the >127 first byte, UTF-8 bytes, NUL.
        val expected = byteArrayOf(
            0x96.toByte(), 0x09, 0xEA.toByte(), 0x1B,
            0xEC.toByte(), 0x82.toByte(), 0xAC.toByte(),
            0xEC.toByte(), 0xA7.toByte(), 0x84.toByte(), 0x00,
        )
        assertTrue(containsSequence(pdu, expected))
    }

    @Test
    fun longPartNameUsesExtendedValueLength() {
        val longName = "a".repeat(40)
        val pdu = MmsPduComposer.compose(
            from = "insert-address-token",
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
