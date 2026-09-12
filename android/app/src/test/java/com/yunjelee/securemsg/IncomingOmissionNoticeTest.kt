package com.yunjelee.securemsg

import com.yunjelee.securemsg.IncomingOmissionNotice.Kind
import com.yunjelee.securemsg.IncomingOmissionNotice.Omission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingOmissionNoticeTest {
    @Test
    fun `a message whose every part survived carries no notice`() {
        assertEquals("본문", IncomingOmissionNotice.appendTo("본문", emptyList()))
        assertEquals("", IncomingOmissionNotice.appendTo("", emptyList()))
    }

    @Test
    fun `one oversized video reads as a single bracketed line`() {
        assertEquals(
            "[동영상 1개는 용량이 커서 받지 못했습니다]",
            IncomingOmissionNotice.appendTo("", listOf(Omission(Kind.VIDEO, 4_000_000))),
        )
    }

    @Test
    fun `a part lost without a measurement drops the cause claim`() {
        // An image that would not decode is not an image that was too large.
        assertEquals(
            "[사진 1장은 받지 못했습니다]",
            IncomingOmissionNotice.appendTo("", listOf(Omission(Kind.IMAGE))),
        )
        assertEquals(
            "[파일 1개는 받지 못했습니다]",
            IncomingOmissionNotice.appendTo("", listOf(Omission(Kind.FILE))),
        )
    }

    @Test
    fun `omissions are counted once per kind inside one bracket`() {
        val notice = IncomingOmissionNotice.appendTo(
            "",
            listOf(
                Omission(Kind.IMAGE, 3_100_000),
                Omission(Kind.VIDEO),
                Omission(Kind.IMAGE, 5_400_000),
            ),
        )

        assertEquals("[사진 2장은 용량이 커서 받지 못했습니다, 동영상 1개는 받지 못했습니다]", notice)
        assertEquals(1, notice.count { it == '[' })
        assertEquals(1, notice.count { it == ']' })
    }

    @Test
    fun `one unweighed part sinks the cause claim for its whole kind`() {
        assertEquals(
            "[사진 2장은 받지 못했습니다]",
            IncomingOmissionNotice.appendTo(
                "",
                listOf(Omission(Kind.IMAGE, 3_100_000), Omission(Kind.IMAGE)),
            ),
        )
    }

    @Test
    fun `the same losses in a different order produce the same notice`() {
        // The encoded content is the preimage of the relay dedupe fingerprint:
        // a notice that reshuffles relays the same MMS twice.
        val parts = listOf(
            Omission(Kind.FILE, 900_000),
            Omission(Kind.AUDIO, 700_000),
            Omission(Kind.VIDEO, 8_000_000),
            Omission(Kind.IMAGE, 3_000_000),
        )

        assertEquals(
            IncomingOmissionNotice.appendTo("본문", parts),
            IncomingOmissionNotice.appendTo("본문", parts.reversed()),
        )
        assertEquals(
            "본문\n[사진 1장은 용량이 커서 받지 못했습니다, 동영상 1개는 용량이 커서 받지 못했습니다, " +
                "음성 1개는 용량이 커서 받지 못했습니다, 파일 1개는 용량이 커서 받지 못했습니다]",
            IncomingOmissionNotice.appendTo("본문", parts),
        )
    }

    @Test
    fun `the notice follows the body on its own line and never replaces it`() {
        val result = IncomingOmissionNotice.appendTo(
            "사진 보냈어",
            listOf(Omission(Kind.IMAGE, 3_400_000)),
        )

        assertTrue(result.startsWith("사진 보냈어\n"))
        assertEquals(2, result.lines().size)
        assertEquals("[사진 1장은 용량이 커서 받지 못했습니다]", result.lines()[1])
    }

    @Test
    fun `a body already at the ceiling still yields a result within the ceiling`() {
        val body = "가".repeat(IncomingOmissionNotice.MAX_TEXT_CHARS)

        val result = IncomingOmissionNotice.appendTo(body, listOf(Omission(Kind.VIDEO, 9_000_000)))

        assertEquals(IncomingOmissionNotice.MAX_TEXT_CHARS, result.length)
        // The carrier text is what the user actually needs; the notice is cut.
        assertEquals(body, result)
    }

    @Test
    fun `a body just under the ceiling keeps the encoder's require satisfied`() {
        val body = "가".repeat(IncomingOmissionNotice.MAX_TEXT_CHARS - 5)

        val result = IncomingOmissionNotice.appendTo(body, listOf(Omission(Kind.FILE)))

        assertEquals(IncomingOmissionNotice.MAX_TEXT_CHARS, result.length)
        assertTrue(result.startsWith("$body\n[파일"))
    }

    // --- what is worth announcing ------------------------------------------

    @Test
    fun `a smil omission can never be constructed`() {
        assertNull(IncomingOmissionNotice.omissionFor("application/smil", 900))
        assertNull(IncomingOmissionNotice.omissionFor("application/smil+xml", 900))
        assertNull(IncomingOmissionNotice.omissionFor("APPLICATION/SMIL; charset=utf-8", 900))
    }

    @Test
    fun `a smil part is never mentioned in a notice`() {
        val omissions = listOfNotNull(
            IncomingOmissionNotice.omissionFor("application/smil", 900),
            IncomingOmissionNotice.omissionFor("image/jpeg", 3_300_000),
        )
        val result = IncomingOmissionNotice.appendTo("본문", omissions)

        assertEquals("본문\n[사진 1장은 용량이 커서 받지 못했습니다]", result)
        assertFalse(result.contains("smil", ignoreCase = true))
        assertFalse(result.contains("파일"))
    }

    @Test
    fun `a smil-only message carries no notice at all`() {
        val omissions = listOfNotNull(IncomingOmissionNotice.omissionFor("application/smil", 900))

        assertEquals("본문", IncomingOmissionNotice.appendTo("본문", omissions))
    }

    @Test
    fun `a text part is never omitted because the body already carries it`() {
        assertNull(IncomingOmissionNotice.omissionFor("text/plain; charset=utf-8", 40))
        assertNull(IncomingOmissionNotice.omissionFor("text/x-vcard", 40))
    }

    @Test
    fun `media types map to the kind the user would recognize`() {
        assertEquals(Kind.IMAGE, IncomingOmissionNotice.omissionFor("IMAGE/JPEG")?.kind)
        assertEquals(Kind.VIDEO, IncomingOmissionNotice.omissionFor("video/3gpp")?.kind)
        assertEquals(Kind.AUDIO, IncomingOmissionNotice.omissionFor("audio/amr")?.kind)
        assertEquals(Kind.FILE, IncomingOmissionNotice.omissionFor("application/pdf")?.kind)
        // A part whose type the provider never declared is still a loss.
        assertEquals(Kind.FILE, IncomingOmissionNotice.omissionFor(null)?.kind)
        assertEquals(Kind.FILE, IncomingOmissionNotice.omissionFor("")?.kind)
    }

    @Test
    fun `an unmeasurable size is not treated as a measurement`() {
        assertNull(IncomingOmissionNotice.omissionFor("image/jpeg", -1)?.bytes)
        assertEquals(
            "[사진 1장은 받지 못했습니다]",
            IncomingOmissionNotice.appendTo(
                "",
                listOfNotNull(IncomingOmissionNotice.omissionFor("image/jpeg", -1)),
            ),
        )
    }

    @Test
    fun `a zero-byte part is still a measurement`() {
        assertEquals(0, IncomingOmissionNotice.omissionFor("image/jpeg", 0)?.bytes)
    }
}
