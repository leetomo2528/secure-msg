package com.yunjelee.securemsg

import java.util.Locale

/**
 * Lossless removal of location, comment and timestamp metadata from a JPEG or
 * PNG that is about to travel as-is (SM-4).
 *
 * A port of `frontend/src/store/imageMetadata.ts`, framing rule for framing
 * rule, so a photo leaves this phone carrying exactly what the same photo
 * would carry leaving the web composer:
 *
 *  - JPEG: every APP1 segment (EXIF and XMP -- GPS, capture time, device
 *    model) and every COM segment is dropped. APP0 (JFIF), APP2 (ICC colour
 *    profile, MPF) and every other segment before SOS are kept in order, and
 *    SOS plus everything after it is copied verbatim. Fill bytes (0xFF runs)
 *    before a marker are allowed. A standalone marker (RSTn, TEM, a second
 *    SOI) or a reserved one outside a scan, a truncated or undersized
 *    segment length, and a file with no SOS all mean the framing is not
 *    established -- the ORIGINAL bytes are returned.
 *  - PNG: the eXIf, tEXt, iTXt, zTXt and tIME chunks are dropped; every other
 *    chunk is copied whole with its CRC, in order. The first chunk must be a
 *    13-byte IHDR, IEND must be empty and come after an IDAT, every chunk
 *    must fit, and the walk must reach IEND -- otherwise the ORIGINAL bytes.
 *    Bytes after IEND are discarded, as the web does.
 *
 * "Uncertain means original" is the rule that keeps this lossless: a guess
 * about framing could cut image data out of a photo the owner can no longer
 * re-send, and leaking metadata the web path would also have failed to parse
 * is the smaller loss.
 *
 * When nothing was removed the SAME array instance comes back (as the web's
 * copyRanges returns its input), so a caller can tell "unchanged" by
 * reference. Tests assert content, not identity, where they assert stripping.
 *
 * Free of every android.* type so the host suite pins it.
 */
object ImageMetadataStripper {
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val PNG_METADATA = setOf("eXIf", "tEXt", "iTXt", "zTXt", "tIME")

    private const val SOS = 0xDA
    private const val APP1 = 0xE1
    private const val COM = 0xFE
    private const val ORIENTATION_TAG = 0x0112
    private const val TIFF_SHORT = 3

    /** What a pass-through part should do before it travels. */
    sealed interface PassThrough {
        /** Send these bytes: stripped, or the original when stripping was not safe or changed nothing. */
        class Send(val bytes: ByteArray) : PassThrough

        /**
         * A JPEG whose EXIF Orientation is not 1. Stripping APP1 would delete
         * the tag and the photo would arrive sideways, so it has to go through
         * the re-encoder instead: ImageDecoder applies the rotation and
         * Bitmap.compress writes no EXIF at all. If that re-encode is not
         * available the caller sends the original, as every build before this
         * one did.
         */
        object Reencode : PassThrough
    }

    /** What EXIF says about a JPEG's rotation. */
    enum class Orientation {
        /** No EXIF, no Orientation tag, or Orientation 1: stripping changes nothing visible. */
        NORMAL,

        /** Orientation 2..8: the pixels must be transformed before the tag can go. */
        ROTATED,

        /** An EXIF block (or the file's framing) that could not be read. Strip nothing. */
        UNKNOWN,
    }

    /** The only declared types [MmsSender] decodes a payload to strip. */
    fun handles(mime: String?): Boolean = mediaType(mime) in setOf("image/jpeg", "image/jpg", "image/png")

    /**
     * [bytes] with metadata removed, or [bytes] itself. The type decides which
     * format is tried; an empty or `application/octet-stream` type is sniffed,
     * exactly as the web does.
     */
    fun strip(bytes: ByteArray, mime: String?): ByteArray {
        val type = mediaType(mime)
        if (type == "image/jpeg" || type == "image/jpg") return stripJpeg(bytes)
        if (type == "image/png") return stripPng(bytes)
        if (type == "" || type == "application/octet-stream") {
            if (looksLikeJpeg(bytes)) return stripJpeg(bytes)
            if (hasPngSignature(bytes)) return stripPng(bytes)
        }
        return bytes
    }

    /**
     * The pass-through decision: strip, keep, or send through the re-encoder.
     *
     * A JPEG is checked for Orientation first. [Orientation.ROTATED] is
     * [PassThrough.Reencode]; [Orientation.UNKNOWN] keeps the original bytes
     * whole (metadata included) rather than risk a sideways photo; NORMAL is
     * stripped. The result is never larger than [bytes] -- stripping only
     * removes, and that is re-checked here rather than trusted, because the
     * callers compare this size against budgets the original already fit.
     */
    fun forPassThrough(bytes: ByteArray, mime: String?): PassThrough {
        val type = mediaType(mime)
        val jpeg = type == "image/jpeg" || type == "image/jpg" ||
            ((type == "" || type == "application/octet-stream") && looksLikeJpeg(bytes))
        if (jpeg) {
            when (jpegOrientation(bytes)) {
                Orientation.ROTATED -> return PassThrough.Reencode
                Orientation.UNKNOWN -> return PassThrough.Send(bytes)
                Orientation.NORMAL -> Unit
            }
        }
        val stripped = strip(bytes, mime)
        return PassThrough.Send(if (stripped.size <= bytes.size) stripped else bytes)
    }

    /**
     * The EXIF Orientation of a JPEG.
     *
     * Every APP1 that begins `Exif\0\0` is read as a TIFF block (II or MM,
     * magic 42, IFD0 entry 0x0112 of type SHORT, count 1) -- the same entry
     * the platform decoder rotates by. Any one of them saying 2..8 is ROTATED;
     * otherwise any one that cannot be parsed is UNKNOWN; otherwise NORMAL. A
     * tag of another type or count, or a value outside 1..8, is ignored the
     * way the decoder ignores it. XMP's tiff:Orientation is not consulted: the
     * decoder does not consult it either. A file whose segment framing is not
     * established is UNKNOWN, which [strip] would leave untouched anyway.
     */
    fun jpegOrientation(bytes: ByteArray): Orientation {
        val framing = jpegFraming(bytes) ?: return Orientation.UNKNOWN
        var unknown = false
        for (segment in framing.segments) {
            if (segment.marker != APP1) continue
            val base = segment.payloadStart + 6
            if (base > segment.end || !isExifHeader(bytes, segment.payloadStart, segment.end)) continue
            when (val value = tiffOrientation(bytes, base, segment.end)) {
                null -> unknown = true
                in 2..8 -> return Orientation.ROTATED
                // 1: upright, which is what stripping leaves behind.
                else -> Unit
            }
        }
        return if (unknown) Orientation.UNKNOWN else Orientation.NORMAL
    }

    // ---- JPEG -------------------------------------------------------------

    /** One length-framed segment before SOS: its marker, its [start, end) span including fill bytes, and its payload start. */
    private class Segment(val marker: Int, val start: Int, val end: Int, val payloadStart: Int)

    /** Every segment before SOS, and where the SOS marker (with its fill bytes) begins. */
    private class JpegFraming(val segments: List<Segment>, val sosStart: Int)

    /** The walk shared by [stripJpeg] and [jpegOrientation]; null whenever the web's stripJpeg would return its input. */
    private fun jpegFraming(bytes: ByteArray): JpegFraming? {
        if (!looksLikeJpeg(bytes)) return null
        val segments = mutableListOf<Segment>()
        var at = 2
        while (at < bytes.size) {
            val start = at
            if (u8(bytes, at) != 0xFF) return null
            // A marker may be preceded by any number of FF fill bytes.
            while (at < bytes.size && u8(bytes, at) == 0xFF) at += 1
            if (at == bytes.size) return null
            val marker = u8(bytes, at)
            if (marker == SOS) {
                // A truncated SOS header is not safe to rewrite either.
                if (at + 2 >= bytes.size) return null
                val length = be16(bytes, at + 1)
                if (length < 2 || at + 1 + length > bytes.size) return null
                return JpegFraming(segments, start)
            }
            // Byte stuffing and standalone markers have no length outside a
            // scan. Reserved extension markers are also left untouched: their
            // framing is not established here, so guessing could cut out image
            // data.
            val hasLength = (marker in 0xC0..0xCF && marker != 0xC8) ||
                marker in 0xDB..0xDF ||
                marker in 0xE0..0xEF ||
                marker == COM
            if (!hasLength) return null
            if (at + 2 >= bytes.size) return null
            val length = be16(bytes, at + 1)
            val end = at + 1 + length
            if (length < 2 || end > bytes.size) return null
            segments += Segment(marker, start, end, payloadStart = at + 3)
            at = end
        }
        // Without SOS, even otherwise valid-looking segments might be incomplete.
        return null
    }

    private fun stripJpeg(bytes: ByteArray): ByteArray {
        val framing = jpegFraming(bytes) ?: return bytes
        val keep = mutableListOf(0 until 2)
        framing.segments.forEach {
            if (it.marker != APP1 && it.marker != COM) keep += it.start until it.end
        }
        keep += framing.sosStart until bytes.size
        return copyRanges(bytes, keep)
    }

    private fun looksLikeJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF

    private fun isExifHeader(bytes: ByteArray, from: Int, end: Int): Boolean {
        if (from + 6 > end) return false
        return u8(bytes, from) == 'E'.code && u8(bytes, from + 1) == 'x'.code &&
            u8(bytes, from + 2) == 'i'.code && u8(bytes, from + 3) == 'f'.code &&
            u8(bytes, from + 4) == 0 && u8(bytes, from + 5) == 0
    }

    /**
     * IFD0's Orientation value from the TIFF block at [base] (bounded by
     * [end]): 1 when the tag is absent or not the SHORT/count-1 entry the
     * decoder reads or its value is outside 1..8, the value when it is, and
     * null when the block itself cannot be walked.
     */
    private fun tiffOrientation(bytes: ByteArray, base: Int, end: Int): Int? {
        if (base + 8 > end) return null
        val little = when {
            u8(bytes, base) == 'I'.code && u8(bytes, base + 1) == 'I'.code -> true
            u8(bytes, base) == 'M'.code && u8(bytes, base + 1) == 'M'.code -> false
            else -> return null
        }
        if (u16(bytes, base + 2, little) != 42) return null
        val ifdOffset = u32(bytes, base + 4, little)
        if (ifdOffset < 8 || base + ifdOffset + 2 > end) return null
        val ifd = base + ifdOffset.toInt()
        val count = u16(bytes, ifd, little)
        if (ifd + 2L + count * 12L > end) return null
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (u16(bytes, entry, little) != ORIENTATION_TAG) continue
            val type = u16(bytes, entry + 2, little)
            val values = u32(bytes, entry + 4, little)
            if (type != TIFF_SHORT || values != 1L) return 1
            val value = u16(bytes, entry + 8, little)
            return if (value in 1..8) value else 1
        }
        return 1
    }

    // ---- PNG --------------------------------------------------------------

    private fun hasPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return false
        return true
    }

    /** Copy whole chunks, including CRCs; trailing bytes after IEND are discarded. */
    private fun stripPng(bytes: ByteArray): ByteArray {
        if (!hasPngSignature(bytes)) return bytes
        val keep = mutableListOf(0 until PNG_SIGNATURE.size)
        var at = PNG_SIGNATURE.size
        var first = true
        var sawIdat = false
        while (at < bytes.size) {
            if (at + 12 > bytes.size) return bytes
            val length = be32(bytes, at)
            if (length > 0x7FFFFFFFL) return bytes
            val end = at + 12L + length
            if (end > bytes.size) return bytes
            for (i in at + 4 until at + 8) {
                val code = u8(bytes, i)
                if (!(code in 65..90 || code in 97..122)) return bytes
            }
            val name = String(CharArray(4) { u8(bytes, at + 4 + it).toChar() })
            if (first && (name != "IHDR" || length != 13L)) return bytes
            first = false
            if (name == "IHDR" && at != PNG_SIGNATURE.size) return bytes
            if (name == "IDAT") sawIdat = true
            if (name == "IEND" && (length != 0L || !sawIdat)) return bytes
            if (name !in PNG_METADATA) keep += at until end.toInt()
            at = end.toInt()
            if (name == "IEND") return copyRanges(bytes, keep)
        }
        // A missing IEND may mean the parser stopped before later metadata.
        return bytes
    }

    // ---- shared -----------------------------------------------------------

    private fun copyRanges(bytes: ByteArray, keep: List<IntRange>): ByteArray {
        val total = keep.sumOf { it.last - it.first + 1 }
        if (total == bytes.size) return bytes
        val out = ByteArray(total)
        var at = 0
        for (range in keep) {
            val length = range.last - range.first + 1
            if (length <= 0) continue
            System.arraycopy(bytes, range.first, out, at, length)
            at += length
        }
        return out
    }

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

    private fun be16(bytes: ByteArray, at: Int): Int = (u8(bytes, at) shl 8) or u8(bytes, at + 1)

    private fun be32(bytes: ByteArray, at: Int): Long =
        (u8(bytes, at).toLong() shl 24) or (u8(bytes, at + 1).toLong() shl 16) or
            (u8(bytes, at + 2).toLong() shl 8) or u8(bytes, at + 3).toLong()

    private fun u16(bytes: ByteArray, at: Int, little: Boolean): Int =
        if (little) u8(bytes, at) or (u8(bytes, at + 1) shl 8) else be16(bytes, at)

    private fun u32(bytes: ByteArray, at: Int, little: Boolean): Long =
        if (little) {
            u8(bytes, at).toLong() or (u8(bytes, at + 1).toLong() shl 8) or
                (u8(bytes, at + 2).toLong() shl 16) or (u8(bytes, at + 3).toLong() shl 24)
        } else {
            be32(bytes, at)
        }

    private fun mediaType(value: String?): String =
        value.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
}
