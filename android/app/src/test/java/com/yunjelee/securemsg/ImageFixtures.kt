package com.yunjelee.securemsg

/**
 * Byte-level image fixtures built in the test, never checked in as binaries:
 * a committed .webp or .jpg is a blob nobody can diff, and every assertion
 * that uses these is about framing, not pixels.
 */
object ImageFixtures {
    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    // ---- WebP -------------------------------------------------------------

    /** One RIFF chunk: fourcc, little-endian size, payload, pad byte when odd. */
    fun webpChunk(tag: String, payload: ByteArray): ByteArray {
        val pad = if (payload.size % 2 == 1) ByteArray(1) else ByteArray(0)
        return ascii(tag) + le32(payload.size) + payload + pad
    }

    /** RIFF/WEBP container around [chunks], with a truthful RIFF size. */
    fun webp(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        return ascii("RIFF") + le32(4 + body.size) + ascii("WEBP") + body
    }

    /** VP8X payload: flags byte, three reserved, 24-bit canvas width-1 and height-1. */
    fun vp8x(flags: Int, width: Int = 64, height: Int = 64): ByteArray {
        val w = width - 1
        val h = height - 1
        return webpChunk(
            "VP8X",
            byteArrayOf(
                flags.toByte(), 0, 0, 0,
                w.toByte(), (w ushr 8).toByte(), (w ushr 16).toByte(),
                h.toByte(), (h ushr 8).toByte(), (h ushr 16).toByte(),
            ),
        )
    }

    const val VP8X_ANIMATION = 0x02
    const val VP8X_ALPHA = 0x10
    const val VP8X_EXIF = 0x08

    /**
     * An animated WebP of exactly [size] bytes (even, at least 60): VP8X with
     * the animation flag, an ANIM chunk, and one ANMF frame padded out.
     */
    fun animatedWebp(size: Int = 4_096, fill: Byte = 0x41): ByteArray {
        val head = webp(vp8x(VP8X_ANIMATION or VP8X_ALPHA), webpChunk("ANIM", ByteArray(6)))
        val frame = size - head.size - 8
        require(frame >= 0 && frame % 2 == 0) { "size $size cannot be framed" }
        return webp(
            vp8x(VP8X_ANIMATION or VP8X_ALPHA),
            webpChunk("ANIM", ByteArray(6)),
            webpChunk("ANMF", ByteArray(frame) { fill }),
        )
    }

    /** A simple-format (lossy `VP8 `) still WebP of exactly [size] bytes (even, at least 20). */
    fun stillWebp(size: Int = 4_096, fill: Byte = 0x42): ByteArray {
        val payload = size - 20
        require(payload >= 0 && payload % 2 == 0) { "size $size cannot be framed" }
        return webp(webpChunk("VP8 ", ByteArray(payload) { fill }))
    }

    // ---- JPEG -------------------------------------------------------------

    fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    fun be16(value: Int): ByteArray = bytesOf((value ushr 8) and 0xFF, value and 0xFF)

    fun be32(value: Int): ByteArray =
        bytesOf((value ushr 24) and 0xFF, (value ushr 16) and 0xFF, (value ushr 8) and 0xFF, value and 0xFF)

    /** One length-framed JPEG segment: FF, marker, big-endian length (payload + 2), payload. */
    fun segment(marker: Int, payload: ByteArray): ByteArray =
        bytesOf(0xFF, marker) + be16(payload.size + 2) + payload

    val SOI: ByteArray = bytesOf(0xFF, 0xD8)
    val EOI: ByteArray = bytesOf(0xFF, 0xD9)

    val APP0_JFIF: ByteArray = segment(0xE0, ascii("JFIF") + bytesOf(0, 1, 2, 0, 0, 1, 0, 1, 0, 0))
    val APP2_ICC: ByteArray = segment(0xE2, ascii("ICC_PROFILE") + bytesOf(0, 1, 1) + ByteArray(32) { 0x2A })
    val DQT: ByteArray = segment(0xDB, bytesOf(0) + ByteArray(64) { (it + 1).toByte() })
    val SOF0: ByteArray = segment(0xC0, bytesOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0))
    val DHT: ByteArray = segment(0xC4, bytesOf(0) + ByteArray(16) { if (it == 0) 1 else 0 } + bytesOf(0))
    val SOS: ByteArray = segment(0xDA, bytesOf(1, 1, 0, 0, 0x3F, 0))

    /** The GPS-ish payload a camera leaves in EXIF; tests look for it by name. */
    const val GPS_MARKER = "GPS 37.2636N 127.0286E"
    const val XMP_MARKER = "xmp:GPSLatitude"
    const val COMMENT_MARKER = "shot on a phone"

    /**
     * An APP1 EXIF segment: TIFF header, IFD0 with Make, (Orientation), and a
     * GPS IFD pointer, then the GPS-ish bytes the pointer would address.
     */
    fun exifApp1(orientation: Int? = null, little: Boolean = true, orientationType: Int = 3): ByteArray {
        fun u16(v: Int) = if (little) bytesOf(v and 0xFF, (v ushr 8) and 0xFF) else be16(v)
        fun u32(v: Int) = if (little) le32(v) else be32(v)
        val entries = mutableListOf<ByteArray>()
        entries += u16(0x010F) + u16(2) + u32(4) + ascii("Sam") + bytesOf(0)
        if (orientation != null) {
            val value = if (orientationType == 3) u16(orientation) + bytesOf(0, 0) else u32(orientation)
            entries += u16(0x0112) + u16(orientationType) + u32(1) + value
        }
        val ifdSize = 2 + entries.size * 12 + 4
        entries += u16(0x8825) + u16(4) + u32(1) + u32(8 + ifdSize + 12)
        val count = entries.size
        val ifd = entries.fold(u16(count)) { acc, e -> acc + e } + u32(0)
        val tiff = (if (little) ascii("II") + u16(42) else ascii("MM") + u16(42)) + u32(8) + ifd + ascii(GPS_MARKER)
        return segment(0xE1, ascii("Exif") + bytesOf(0, 0) + tiff)
    }

    val XMP_APP1: ByteArray = segment(
        0xE1,
        ascii("http://ns.adobe.com/xap/1.0/") + bytesOf(0) +
            ascii("<x:xmpmeta><$XMP_MARKER>37,15.8N</$XMP_MARKER></x:xmpmeta>"),
    )

    fun com(size: Int = 0): ByteArray =
        segment(0xFE, ascii(COMMENT_MARKER) + ByteArray(size) { 0x43 })

    /**
     * Entropy-coded data with byte stuffing (FF 00) and a restart marker, and
     * bytes after EOI that look like an APP1: everything from SOS on is kept
     * verbatim, so none of it may be touched.
     */
    fun scan(size: Int = 0): ByteArray =
        bytesOf(0x12, 0x34, 0xFF, 0x00, 0x56, 0xFF, 0xD0, 0x78, 0x9A) + ByteArray(size) { 0x5C }

    val TRAILER: ByteArray = bytesOf(0xFF, 0xE1, 0x00, 0x04, 0xAB, 0xCD)

    /** A camera-shaped JPEG carrying EXIF (with GPS), XMP and a comment. */
    fun jpegWithMetadata(
        orientation: Int? = null,
        little: Boolean = true,
        scanSize: Int = 0,
        commentSize: Int = 0,
    ): ByteArray =
        SOI + APP0_JFIF + exifApp1(orientation, little) + XMP_APP1 + com(commentSize) + APP2_ICC +
            DQT + SOF0 + DHT + SOS + scan(scanSize) + EOI + TRAILER

    /** [jpegWithMetadata] as the stripper must leave it: APP1s and COM gone, everything else in order. */
    fun jpegStripped(scanSize: Int = 0): ByteArray =
        SOI + APP0_JFIF + APP2_ICC + DQT + SOF0 + DHT + SOS + scan(scanSize) + EOI + TRAILER

    /** A JPEG with nothing to strip. */
    fun jpegClean(scanSize: Int = 0): ByteArray = jpegStripped(scanSize)

    // ---- PNG --------------------------------------------------------------

    val PNG_SIGNATURE: ByteArray = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** One PNG chunk with a real CRC-32 over type + data. */
    fun pngChunk(type: String, data: ByteArray): ByteArray {
        val crc = java.util.zip.CRC32()
        crc.update(ascii(type))
        crc.update(data)
        return be32(data.size) + ascii(type) + data + be32(crc.value.toInt())
    }

    val IHDR: ByteArray = pngChunk("IHDR", be32(16) + be32(16) + bytesOf(8, 6, 0, 0, 0))
    val PHYS: ByteArray = pngChunk("pHYs", be32(2835) + be32(2835) + bytesOf(1))
    val IEND: ByteArray = pngChunk("IEND", ByteArray(0))

    fun idat(size: Int = 32): ByteArray = pngChunk("IDAT", ByteArray(size) { (it * 3).toByte() })

    fun pngWithMetadata(idatSize: Int = 32): ByteArray =
        PNG_SIGNATURE + IHDR + pngChunk("tEXt", ascii("Comment") + bytesOf(0) + ascii(COMMENT_MARKER)) +
            PHYS + pngChunk("iTXt", ascii("XML:com.adobe.xmp") + bytesOf(0, 0, 0, 0, 0) + ascii(XMP_MARKER)) +
            pngChunk("zTXt", ascii("Author") + bytesOf(0, 0) + ByteArray(8)) +
            pngChunk("eXIf", ascii("MM") + be16(42) + be32(8) + ascii(GPS_MARKER)) +
            pngChunk("tIME", bytesOf(0x07, 0xEA, 9, 28, 7, 42, 0)) +
            idat(idatSize) + IEND

    fun pngStripped(idatSize: Int = 32): ByteArray = PNG_SIGNATURE + IHDR + PHYS + idat(idatSize) + IEND
}
