package com.yunjelee.securemsg

import java.util.Locale

/**
 * What an attachment's bytes actually are, read off the bytes themselves.
 *
 * The gate in front of every ImageDecoder call in this app. A declared MIME
 * type is a string the sender chose, and ImageDecoder.createSource over a
 * ByteBuffer ignores it: the platform picks its codec by sniffing, so a part
 * declared `image/jpeg` whose bytes are a DNG, a TIFF, an ICO, a WBMP or an
 * AVIF reaches the raw/tiff/ico/avif parser anyway -- inside the default SMS
 * app's process, which holds the E2E keys, and on the incoming path without
 * the user touching anything ([MmsProvider.materialize] shrinks an over-cap
 * part the moment it lands). So nothing is decoded unless the bytes carry one
 * of the six signatures this app is prepared to decode AND that signature is
 * the format the part says it is.
 *
 * Free of every android.* type so the host suite pins it; the decoders call it
 * and hold no byte rules of their own.
 */
object ImageBytes {
    /** The formats the decode paths accept. Everything else is "unknown" and never decoded. */
    enum class Format { JPEG, PNG, GIF, WEBP, BMP, HEIF }

    /**
     * Second-defence ceiling on a source's pixel count, checked in the decoder's
     * header callback before a single pixel is allocated.
     *
     * 100 MP is above every phone camera in circulation (the largest ship
     * 200 MP sensors but write 12-50 MP files by default) and far below what a
     * forged header can claim. The decoders subsample to a target size, so the
     * risk is not the final bitmap but the minutes of decode work and the
     * codec's own scratch buffers a 60 000 x 60 000 header asks for.
     */
    const val MAX_DECODE_PIXELS: Long = 100_000_000L

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * ISO-BMFF brands accepted as HEIF/HEIC.
     *
     * The HEVC-coded image and sequence brands plus the two generic MIAF
     * structural brands (`mif1`, `msf1`), because a Samsung or iPhone HEIC
     * often carries `mif1` as its major brand with `heic` among the compatible
     * ones. The decision is made on the MAJOR brand, and any file that also
     * lists `avif`/`avis` anywhere (major or compatible) is refused outright:
     * the platform's HEIF sniffer walks the brand list in order and an AV1
     * payload behind a `mif1` major brand is exactly the AVIF-declared-as-HEIC
     * case this gate exists for.
     */
    private val HEIF_MAJOR_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")
    private val AVIF_BRANDS = setOf("avif", "avis")

    /** An ftyp box larger than this is not a real HEIC header; refusing it bounds the brand walk. */
    private const val MAX_FTYP_BOX = 4096

    /** BITMAPCOREHEADER, BITMAPINFOHEADER and its V2..V5 successors -- the DIB headers a BMP can carry. */
    private val BMP_DIB_HEADER_SIZES = setOf(12, 40, 52, 56, 64, 108, 124)

    /**
     * The format [bytes] begin with, or null when they carry none of the
     * accepted signatures. Empty and truncated input is null: a header too
     * short to identify is a header this app does not decode.
     */
    fun sniff(bytes: ByteArray): Format? = when {
        isJpeg(bytes) -> Format.JPEG
        startsWith(bytes, PNG_SIGNATURE) -> Format.PNG
        isGif(bytes) -> Format.GIF
        isWebp(bytes) -> Format.WEBP
        isBmp(bytes) -> Format.BMP
        isHeif(bytes) -> Format.HEIF
        else -> null
    }

    /**
     * The format a MIME type names, or null when it names none this app
     * decodes. Parameters and case never matter; `image/jpg` is the JPEG it
     * is emitted as, and `image/heic` and `image/heif` are one container.
     */
    fun formatOf(mime: String?): Format? = when (mediaType(mime)) {
        "image/jpeg", "image/jpg" -> Format.JPEG
        "image/png" -> Format.PNG
        "image/gif" -> Format.GIF
        "image/webp" -> Format.WEBP
        "image/bmp" -> Format.BMP
        "image/heic", "image/heif" -> Format.HEIF
        else -> null
    }

    /**
     * The gate: true only when [bytes] sniff as a known format AND that format
     * is the one [declaredMime] names. A mismatch is not "probably fine" -- it
     * is precisely a part whose bytes would select a codec its type never
     * admitted to.
     */
    fun matchesDeclared(bytes: ByteArray, declaredMime: String?): Boolean {
        val declared = formatOf(declaredMime) ?: return false
        return sniff(bytes) == declared
    }

    /**
     * The decoder's own verdict, checked from inside its header callback: the
     * MIME ImageDecoder reports for the codec it chose must name the format the
     * part was declared as. It cannot disagree with a sniff that passed unless
     * the sniff is wrong, and this is what catches that.
     */
    fun decoderMimeMatches(declaredMime: String?, decoderMime: String?): Boolean {
        val declared = formatOf(declaredMime) ?: return false
        return formatOf(decoderMime) == declared
    }

    /** Whether a header claiming [width] x [height] may be decoded at all. */
    fun withinPixelCap(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return width.toLong() * height.toLong() <= MAX_DECODE_PIXELS
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF

    private fun isGif(bytes: ByteArray): Boolean =
        startsWith(bytes, "GIF87a".toByteArray(Charsets.US_ASCII)) ||
            startsWith(bytes, "GIF89a".toByteArray(Charsets.US_ASCII))

    /**
     * `RIFF` + size + `WEBP`, followed by a complete chunk header naming one of
     * the three WebP bitstream layouts. The chunk is required so that a bare
     * twelve-byte RIFF header -- or a RIFF of some other form that happens to
     * say WEBP -- is not waved through to libwebp.
     */
    internal fun isWebp(bytes: ByteArray): Boolean {
        if (!isRiffWebp(bytes) || bytes.size < 16) return false
        return fourcc(bytes, 12) in setOf("VP8 ", "VP8L", "VP8X")
    }

    /** The twelve-byte RIFF/WEBP container header alone. */
    internal fun isRiffWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 && fourcc(bytes, 0) == "RIFF" && fourcc(bytes, 8) == "WEBP"

    /**
     * `BM` plus a DIB header size the format defines. Two letters alone would
     * accept any file that happens to start with them, and the header-size
     * field is the first thing every BMP decoder trusts.
     */
    private fun isBmp(bytes: ByteArray): Boolean {
        if (bytes.size < 18 || u8(bytes, 0) != 'B'.code || u8(bytes, 1) != 'M'.code) return false
        val dib = le32(bytes, 14)
        return dib <= Int.MAX_VALUE && dib.toInt() in BMP_DIB_HEADER_SIZES
    }

    /** ISO-BMFF `ftyp` at offset 4 with an accepted major brand and no AVIF brand anywhere. */
    private fun isHeif(bytes: ByteArray): Boolean {
        if (bytes.size < 16 || fourcc(bytes, 4) != "ftyp") return false
        val boxSize = be32(bytes, 0)
        // size 0 (to end of file) and 1 (64-bit largesize) are legal ISO-BMFF
        // and are never what a camera writes for ftyp; refusing them keeps the
        // brand walk bounded by a length this code has actually checked.
        if (boxSize < 16 || boxSize > MAX_FTYP_BOX || boxSize > bytes.size || (boxSize - 16) % 4 != 0L) return false
        val major = fourcc(bytes, 8)
        val brands = mutableListOf(major)
        var at = 16
        while (at + 4 <= boxSize) {
            brands += fourcc(bytes, at)
            at += 4
        }
        if (brands.any { it in AVIF_BRANDS }) return false
        return major in HEIF_MAJOR_BRANDS
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
        return true
    }

    internal fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

    internal fun fourcc(bytes: ByteArray, at: Int): String {
        if (at < 0 || at + 4 > bytes.size) return ""
        val chars = CharArray(4) { u8(bytes, at + it).toChar() }
        return String(chars)
    }

    /** Little-endian unsigned 32-bit, as a Long so a 4 GiB claim cannot wrap negative. */
    internal fun le32(bytes: ByteArray, at: Int): Long =
        (u8(bytes, at).toLong()) or
            (u8(bytes, at + 1).toLong() shl 8) or
            (u8(bytes, at + 2).toLong() shl 16) or
            (u8(bytes, at + 3).toLong() shl 24)

    internal fun be32(bytes: ByteArray, at: Int): Long =
        (u8(bytes, at).toLong() shl 24) or
            (u8(bytes, at + 1).toLong() shl 16) or
            (u8(bytes, at + 2).toLong() shl 8) or
            u8(bytes, at + 3).toLong()

    private fun mediaType(value: String?): String =
        value.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
}
