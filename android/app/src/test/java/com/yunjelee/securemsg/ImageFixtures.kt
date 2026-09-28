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
}
