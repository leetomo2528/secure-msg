package com.yunjelee.securemsg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject

class RelayContentTest {
    @Test
    fun mmsContentRoundTripsWithBinaryAttachment() {
        val bytes = byteArrayOf(0x00, 0x01, 0x7F, 0xFF.toByte())
        val content = RelayContent(
            type = RelayContentCodec.TYPE_MMS,
            text = "사진을 보냅니다",
            subject = "테스트",
            attachments = listOf(
                RelayAttachment(
                    name = "photo.jpg",
                    contentType = "image/jpeg",
                    data = RelayContentCodec.encodeBytes(bytes),
                    size = bytes.size,
                ),
            ),
        )

        val decoded = RelayContentCodec.decode(RelayContentCodec.encode(content))
        assertEquals(RelayContentCodec.TYPE_MMS, decoded.type)
        assertEquals(content.text, decoded.text)
        assertEquals(content.subject, decoded.subject)
        assertEquals(1, decoded.attachments.size)
        assertEquals("photo.jpg", decoded.attachments[0].name)
        assertArrayEquals(bytes, RelayContentCodec.decodeBytes(decoded.attachments[0].data))
    }

    @Test
    fun directionRoundTripsAndStaysOutOfAnUnmarkedEncoding() {
        val plain = RelayContentCodec.text("문자")
        // The identity hashes for an incoming message are taken over the
        // encoding of a direction-less content. Emitting `dir` here would
        // re-key every one of them at the upgrade boundary.
        assertTrue(!RelayContentCodec.encode(plain).contains("\"dir\""))

        val sent = plain.copy(direction = RelayContentCodec.DIR_OUT)
        assertEquals(RelayContentCodec.DIR_OUT, RelayContentCodec.decode(RelayContentCodec.encode(sent)).direction)
        val received = plain.copy(direction = RelayContentCodec.DIR_IN)
        assertEquals(RelayContentCodec.DIR_IN, RelayContentCodec.decode(RelayContentCodec.encode(received)).direction)
    }

    @Test
    fun unknownDirectionIsRefusedRatherThanCarried() {
        assertTrue(!RelayContentCodec.encode(RelayContentCodec.text("x").copy(direction = "sideways")).contains("dir"))
        val decoded = RelayContentCodec.decode(
            JSONObject().put("v", 1).put("type", RelayContentCodec.TYPE_TEXT)
                .put("text", "x").put("dir", "sideways").toString(),
        )
        assertEquals(null, decoded.direction)
    }

    @Test
    fun aDirectionBearingBodyStillOpensOnAClientThatIgnoresIt() {
        // v stays 1 so an older build parses the body instead of rendering the
        // raw JSON, which is what a version bump would have caused.
        val wire = RelayContentCodec.encode(
            RelayContentCodec.text("안녕").copy(direction = RelayContentCodec.DIR_IN),
        )
        assertEquals(1, JSONObject(wire).optInt("v"))
        assertEquals("안녕", RelayContentCodec.decode(wire).text)
    }

    @Test
    fun legacyPlaintextRemainsTextContent() {
        val decoded = RelayContentCodec.decode("old message")
        assertEquals(RelayContentCodec.TYPE_TEXT, decoded.type)
        assertEquals("old message", decoded.text)
        assertTrue(decoded.attachments.isEmpty())
    }

    @Test
    fun malformedAttachmentIsDroppedBeforeCarrierDispatch() {
        val decoded = RelayContentCodec.decode(
            JSONObject()
                .put("v", 1)
                .put("type", RelayContentCodec.TYPE_MMS)
                .put("text", "x")
                .put("attachments", org.json.JSONArray().put(
                    JSONObject()
                        .put("name", "bad.bin")
                        .put("content_type", "application/octet-stream")
                        .put("data", "AA")
                        .put("size", 99),
                ))
                .toString(),
        )
        assertTrue(decoded.attachments.isEmpty())
    }

    @Test
    fun encodeRejectsForgedAttachmentSize() {
        val content = RelayContent(
            type = RelayContentCodec.TYPE_MMS,
            text = "x",
            attachments = listOf(
                RelayAttachment(
                    name = "bad.bin",
                    contentType = "application/octet-stream",
                    data = RelayContentCodec.encodeBytes(byteArrayOf(1, 2, 3)),
                    size = 2,
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            RelayContentCodec.encode(content)
        }
    }
}
