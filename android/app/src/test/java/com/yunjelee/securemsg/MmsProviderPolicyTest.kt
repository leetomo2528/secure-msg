package com.yunjelee.securemsg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
