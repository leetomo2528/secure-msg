package com.yunjelee.securemsg

import com.yunjelee.securemsg.ImageFixtures.APP0_JFIF
import com.yunjelee.securemsg.ImageFixtures.APP2_ICC
import com.yunjelee.securemsg.ImageFixtures.COMMENT_MARKER
import com.yunjelee.securemsg.ImageFixtures.DHT
import com.yunjelee.securemsg.ImageFixtures.DQT
import com.yunjelee.securemsg.ImageFixtures.EOI
import com.yunjelee.securemsg.ImageFixtures.GPS_MARKER
import com.yunjelee.securemsg.ImageFixtures.IEND
import com.yunjelee.securemsg.ImageFixtures.IHDR
import com.yunjelee.securemsg.ImageFixtures.PHYS
import com.yunjelee.securemsg.ImageFixtures.PNG_SIGNATURE
import com.yunjelee.securemsg.ImageFixtures.SOF0
import com.yunjelee.securemsg.ImageFixtures.SOI
import com.yunjelee.securemsg.ImageFixtures.SOS
import com.yunjelee.securemsg.ImageFixtures.XMP_APP1
import com.yunjelee.securemsg.ImageFixtures.XMP_MARKER
import com.yunjelee.securemsg.ImageFixtures.ascii
import com.yunjelee.securemsg.ImageFixtures.be32
import com.yunjelee.securemsg.ImageFixtures.bytesOf
import com.yunjelee.securemsg.ImageFixtures.com
import com.yunjelee.securemsg.ImageFixtures.exifApp1
import com.yunjelee.securemsg.ImageFixtures.idat
import com.yunjelee.securemsg.ImageFixtures.pngChunk
import com.yunjelee.securemsg.ImageFixtures.scan
import com.yunjelee.securemsg.ImageMetadataStripper.Orientation
import com.yunjelee.securemsg.ImageMetadataStripper.PassThrough
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin port of frontend/src/store/imageMetadata.ts (SM-4): what is
 * removed, what is kept, and every framing doubt that must return the
 * original bytes untouched.
 */
class ImageMetadataStripperTest {
    private fun contains(haystack: ByteArray, needle: String): Boolean =
        String(haystack, Charsets.ISO_8859_1).contains(needle)

    private fun assertNoMetadata(bytes: ByteArray) {
        assertFalse("GPS survived", contains(bytes, GPS_MARKER))
        assertFalse("XMP survived", contains(bytes, XMP_MARKER))
        assertFalse("comment survived", contains(bytes, COMMENT_MARKER))
        assertFalse("EXIF header survived", contains(bytes, "Exif"))
    }

    private fun strip(bytes: ByteArray, mime: String = "image/jpeg") = ImageMetadataStripper.strip(bytes, mime)

    // ---- JPEG: removed and retained -----------------------------------------

    @Test
    fun `a JPEG loses every APP1 and COM and keeps everything else in order`() {
        val source = ImageFixtures.jpegWithMetadata(scanSize = 200)
        assertTrue(contains(source, GPS_MARKER))
        val out = strip(source)
        assertArrayEquals(ImageFixtures.jpegStripped(scanSize = 200), out)
        assertNoMetadata(out)
        // APP0 (JFIF) and APP2 (ICC) are kept, in place.
        assertTrue(contains(out, "JFIF"))
        assertTrue(contains(out, "ICC_PROFILE"))
    }

    @Test
    fun `aliases and the octet-stream sniff strip the same way`() {
        val source = ImageFixtures.jpegWithMetadata()
        val expected = ImageFixtures.jpegStripped()
        assertArrayEquals(expected, strip(source, "image/jpg"))
        assertArrayEquals(expected, strip(source, "IMAGE/JPEG; name=a.jpg"))
        assertArrayEquals(expected, strip(source, ""))
        assertArrayEquals(expected, strip(source, "application/octet-stream"))
        // A type that is neither, or bytes that are not what the type says.
        assertArrayEquals(source, strip(source, "image/webp"))
        assertArrayEquals(source, strip(source, "image/png"))
    }

    @Test
    fun `fill bytes before a marker are tolerated and travel with a kept segment`() {
        val filled = SOI + bytesOf(0xFF, 0xFF) + APP0_JFIF + bytesOf(0xFF) + exifApp1() +
            bytesOf(0xFF, 0xFF, 0xFF) + DQT + SOF0 + DHT + SOS + scan() + EOI
        val expected = SOI + bytesOf(0xFF, 0xFF) + APP0_JFIF + bytesOf(0xFF, 0xFF, 0xFF) + DQT + SOF0 + DHT +
            SOS + scan() + EOI
        assertArrayEquals(expected, strip(filled))
    }

    @Test
    fun `a JPEG with nothing to remove comes back as the same array`() {
        val clean = ImageFixtures.jpegClean()
        assertSame(clean, strip(clean))
    }

    // ---- JPEG: uncertain framing is the original ------------------------------

    @Test
    fun `uncertain JPEG framing returns the original bytes`() {
        val head = SOI + APP0_JFIF + exifApp1()
        val cases = mapOf(
            "no SOS" to head + DQT + SOF0 + DHT,
            "standalone RST before SOS" to head + bytesOf(0xFF, 0xD0) + DQT + SOS + scan() + EOI,
            "TEM before SOS" to head + bytesOf(0xFF, 0x01) + SOS + scan() + EOI,
            "second SOI" to head + SOI + SOS + scan() + EOI,
            "segment longer than the file" to head + bytesOf(0xFF, 0xDB, 0x40, 0x00, 1, 2, 3),
            "length below 2" to head + bytesOf(0xFF, 0xDB, 0x00, 0x01) + SOS + scan() + EOI,
            "truncated SOS header" to head + bytesOf(0xFF, 0xDA, 0x00),
            "SOS longer than the file" to head + bytesOf(0xFF, 0xDA, 0x00, 0x40, 1),
            "marker cut after fill bytes" to head + bytesOf(0xFF, 0xFF),
            "garbage between segments" to head + bytesOf(0x00) + SOS + scan() + EOI,
            "not a JPEG" to bytesOf(0xFF, 0xD8, 0x00) + exifApp1() + SOS + scan(),
            "too short" to bytesOf(0xFF, 0xD8),
            "empty" to ByteArray(0),
        )
        for ((label, bytes) in cases) {
            assertArrayEquals(label, bytes, strip(bytes))
        }
    }

    // ---- PNG ---------------------------------------------------------------

    @Test
    fun `a PNG loses its text, time and EXIF chunks and keeps every other chunk in order`() {
        val source = ImageFixtures.pngWithMetadata()
        val out = strip(source, "image/png")
        assertArrayEquals(ImageFixtures.pngStripped(), out)
        assertNoMetadata(out)
        assertFalse(contains(out, "tIME"))
        assertFalse(contains(out, "zTXt"))
        assertArrayEquals(ImageFixtures.pngStripped(), strip(source, "application/octet-stream"))
        assertArrayEquals(source, strip(source, "image/jpeg"))
    }

    @Test
    fun `bytes after IEND are dropped, as the web does`() {
        val source = ImageFixtures.pngWithMetadata() + ascii("trailing")
        assertArrayEquals(ImageFixtures.pngStripped(), strip(source, "image/png"))
    }

    @Test
    fun `a PNG with nothing to remove comes back as the same array`() {
        val clean = ImageFixtures.pngStripped()
        assertSame(clean, strip(clean, "image/png"))
    }

    @Test
    fun `uncertain PNG framing returns the original bytes`() {
        val text = pngChunk("tEXt", ascii("Comment") + bytesOf(0) + ascii(COMMENT_MARKER))
        val cases = mapOf(
            "no IEND" to PNG_SIGNATURE + IHDR + text + idat(),
            "IHDR not first" to PNG_SIGNATURE + text + IHDR + idat() + IEND,
            "IHDR of the wrong length" to PNG_SIGNATURE + pngChunk("IHDR", ByteArray(12)) + text + idat() + IEND,
            "second IHDR" to PNG_SIGNATURE + IHDR + text + IHDR + idat() + IEND,
            "IEND before IDAT" to PNG_SIGNATURE + IHDR + text + IEND,
            "IEND with data" to PNG_SIGNATURE + IHDR + text + idat() + pngChunk("IEND", ByteArray(1)),
            "chunk past the end" to PNG_SIGNATURE + IHDR + text + be32(4_000) + ascii("IDAT") + ByteArray(8),
            "non-letter chunk type" to PNG_SIGNATURE + IHDR + text + pngChunk("ID1T", ByteArray(4)) + idat() + IEND,
            "length over 2^31-1" to PNG_SIGNATURE + IHDR + text + bytesOf(0x80, 0, 0, 0) + ascii("IDAT") + ByteArray(8),
            "cut mid chunk header" to PNG_SIGNATURE + IHDR + text + bytesOf(0, 0, 0),
            "bad signature" to bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x00) + IHDR + text + idat() + IEND,
        )
        for ((label, bytes) in cases) {
            assertArrayEquals(label, bytes, strip(bytes, "image/png"))
        }
    }

    // ---- EXIF orientation ---------------------------------------------------

    @Test
    fun `orientation is read from IFD0 in either byte order`() {
        for (little in listOf(true, false)) {
            for (value in 2..8) {
                assertEquals(
                    "little=$little value=$value",
                    Orientation.ROTATED,
                    ImageMetadataStripper.jpegOrientation(ImageFixtures.jpegWithMetadata(value, little)),
                )
            }
            assertEquals(Orientation.NORMAL, ImageMetadataStripper.jpegOrientation(ImageFixtures.jpegWithMetadata(1, little)))
            assertEquals(Orientation.NORMAL, ImageMetadataStripper.jpegOrientation(ImageFixtures.jpegWithMetadata(null, little)))
        }
    }

    @Test
    fun `no EXIF, XMP only, and tags the decoder ignores are upright`() {
        assertEquals(Orientation.NORMAL, ImageMetadataStripper.jpegOrientation(ImageFixtures.jpegClean()))
        val xmpOnly = SOI + APP0_JFIF + XMP_APP1 + DQT + SOS + scan() + EOI
        assertEquals(Orientation.NORMAL, ImageMetadataStripper.jpegOrientation(xmpOnly))
        for (bogus in listOf(0, 9, 255)) {
            val jpeg = SOI + exifApp1(bogus) + SOS + scan() + EOI
            assertEquals("value $bogus", Orientation.NORMAL, ImageMetadataStripper.jpegOrientation(jpeg))
        }
        // Orientation stored as a LONG is not the SHORT the decoder reads.
        val asLong = SOI + exifApp1(6, orientationType = 4) + SOS + scan() + EOI
        assertEquals(Orientation.NORMAL, ImageMetadataStripper.jpegOrientation(asLong))
    }

    @Test
    fun `an EXIF block that cannot be walked is unknown`() {
        fun exifWith(tiff: ByteArray) = ImageFixtures.segment(0xE1, ascii("Exif") + bytesOf(0, 0) + tiff)
        val cases = mapOf(
            "bad byte order" to exifWith(ascii("IM") + bytesOf(42, 0) + ImageFixtures.le32(8) + ByteArray(6)),
            "bad magic" to exifWith(ascii("II") + bytesOf(43, 0) + ImageFixtures.le32(8) + ByteArray(6)),
            "IFD past the block" to exifWith(ascii("II") + bytesOf(42, 0) + ImageFixtures.le32(4_000) + ByteArray(6)),
            "IFD inside the header" to exifWith(ascii("II") + bytesOf(42, 0) + ImageFixtures.le32(2) + ByteArray(6)),
            "entries past the block" to exifWith(ascii("II") + bytesOf(42, 0) + ImageFixtures.le32(8) + bytesOf(9, 0) + ByteArray(12)),
            "header cut short" to exifWith(ascii("II") + bytesOf(42)),
        )
        for ((label, app1) in cases) {
            val jpeg = SOI + APP0_JFIF + app1 + SOS + scan() + EOI
            assertEquals(label, Orientation.UNKNOWN, ImageMetadataStripper.jpegOrientation(jpeg))
        }
        // Framing that is not established at all.
        assertEquals(Orientation.UNKNOWN, ImageMetadataStripper.jpegOrientation(SOI + exifApp1(6)))
    }

    // ---- the pass-through decision ------------------------------------------

    @Test
    fun `a rotated JPEG goes to the re-encoder instead of losing its rotation`() {
        for (value in listOf(3, 6, 8)) {
            assertSame(
                PassThrough.Reencode,
                ImageMetadataStripper.forPassThrough(ImageFixtures.jpegWithMetadata(value), "image/jpeg"),
            )
        }
    }

    @Test
    fun `an upright JPEG and a PNG are sent stripped`() {
        val jpeg = ImageMetadataStripper.forPassThrough(ImageFixtures.jpegWithMetadata(1), "image/jpeg")
        assertArrayEquals(ImageFixtures.jpegStripped(), (jpeg as PassThrough.Send).bytes)
        val png = ImageMetadataStripper.forPassThrough(ImageFixtures.pngWithMetadata(), "image/png")
        assertArrayEquals(ImageFixtures.pngStripped(), (png as PassThrough.Send).bytes)
    }

    @Test
    fun `an unreadable EXIF keeps the original bytes whole`() {
        val app1 = ImageFixtures.segment(0xE1, ascii("Exif") + bytesOf(0, 0) + ascii("IM") + ByteArray(10))
        val jpeg = SOI + APP0_JFIF + app1 + com() + SOS + scan() + EOI
        val pass = ImageMetadataStripper.forPassThrough(jpeg, "image/jpeg") as PassThrough.Send
        assertArrayEquals(jpeg, pass.bytes)
    }

    @Test
    fun `other types are sent exactly as they are`() {
        val sticker = ImageFixtures.animatedWebp()
        val pass = ImageMetadataStripper.forPassThrough(sticker, "image/webp") as PassThrough.Send
        assertSame(sticker, pass.bytes)
        val gif = ascii("GIF89a") + ByteArray(32)
        assertSame(gif, (ImageMetadataStripper.forPassThrough(gif, "image/gif") as PassThrough.Send).bytes)
    }

    @Test
    fun `only jpeg and png payloads are ever decoded to strip`() {
        for (type in listOf("image/jpeg", "image/jpg", "IMAGE/PNG; x=y")) assertTrue(type, ImageMetadataStripper.handles(type))
        for (type in listOf("image/gif", "image/webp", "image/heic", "application/pdf", "", null)) {
            assertFalse("$type", ImageMetadataStripper.handles(type))
        }
    }
}
