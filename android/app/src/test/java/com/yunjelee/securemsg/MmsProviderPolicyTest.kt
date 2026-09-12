package com.yunjelee.securemsg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsProviderPolicyTest {
    @Test
    fun `provider MIME is lowercased and parameters are stripped`() {
        assertEquals("image/jpeg", MmsProvider.normalizePartContentType(" Image/JPEG; charset=UTF-8"))
        assertEquals("text/plain", MmsProvider.normalizePartContentType("TEXT/PLAIN ; format=flowed"))
    }

    @Test
    fun `blank and invalid provider MIME use binary fallback`() {
        val fallback = "application/octet-stream"
        assertEquals(fallback, MmsProvider.normalizePartContentType(null))
        assertEquals(fallback, MmsProvider.normalizePartContentType("  ; charset=utf-8"))
        assertEquals(fallback, MmsProvider.normalizePartContentType("text html; charset=utf-8"))
        assertEquals(fallback, MmsProvider.normalizePartContentType("image/"))
    }

    @Test
    fun `provider charset parameter is extracted for diagnostics`() {
        assertEquals("euc-kr", MmsProvider.contentTypeCharsetParam("text/plain; charset=EUC-KR"))
        assertEquals("utf-8", MmsProvider.contentTypeCharsetParam("text/plain;charset=\"utf-8\""))
        assertEquals(
            "ks_c_5601-1987",
            MmsProvider.contentTypeCharsetParam("text/plain; name=a.txt ; Charset = ks_c_5601-1987"),
        )
    }

    @Test
    fun `missing charset parameter yields null and never leaks other parameters`() {
        assertNull(MmsProvider.contentTypeCharsetParam(null))
        assertNull(MmsProvider.contentTypeCharsetParam("text/plain"))
        assertNull(MmsProvider.contentTypeCharsetParam("text/plain; charset="))
        // A media type that merely *contains* "charset" is not a parameter.
        assertNull(MmsProvider.contentTypeCharsetParam("charset=euc-kr"))
        // Part file names live in the same header and must never come back.
        assertNull(MmsProvider.contentTypeCharsetParam("image/jpeg; name=\"가족사진.jpg\""))
    }

    // --- charset decoding --------------------------------------------------

    @Test
    fun `a korean subject stored as latin-1 bytes is recovered from its MIBenum`() {
        // What the platform hands back: PduPersister.toIsoString over the raw
        // EUC-KR subject bytes, with the real charset in sub_cs.
        val raw = "긴급 안내".toByteArray(charset("EUC-KR"))
        val stored = String(raw, Charsets.ISO_8859_1)

        assertEquals("긴급 안내", MmsProvider.decodeIsoStoredText(stored, 36))
    }

    @Test
    fun `a UCS-2 subject is recovered too`() {
        val raw = "안내".toByteArray(Charsets.UTF_16BE)
        assertEquals("안내", MmsProvider.decodeIsoStoredText(String(raw, Charsets.ISO_8859_1), 1000))
    }

    @Test
    fun `an already-decoded subject is left alone`() {
        // Some OEM providers store the decoded string. A character above U+00FF
        // cannot be a byte view, so re-decoding it would destroy it.
        assertEquals("긴급 안내", MmsProvider.decodeIsoStoredText("긴급 안내", 36))
        // Nothing declared, nothing to redo.
        assertEquals("Hello", MmsProvider.decodeIsoStoredText("Hello", 0))
        assertNull(MmsProvider.decodeIsoStoredText(null, 106))
    }

    @Test
    fun `a subject whose bytes contradict its MIBenum keeps the platform string`() {
        // Lone 0xFF is not valid UTF-8; manufacturing U+FFFD is worse than
        // passing the provider's own view through.
        val stored = String(byteArrayOf(0x41, 0xFF.toByte()), Charsets.ISO_8859_1)
        assertEquals(stored, MmsProvider.decodeIsoStoredText(stored, 106))
    }

    @Test
    fun `a text part file is decoded with the charset the part declares`() {
        val eucKr = "이름:홍길동".toByteArray(charset("EUC-KR"))
        assertEquals("이름:홍길동", MmsProvider.decodeTextBytes(eucKr, 36, null))
        // chset empty: the Content-Type parameter is the second source.
        assertEquals("이름:홍길동", MmsProvider.decodeTextBytes(eucKr, 0, "euc-kr"))
    }

    @Test
    fun `UCS-2 part bytes do not become NUL-riddled UTF-8`() {
        val ucs2 = "BEGIN:VCARD".toByteArray(Charsets.UTF_16BE)
        val decoded = MmsProvider.decodeTextBytes(ucs2, 1000, null)

        assertEquals("BEGIN:VCARD", decoded)
        assertFalse("NUL must never reach Room or a notification", decoded.contains('\u0000'))
    }

    @Test
    fun `an undeclared or unusable charset falls back to UTF-8`() {
        val utf8 = "본문".toByteArray(Charsets.UTF_8)
        assertEquals("본문", MmsProvider.decodeTextBytes(utf8, 0, null))
        assertEquals("본문", MmsProvider.decodeTextBytes(utf8, 0, "no-such-charset"))
        assertEquals("", MmsProvider.decodeTextBytes(ByteArray(0), 106, null))
    }

    @Test
    fun `bytes that contradict every candidate charset still decode losslessly`() {
        // Lone 0xFF: not UTF-8, not anything else the declaration claims. The
        // byte-preserving fallback keeps the part readable instead of turning
        // it into U+FFFD soup.
        val decoded = MmsProvider.decodeTextBytes(byteArrayOf(0x41, 0xFF.toByte()), 106, null)

        assertEquals("A\u00FF", decoded)
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test
    fun `recent MMS processing continues after one row fails`() = runBlocking {
        val processed = mutableListOf<Long>()
        val failures = mutableListOf<Long>()

        MmsRowProcessor.process(listOf(31L, 32L, 33L), processRow = { id ->
            processed += id
            if (id == 32L) error("poison row")
        }, onFailure = { id, _ -> failures += id })

        assertEquals(listOf(31L, 32L, 33L), processed)
        assertEquals(listOf(32L), failures)
    }

    @Test(expected = CancellationException::class)
    fun `recent MMS processing preserves coroutine cancellation`() = runBlocking {
        MmsRowProcessor.process(listOf(1L), { throw CancellationException("stop") }, { _, _ -> })
        }

    // --- relay materialization ---------------------------------------------
    //
    // The payload half of the identity/payload split. Every case below asserts
    // on the *relayed* set; the identity list these rows came from is asserted
    // to be untouched by RelayContentTest's frozen encoding.

    @Test
    fun `the layout script is dropped from the relay while the identity list keeps it`() {
        val row = mms(
            parts = listOf(
                ProviderMmsPart("smil.xml", "application/smil", ByteArray(120)),
                ProviderMmsPart("photo.jpg", "image/jpeg", ByteArray(64)),
            ),
            relayCandidates = listOf(
                candidate(1L, "application/smil", 120),
                candidate(2L, "image/jpeg", 64),
            ),
        )

        // The hash preimage still carries the smil part, exactly as it did for
        // every message already sitting in processed_mms on the phone.
        assertEquals(
            listOf("application/smil", "image/jpeg"),
            MmsProvider.identityContent(row).attachments.map { it.contentType },
        )

        val material = materialize(row.relayCandidates, reads = mapOf(1L to ok(120), 2L to ok(64)))

        assertEquals(listOf("image/jpeg"), material.parts.map { it.contentType })
        // And it is never announced: nothing the user could have seen was lost.
        assertTrue(material.omissions.isEmpty())
    }

    @Test
    fun `a part that has not finished downloading yields neither a part nor an omission`() {
        val material = materialize(
            listOf(candidate(1L, "image/jpeg", -1)),
            reads = mapOf(1L to ImageShrinkPolicy.PartRead.Failed),
        )

        assertTrue(material.parts.isEmpty())
        // Announcing a loss here would tell the owner a photo is gone seconds
        // before it lands, and the caller must be free to keep deferring.
        assertTrue(material.omissions.isEmpty())
        assertEquals("본문", IncomingOmissionNotice.appendTo("본문", material.omissions))
    }

    @Test
    fun `an empty successful read is treated as not-here-yet, not as a loss`() {
        val material = materialize(
            listOf(candidate(1L, "image/jpeg", 0)),
            reads = mapOf(1L to ok(0)),
        )

        assertTrue(material.parts.isEmpty())
        assertTrue(material.omissions.isEmpty())
    }

    @Test
    fun `a video becomes an omission and is never opened`() {
        val opened = mutableListOf<Long>()
        val material = materialize(
            listOf(candidate(9L, "video/mp4", 4_000_000)),
            opened = opened,
        )

        assertTrue(material.parts.isEmpty())
        // The size comes from the descriptor, so 용량이 커서 is a true claim
        // here rather than a guess about the cause.
        assertEquals(
            listOf(IncomingOmissionNotice.Omission(IncomingOmissionNotice.Kind.VIDEO, 4_000_000)),
            material.omissions,
        )
        assertEquals(
            "[동영상 1개는 용량이 커서 받지 못했습니다]",
            IncomingOmissionNotice.appendTo("", material.omissions),
        )
        // Reading a 4 MB clip to learn that it is a 4 MB clip is the one cost
        // this path must never pay.
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a clip that fits travels verbatim instead of being announced as lost`() {
        // The previous build relayed any part under the wire cap and the web
        // rendered it as a download link. Refusing one for its type alone
        // dropped 40 KB voice parts that had eight times the room they needed,
        // and told the owner they were lost while the phone still held them.
        val material = materialize(
            listOf(candidate(4L, "audio/amr", 40_000)),
            reads = mapOf(4L to ok(40_000)),
        )

        assertEquals(1, material.parts.size)
        assertEquals("audio/amr", material.parts[0].contentType)
        assertEquals(40_000, material.parts[0].bytes.size)
        assertTrue(material.omissions.isEmpty())
    }

    @Test
    fun `a clip whose size the provider will not declare is carried when it fits`() {
        // declaredSize -1 reserves nothing, so the decision has to fall to what
        // is actually left rather than to an allowance of zero.
        val material = materialize(
            listOf(candidate(5L, "application/pdf", -1)),
            reads = mapOf(5L to ok(90_000)),
        )

        assertEquals(1, material.parts.size)
        assertEquals(90_000, material.parts[0].bytes.size)
        assertTrue(material.omissions.isEmpty())
    }

    @Test
    fun `a gif inside the wire cap is carried rather than refused by a self-imposed reserve`() {
        // 400 KiB fits RelayContentCodec.MAX_ATTACHMENT_BYTES with room to
        // spare. A 384 KiB reserve refused it and reported 용량이 커서, which
        // was only true of our own budget.
        val bytes = 400 * 1024
        val material = materialize(
            listOf(candidate(6L, "image/gif", bytes)),
            reads = mapOf(6L to ok(bytes)),
        )

        assertEquals(1, material.parts.size)
        assertEquals("image/gif", material.parts[0].contentType)
        assertEquals(bytes, material.parts[0].bytes.size)
        assertTrue(material.omissions.isEmpty())
    }

    @Test
    fun `the relayed total never exceeds the codec attachment cap`() {
        // A shrinker that ignores its allowance is exactly the failure the cap
        // has to survive: an over-cap payload is refused by RelayContentCodec
        // and would take the whole message down with it.
        val material = materialize(
            (1L..3L).map { candidate(it, "image/jpeg", 5_000_000) },
            shrink = { c, _, _ -> ProviderMmsPart(c.name, "image/jpeg", ByteArray(300_000)) },
            reads = (1L..3L).associateWith { ok(600_000) },
        )

        assertTrue(material.parts.sumOf { it.bytes.size } <= RelayContentCodec.MAX_ATTACHMENT_BYTES)
        assertEquals(1, material.parts.size)
        // The two that did not fit are announced rather than dropped in silence.
        assertEquals(2, material.omissions.size)
    }

    @Test
    fun `an oversized photo is shrunk into the relay instead of vanishing`() {
        val material = materialize(
            listOf(candidate(1L, "image/jpeg", 7_150_000)),
            reads = mapOf(1L to ok(7_150_000)),
            shrink = { c, _, budget -> ProviderMmsPart(c.name, "image/jpeg", ByteArray(budget - 1)) },
        )

        assertEquals(1, material.parts.size)
        assertTrue(material.parts[0].bytes.size < ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET)
        assertTrue(material.omissions.isEmpty())
    }

    @Test
    fun `a photo the encoder cannot fit is announced with its measured size`() {
        val material = materialize(
            listOf(candidate(1L, "image/jpeg", 7_150_000)),
            reads = mapOf(1L to ok(7_150_000)),
            shrink = { _, _, _ -> null },
        )

        assertTrue(material.parts.isEmpty())
        assertEquals(
            listOf(IncomingOmissionNotice.Omission(IncomingOmissionNotice.Kind.IMAGE, 7_150_000)),
            material.omissions,
        )
        assertEquals(
            "사진 봐\n[사진 1장은 용량이 커서 받지 못했습니다]",
            IncomingOmissionNotice.appendTo("사진 봐", material.omissions),
        )
    }

    @Test
    fun `a part too large to hold is re-encoded from what could be read`() {
        val prefix = ByteArray(2048) { 0x11 }
        val material = materialize(
            listOf(candidate(1L, "image/jpeg", -1)),
            reads = mapOf(1L to ImageShrinkPolicy.PartRead.TooLarge),
            truncated = mapOf(1L to prefix),
            // The marker byte proves the prefix went through the encoder. It
            // must: a truncated file relayed byte for byte opens as a broken
            // image, which lies harder than saying the photo did not arrive.
            shrink = { c, bytes, _ -> ProviderMmsPart(c.name, "image/jpeg", bytes + 0x99.toByte()) },
        )

        assertArrayEquals(prefix + 0x99.toByte(), material.parts.single().bytes)
    }

    @Test
    fun `a part too large to hold with nothing readable is announced without a size`() {
        val material = materialize(
            listOf(candidate(1L, "image/jpeg", -1)),
            reads = mapOf(1L to ImageShrinkPolicy.PartRead.TooLarge),
        )

        assertTrue(material.parts.isEmpty())
        // The declared size was -1, so the prefix read is the only measurement
        // available and it measures the prefix, not the part. No claim is made.
        assertEquals(
            listOf(IncomingOmissionNotice.Omission(IncomingOmissionNotice.Kind.IMAGE)),
            material.omissions,
        )
    }

    @Test
    fun `a GIF travels byte-identical or not at all`() {
        val animation = ByteArray(120_000) { 0x47 }
        val fits = materialize(
            listOf(candidate(1L, "image/gif", animation.size)),
            reads = mapOf(1L to ImageShrinkPolicy.PartRead.Ok(animation)),
            shrink = { _, _, _ -> error("a GIF must never be re-encoded") },
        )
        // Re-encoding one keeps frame one and drops the animation in silence,
        // which is why it is a pass-through part.
        assertArrayEquals(animation, fits.parts.single().bytes)
        assertEquals("image/gif", fits.parts.single().contentType)

        val tooBig = materialize(
            listOf(candidate(2L, "image/gif", 5_000_000)),
            reads = mapOf(2L to ok(5_000_000)),
            shrink = { _, _, _ -> error("a GIF must never be re-encoded") },
        )
        assertTrue(tooBig.parts.isEmpty())
        assertEquals(1, tooBig.omissions.size)
    }

    @Test
    fun `a ninth attachment is announced rather than silently refused by the codec`() {
        val candidates = (1L..9L).map { candidate(it, "image/jpeg", 1_000) }
        val material = materialize(
            candidates,
            reads = (1L..9L).associateWith { ok(1_000) },
        )

        assertEquals(RelayContentCodec.MAX_ATTACHMENTS, material.parts.size)
        assertEquals(1, material.omissions.size)
    }

    private fun ok(size: Int) = ImageShrinkPolicy.PartRead.Ok(ByteArray(size) { 0x5A })

    private fun candidate(partId: Long, contentType: String, declaredSize: Int) =
        ProviderMmsCandidate(partId, "part-$partId", contentType, declaredSize)

    private fun mms(
        parts: List<ProviderMmsPart> = emptyList(),
        relayCandidates: List<ProviderMmsCandidate> = emptyList(),
    ) = ProviderMms(
        id = 51L,
        address = "+821012345678",
        subject = null,
        body = "",
        date = 1L,
        parts = parts,
        relayCandidates = relayCandidates,
    )

    /** [MmsProvider.materialize] with every framework call stubbed out. */
    private fun materialize(
        candidates: List<ProviderMmsCandidate>,
        budget: Int = ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET,
        reads: Map<Long, ImageShrinkPolicy.PartRead> = emptyMap(),
        truncated: Map<Long, ByteArray> = emptyMap(),
        opened: MutableList<Long> = mutableListOf(),
        shrink: (ProviderMmsCandidate, ByteArray, Int) -> ProviderMmsPart? = { c, bytes, budgetFor ->
            ProviderMmsPart(c.name, "image/jpeg", ByteArray(minOf(budgetFor, bytes.size)))
        },
    ) = MmsProvider.materialize(
        candidates,
        budget,
        read = {
            opened += it.partId
            reads[it.partId] ?: ImageShrinkPolicy.PartRead.Failed
        },
        readTruncated = { truncated[it.partId] ?: ByteArray(0) },
        shrink = shrink,
    )
}
