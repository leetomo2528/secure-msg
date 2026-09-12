package com.yunjelee.securemsg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Re-encodes one received image until it fits a byte budget.
 *
 * The framework half of the incoming shrink path: every android.graphics call
 * is here and every decision -- which rungs exist, how large to draw one, which
 * rung to jump to after a measurement -- is in [ImageShrinkPolicy], which is
 * why the policy runs in the host suite and this does not. The dependency runs
 * one way only. Nothing below re-derives a size the policy already computes; a
 * second copy of that arithmetic would let the host tests stay green while the
 * phone produced a different file from the same photo.
 *
 * What this produces never reaches the incoming idempotency key. `mid` and the
 * relay's source fingerprint are derived from the parts [MmsProvider.read]
 * already returned, unshrunk, exactly as it returned them today; the shrunk
 * copy only ever rides RelayOutbox.plaintext and messages.attachmentsJson. The
 * day that stops being true, every row already in processed_mms re-keys on the
 * first launch after an update and the phone relays the owner a duplicate of
 * every conversation they already have -- unattended, because the APK
 * self-installs.
 */
object ImageShrinker {
    private const val TAG = "ImageShrinker"

    /**
     * Always JPEG, unlike the web composer, which also tries PNG and keeps it
     * when it wins.
     *
     * That comparison costs a second full encode of every image to pay off only
     * on flat graphics, and this path runs inside a foreground service holding
     * a broadcast open while the carrier's own MMS download is in flight. An
     * incoming part is a camera photo almost every time, which is the case PNG
     * always loses.
     */
    private const val OUTPUT_CONTENT_TYPE = "image/jpeg"

    /**
     * A rung's encode, and the bitmap it came from.
     *
     * [width] and [height] are read back off the bitmap that was actually
     * compressed rather than taken from the rung: a rung names a long-edge cap,
     * the source may sit below it, and the decoder applies the source's EXIF
     * rotation before either is met.
     */
    data class Shrunk(
        val bytes: ByteArray,
        val contentType: String,
        val width: Int,
        val height: Int,
    )

    /**
     * [bytes] re-encoded to at most [budget] bytes, or null when it cannot be.
     *
     * Null is a normal answer, not an error: the caller turns it into an
     * [IncomingOmissionNotice] line so the user is told a photo did not make it,
     * which is the entire point of the exercise -- today an oversized part is
     * dropped in silence and the message arrives looking complete.
     *
     * Never throws, and the Throwable catch is deliberate rather than defensive
     * noise: this is called from inside [MmsProvider]'s blanket try, where an
     * escaping exception does not lose one attachment, it returns null for the
     * whole message and the text goes with it. OutOfMemoryError is the
     * realistic escape -- a multi-megapixel decode is the largest single
     * allocation this app ever makes -- and an Error is exactly what a
     * `catch (e: Exception)` upstream would let past.
     *
     * A source already inside [budget] is still re-encoded; passing one through
     * untouched is the caller's decision, because only the caller knows whether
     * the original bytes are still the ones it wants to relay.
     */
    fun shrink(bytes: ByteArray, contentType: String, budget: Int): Shrunk? {
        // A budget of zero is ImageShrinkPolicy.allocate saying this part has no
        // room at all, not an invitation to encode something tiny.
        if (bytes.isEmpty() || budget <= 0) return null
        // The type decides, never the bytes. An animated GIF decodes perfectly
        // well here and would come back as its first frame with the animation
        // gone -- a silent loss dressed up as a success, which is worse than the
        // omission notice the policy's pass-through rule earns it.
        if (!ImageShrinkPolicy.isShrinkable(contentType)) return null
        return try {
            walkLadder(bytes, budget)
        } catch (t: Throwable) {
            // No part name, no dimensions, no bytes: this runs over the default
            // SMS app's own inbox and logcat is readable by the user's other
            // tooling.
            Log.w(TAG, "shrink failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** One decode, held across every rung that shares its edge. */
    private class Decoded(val bitmap: Bitmap, val sourceLongEdge: Int)

    /**
     * Walk down the ladder until an encode measures inside [budget].
     *
     * The top rung is a probe: what it actually weighed tells
     * [ImageShrinkPolicy.predictRung] how expensive this particular image is,
     * and the walk resumes from the rung predicted to fit instead of stepping
     * down one at a time. Each skipped rung is a full decode plus encode of a
     * multi-megapixel bitmap, so the prediction is the difference between a
     * photo costing two passes and eight.
     */
    private fun walkLadder(bytes: ByteArray, budget: Int): Shrunk? {
        // The probe is decoded before the ladder is known, because the source
        // dimensions the ladder clamps to are only readable from the header
        // pass. targetSize clamps the same way rungsFor does, so the bitmap this
        // produces is the one rungs[0] describes.
        var decoded = decodeAt(bytes, ImageShrinkPolicy.SHRINK_RUNGS.first().edge) ?: return null
        try {
            val rungs = ImageShrinkPolicy.rungsFor(decoded.sourceLongEdge)
            val probe = compress(decoded.bitmap, rungs.first().quality) ?: return null
            if (probe.size <= budget) return shrunkOf(probe, decoded.bitmap)
            var index = ImageShrinkPolicy.predictRung(
                probePixels = decoded.bitmap.width * decoded.bitmap.height,
                probeQuality = rungs.first().quality,
                probeBytes = probe.size,
                budget = budget,
                rungs = rungs,
            )
            while (index < rungs.size) {
                val rung = rungs[index]
                if (rung.edge != longEdgeOf(decoded.bitmap)) {
                    // Free before allocating, never after: the decode below is
                    // the allocation that runs the heap out, and holding the
                    // larger bitmap across it is what makes that happen. A
                    // second recycle in the finally is a no-op, so an abandoned
                    // reference here costs nothing.
                    decoded.bitmap.recycle()
                    decoded = decodeAt(bytes, rung.edge) ?: return null
                }
                val encoded = compress(decoded.bitmap, rung.quality)
                if (encoded != null && encoded.size <= budget) return shrunkOf(encoded, decoded.bitmap)
                index += 1
            }
            // The floor rung still overshoots. Better an announced omission than
            // an attachment the receiving decoder drops without telling anyone.
            return null
        } finally {
            decoded.bitmap.recycle()
        }
    }

    /**
     * Decode [bytes] scaled so its long edge is at most [edge].
     *
     * The source is re-wrapped on every call rather than sharing one
     * ImageDecoder.Source: a Source over a ByteBuffer reads through the buffer's
     * own position, and re-using one across the ladder's decodes makes the
     * second decode depend on where the first left it.
     */
    private fun decodeAt(bytes: ByteArray, edge: Int): Decoded? {
        var sourceLongEdge = 0
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = info.size
            sourceLongEdge = maxOf(size.width, size.height)
            val target = ImageShrinkPolicy.targetSize(size.width, size.height, edge)
            // setTargetSize, not setTargetSampleSize: the sample size only
            // reaches a power of two of the source and the rung has to land on
            // an exact edge, or this device and the web composer produce
            // different files from the same photo. The decoder still subsamples
            // its way down internally, so the full-size bitmap is never
            // allocated.
            decoder.setTargetSize(target.width, target.height)
            // The default allocator can hand back a hardware bitmap, whose
            // pixels live on the GPU where Canvas cannot read them -- and
            // reading them is exactly what the alpha flatten below does.
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setMutableRequired(false)
            // False aborts the decode when the input is incomplete, which is
            // what the caller wants: a part the carrier has not finished
            // downloading must surface as a failure and be retried or announced,
            // not relayed as the top third of a photo with grey below it.
            decoder.setOnPartialImageListener { false }
        }
        // JPEG has no alpha and Skia encodes a premultiplied transparent pixel
        // as black, so a PNG sticker would arrive as a black rectangle. Flatten
        // onto white -- what every viewer renders transparency as -- before any
        // rung compresses it, once per decode rather than once per rung.
        val opaque = if (bitmap.hasAlpha()) {
            try {
                flattenOntoWhite(bitmap)
            } finally {
                bitmap.recycle()
            }
        } else {
            bitmap
        }
        return Decoded(opaque, sourceLongEdge)
    }

    private fun flattenOntoWhite(source: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(flat)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(source, 0f, 0f, null)
            return flat
        } catch (t: Throwable) {
            flat.recycle()
            throw t
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? {
        // Sized off the source so a JPEG in the hundreds of KB is not assembled
        // by a dozen doublings of a 32-byte array on a phone.
        val out = ByteArrayOutputStream(64 * 1024)
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun shrunkOf(bytes: ByteArray, bitmap: Bitmap): Shrunk =
        Shrunk(bytes, OUTPUT_CONTENT_TYPE, bitmap.width, bitmap.height)

    /**
     * The long edge of a decoded rung, which is the rung's own clamped edge:
     * [ImageShrinkPolicy.targetSize] assigns min(edge, source long edge) to the
     * long side exactly, and [ImageShrinkPolicy.rungsFor] clamps to the same
     * value. Measured rather than remembered so that a decoder which declines
     * the requested size costs one redundant decode instead of silently
     * compressing the wrong bitmap for the rest of the ladder.
     */
    private fun longEdgeOf(bitmap: Bitmap): Int = maxOf(bitmap.width, bitmap.height)
}
