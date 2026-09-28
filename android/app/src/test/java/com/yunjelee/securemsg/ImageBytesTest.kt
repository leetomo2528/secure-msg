package com.yunjelee.securemsg

import com.yunjelee.securemsg.ImageBytes.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte gate in front of every ImageDecoder call (SM-8): nothing is decoded
 * unless its bytes carry an accepted signature AND that signature is the
 * format the part declared.
 */
class ImageBytesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun padded(head: ByteArray, size: Int = 64) = head + ByteArray(maxOf(0, size - head.size))

    private fun le32(value: Int) = bytes(value and 0xFF, (value ushr 8) and 0xFF, (value ushr 16) and 0xFF, (value ushr 24) and 0xFF)

    private fun be32(value: Int) = bytes((value ushr 24) and 0xFF, (value ushr 16) and 0xFF, (value ushr 8) and 0xFF, value and 0xFF)

    private val jpeg = padded(bytes(0xFF, 0xD8, 0xFF, 0xE0))
    private val png = padded(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    private val gif87 = padded(ascii("GIF87a"))
    private val gif89 = padded(ascii("GIF89a"))
    private val webp = padded(ascii("RIFF") + le32(56) + ascii("WEBPVP8 "))
    private val webpLossless = padded(ascii("RIFF") + le32(56) + ascii("WEBPVP8L"))
    private val bmp = padded(ascii("BM") + ByteArray(12) + le32(40))

    /** An ISO-BMFF ftyp box: size, "ftyp", major brand, minor version, compatible brands. */
    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val size = 16 + 4 * compatible.size
        var box = be32(size) + ascii("ftyp") + ascii(major) + ByteArray(4)
        compatible.forEach { box += ascii(it) }
        return padded(box)
    }

    // ---- accepted signatures -------------------------------------------

    @Test
    fun `every accepted signature sniffs as its own format`() {
        assertEquals(Format.JPEG, ImageBytes.sniff(jpeg))
        assertEquals(Format.PNG, ImageBytes.sniff(png))
        assertEquals(Format.GIF, ImageBytes.sniff(gif87))
        assertEquals(Format.GIF, ImageBytes.sniff(gif89))
        assertEquals(Format.WEBP, ImageBytes.sniff(webp))
        assertEquals(Format.WEBP, ImageBytes.sniff(webpLossless))
        assertEquals(Format.WEBP, ImageBytes.sniff(padded(ascii("RIFF") + le32(56) + ascii("WEBPVP8X"))))
        assertEquals(Format.BMP, ImageBytes.sniff(bmp))
        for (brand in listOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")) {
            assertEquals(brand, Format.HEIF, ImageBytes.sniff(ftyp(brand, "mif1", "heic")))
        }
    }

    @Test
    fun `each accepted signature matches its declared type, with aliases and parameters`() {
        assertTrue(ImageBytes.matchesDeclared(jpeg, "image/jpeg"))
        assertTrue(ImageBytes.matchesDeclared(jpeg, "image/jpg"))
        assertTrue(ImageBytes.matchesDeclared(jpeg, " IMAGE/JPEG; name=photo.jpg"))
        assertTrue(ImageBytes.matchesDeclared(png, "image/png"))
        assertTrue(ImageBytes.matchesDeclared(gif89, "image/gif"))
        assertTrue(ImageBytes.matchesDeclared(webp, "image/webp"))
        assertTrue(ImageBytes.matchesDeclared(bmp, "image/bmp"))
        assertTrue(ImageBytes.matchesDeclared(ftyp("heic"), "image/heic"))
        assertTrue(ImageBytes.matchesDeclared(ftyp("mif1", "heic"), "image/heif"))
    }

    // ---- rejected formats -------------------------------------------------

    @Test
    fun `formats outside the allowlist are unknown and never match a declared jpeg`() {
        val tiffLe = padded(bytes(0x49, 0x49, 0x2A, 0x00))
        val tiffBe = padded(bytes(0x4D, 0x4D, 0x00, 0x2A))
        // A DNG is a TIFF container: same header, same answer.
        val ico = padded(bytes(0x00, 0x00, 0x01, 0x00, 0x01, 0x00))
        val wbmp = padded(bytes(0x00, 0x00, 0x10, 0x10))
        val avif = ftyp("avif", "mif1", "miaf")
        val avifBehindMif1 = ftyp("mif1", "avif", "miaf")
        val heicListingAvif = ftyp("heic", "mif1", "avif")
        val avis = ftyp("avis", "msf1")
        val svg = padded(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\">"))
        for ((label, forged) in listOf(
            "tiff-le" to tiffLe,
            "tiff-be" to tiffBe,
            "ico" to ico,
            "wbmp" to wbmp,
            "avif" to avif,
            "avif behind mif1" to avifBehindMif1,
            "heic listing avif" to heicListingAvif,
            "avis" to avis,
            "svg" to svg,
        )) {
            assertNull(label, ImageBytes.sniff(forged))
            for (declared in listOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/bmp")) {
                assertFalse("$label as $declared", ImageBytes.matchesDeclared(forged, declared))
            }
        }
    }

    @Test
    fun `real bytes under the wrong declared type are refused`() {
        assertFalse(ImageBytes.matchesDeclared(png, "image/jpeg"))
        assertFalse(ImageBytes.matchesDeclared(jpeg, "image/png"))
        assertFalse(ImageBytes.matchesDeclared(gif89, "image/webp"))
        assertFalse(ImageBytes.matchesDeclared(webp, "image/gif"))
        assertFalse(ImageBytes.matchesDeclared(ftyp("heic"), "image/jpeg"))
        assertFalse(ImageBytes.matchesDeclared(jpeg, "image/heic"))
        // A declared type this app never decodes cannot be matched by anything.
        assertFalse(ImageBytes.matchesDeclared(jpeg, "image/tiff"))
        assertFalse(ImageBytes.matchesDeclared(jpeg, "application/octet-stream"))
        assertFalse(ImageBytes.matchesDeclared(jpeg, ""))
        assertFalse(ImageBytes.matchesDeclared(jpeg, null))
    }

    @Test
    fun `empty and truncated headers are refused`() {
        // Each format with the shortest header it can be identified from: any
        // cut below that is refused, however right its first bytes look.
        val shortest = listOf(
            Triple("jpeg", jpeg, 3),
            Triple("png", png, 8),
            Triple("gif", gif89, 6),
            Triple("webp", webp, 16),
            Triple("bmp", bmp, 18),
            Triple("heic", ftyp("heic"), 16),
        )
        for (size in listOf(0, 1, 3, 11)) {
            for ((label, full, minimum) in shortest) {
                if (size >= minimum) continue
                assertNull("$label cut to $size", ImageBytes.sniff(full.copyOf(size)))
            }
        }
        for ((label, full, minimum) in shortest) {
            assertNull("$label one byte short", ImageBytes.sniff(full.copyOf(minimum - 1)))
        }
        assertNull(ImageBytes.sniff(ByteArray(0)))
        assertNull(ImageBytes.sniff(bytes(0xFF, 0xD8)))
        // A bare RIFF/WEBP header with no chunk after it is not a WebP to decode.
        assertNull(ImageBytes.sniff(ascii("RIFF") + le32(4) + ascii("WEBP")))
        // A RIFF that says WEBP but whose first chunk is no WebP bitstream.
        assertNull(ImageBytes.sniff(padded(ascii("RIFF") + le32(56) + ascii("WEBPJUNK"))))
        // "BM" with a DIB header size no BMP defines.
        assertNull(ImageBytes.sniff(padded(ascii("BM") + ByteArray(12) + le32(7))))
        // An ftyp box claiming more bytes than exist, or a 64-bit largesize.
        assertNull(ImageBytes.sniff(be32(40) + ascii("ftypheic") + ByteArray(4)))
        assertNull(ImageBytes.sniff(padded(be32(1) + ascii("ftypheic") + ByteArray(4))))
        assertFalse(ImageBytes.matchesDeclared(ByteArray(0), "image/jpeg"))
        assertFalse(ImageBytes.matchesDeclared(bytes(0xFF, 0xD8), "image/jpeg"))
    }

    // ---- the gate is in front of the framework ---------------------------

    @Test
    fun `the shrinker refuses mismatched bytes before touching a decoder`() {
        // android.graphics is a throwing stub in this suite, and so is the
        // android.util.Log the shrinker's catch writes to: reaching
        // ImageDecoder.createSource at all makes this test error out. Getting a
        // plain null back proves the byte gate ran first.
        val tiff = padded(bytes(0x49, 0x49, 0x2A, 0x00), 4_096)
        val ico = padded(bytes(0x00, 0x00, 0x01, 0x00, 0x01, 0x00), 4_096)
        assertNull(ImageShrinker.shrink(tiff, "image/jpeg", 100_000))
        assertNull(ImageShrinker.shrink(ico, "image/jpeg", 100_000))
        assertNull(ImageShrinker.shrink(ftyp("avif", "mif1"), "image/heic", 100_000))
        assertNull(ImageShrinker.shrink(png, "image/jpeg", 100_000))
        assertNull(ImageShrinker.shrink(jpeg, "image/webp", 100_000))
    }

    // ---- SM-9: animated WebP is never flattened ---------------------------

    @Test
    fun `a VP8X with the animation flag is animated`() {
        val flagOnly = ImageFixtures.webp(
            ImageFixtures.vp8x(ImageFixtures.VP8X_ANIMATION),
            ImageFixtures.webpChunk("VP8 ", ByteArray(32)),
        )
        assertTrue(ImageBytes.isAnimatedWebp(flagOnly))
        assertTrue(ImageBytes.isAnimatedWebp(ImageFixtures.animatedWebp()))
    }

    @Test
    fun `an ANIM or ANMF chunk marks it animated even with the flag clear`() {
        for (tag in listOf("ANIM", "ANMF")) {
            val chunk = ImageFixtures.webp(
                ImageFixtures.vp8x(ImageFixtures.VP8X_ALPHA),
                ImageFixtures.webpChunk(tag, ByteArray(6)),
                ImageFixtures.webpChunk("VP8 ", ByteArray(32)),
            )
            assertTrue(tag, ImageBytes.isAnimatedWebp(chunk))
        }
    }

    @Test
    fun `simple and still extended WebPs are static`() {
        assertFalse(ImageBytes.isAnimatedWebp(ImageFixtures.stillWebp()))
        assertFalse(
            ImageBytes.isAnimatedWebp(ImageFixtures.webp(ImageFixtures.webpChunk("VP8L", ByteArray(31)))),
        )
        // VP8X, flag clear, alpha + image + trailing EXIF (odd length, padded).
        val extendedStill = ImageFixtures.webp(
            ImageFixtures.vp8x(ImageFixtures.VP8X_ALPHA or ImageFixtures.VP8X_EXIF),
            ImageFixtures.webpChunk("ALPH", ByteArray(9)),
            ImageFixtures.webpChunk("VP8 ", ByteArray(40)),
            ImageFixtures.webpChunk("EXIF", ByteArray(13)),
        )
        assertFalse(ImageBytes.isAnimatedWebp(extendedStill))
        // Trailing bytes past the RIFF size are not chunks.
        assertFalse(ImageBytes.isAnimatedWebp(extendedStill + ascii("ANIM")))
        // A simple-format WebP cannot carry animation, even as a truncated prefix.
        assertFalse(ImageBytes.isAnimatedWebp(ImageFixtures.stillWebp().copyOf(64)))
    }

    @Test
    fun `truncated or malformed WebP is treated as animated`() {
        val extendedStill = ImageFixtures.webp(
            ImageFixtures.vp8x(ImageFixtures.VP8X_ALPHA),
            ImageFixtures.webpChunk("VP8 ", ByteArray(400)),
        )
        assertFalse(ImageBytes.isAnimatedWebp(extendedStill))
        // Cut anywhere past the header: the walk cannot prove the rest is still.
        for (cut in listOf(12, 16, 19, 20, 25, 29, 30, 38, 100, extendedStill.size - 1)) {
            assertTrue("cut to $cut", ImageBytes.isAnimatedWebp(extendedStill.copyOf(cut)))
        }
        // A VP8X that declares fewer than its 10 payload bytes.
        val shortVp8x = ImageFixtures.webp(ImageFixtures.webpChunk("VP8X", ByteArray(4)))
        assertTrue(ImageBytes.isAnimatedWebp(padded(shortVp8x, 64)))
        // A chunk claiming more bytes than the container holds.
        val overlong = ImageFixtures.webp(
            ImageFixtures.vp8x(0),
            ImageFixtures.ascii("VP8 ") + ImageFixtures.le32(10_000) + ByteArray(8),
        )
        assertTrue(ImageBytes.isAnimatedWebp(overlong))
        // An unknown first chunk.
        assertTrue(ImageBytes.isAnimatedWebp(ImageFixtures.webp(ImageFixtures.webpChunk("JUNK", ByteArray(16)))))
        // A RIFF size (0, 4, or anything short of the VP8X chunk) that would
        // otherwise skip the chunk walk and hide the ANIM/ANMF chunks after it.
        val hiddenAnimation = ImageFixtures.webp(
            ImageFixtures.vp8x(0),
            ImageFixtures.webpChunk("ANIM", ByteArray(6)),
            ImageFixtures.webpChunk("ANMF", ByteArray(64)),
        )
        for (riffSize in listOf(0, 4, 21)) {
            val lying = hiddenAnimation.copyOf()
            ImageFixtures.le32(riffSize).copyInto(lying, destinationOffset = 4)
            assertTrue("RIFF size $riffSize", ImageBytes.isAnimatedWebp(lying))
        }
    }

    @Test
    fun `bytes that are not a WebP container are not this detector's question`() {
        assertFalse(ImageBytes.isAnimatedWebp(ByteArray(0)))
        assertFalse(ImageBytes.isAnimatedWebp(jpeg))
        assertFalse(ImageBytes.isAnimatedWebp(gif89))
        assertFalse(ImageBytes.isAnimatedWebp(ascii("RIFF") + le32(4) + ascii("AVI ")))
    }

    @Test
    fun `the shrinker refuses an animated WebP before touching a decoder`() {
        // Same stub argument as the byte gate above: a null with no error is
        // proof the animation never reached ImageDecoder.
        assertNull(ImageShrinker.shrink(ImageFixtures.animatedWebp(), "image/webp", 100_000))
    }

    // ---- second defence: the decoder's own verdict and the pixel cap ------

    @Test
    fun `the decoder's chosen codec must be the declared format`() {
        assertTrue(ImageBytes.decoderMimeMatches("image/jpeg", "image/jpeg"))
        assertTrue(ImageBytes.decoderMimeMatches("image/jpg", "image/jpeg"))
        assertTrue(ImageBytes.decoderMimeMatches("image/heic", "image/heif"))
        assertTrue(ImageBytes.decoderMimeMatches("image/webp; name=a.webp", "image/webp"))
        // What ImageDecoder reports for the codecs this gate exists to keep out.
        assertFalse(ImageBytes.decoderMimeMatches("image/jpeg", "image/x-adobe-dng"))
        assertFalse(ImageBytes.decoderMimeMatches("image/jpeg", "image/x-ico"))
        assertFalse(ImageBytes.decoderMimeMatches("image/jpeg", "image/vnd.wap.wbmp"))
        assertFalse(ImageBytes.decoderMimeMatches("image/heic", "image/avif"))
        assertFalse(ImageBytes.decoderMimeMatches("image/jpeg", "image/png"))
        assertFalse(ImageBytes.decoderMimeMatches("image/jpeg", null))
        assertFalse(ImageBytes.decoderMimeMatches("image/jpeg", ""))
    }

    @Test
    fun `the pixel cap sits at one hundred megapixels`() {
        assertEquals(100_000_000L, ImageBytes.MAX_DECODE_PIXELS)
        assertTrue(ImageBytes.withinPixelCap(10_000, 10_000))
        assertTrue(ImageBytes.withinPixelCap(4_000, 3_000))
        assertTrue(ImageBytes.withinPixelCap(1, 1))
        assertFalse(ImageBytes.withinPixelCap(10_000, 10_001))
        assertFalse(ImageBytes.withinPixelCap(100_000_001, 1))
        // Int overflow must not turn a forged header into a small number.
        assertFalse(ImageBytes.withinPixelCap(65_536, 65_536))
        assertFalse(ImageBytes.withinPixelCap(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(ImageBytes.withinPixelCap(0, 100))
        assertFalse(ImageBytes.withinPixelCap(-1, 100))
    }
}
