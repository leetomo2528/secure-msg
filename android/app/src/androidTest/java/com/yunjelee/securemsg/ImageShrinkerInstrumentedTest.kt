package com.yunjelee.securemsg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * [ImageShrinker] against the only decoder that matters.
 *
 * Nothing here can be a host test: ImageDecoder, Bitmap.compress and the JPEG
 * encoder are the phone's, and the questions this asks -- does a 12 MP photo
 * actually come out under the budget, does a truncated part abort instead of
 * relaying half an image, does a transparent PNG come out white -- are
 * questions about those implementations. [ImageShrinkPolicy] carries the
 * arithmetic and is pinned by the host suite; this pins the behaviour the
 * policy cannot see.
 *
 * Every fixture is drawn here rather than checked in. A committed .jpg would
 * be a binary blob nobody can diff, and the decoder's own output is what the
 * assertions are about anyway.
 */
@RunWith(AndroidJUnit4::class)
class ImageShrinkerInstrumentedTest {

    /** The web composer's own COMPOSE_ATTACHMENT_BUDGET, which is the tighter carrier-shaped one. */
    private val budget = 240 * 1024

    @Test
    fun aTwelveMegapixelPhotoLandsUnderBudgetAtOrBelowTheTwelveEightyRung() {
        val source = detailedJpeg(4000, 3000)
        // The fixture has to be genuinely expensive to encode, or the top rung
        // fits and the ladder is never exercised at all.
        assertTrue("fixture too easy: ${source.size}", source.size > budget)

        val shrunk = ImageShrinker.shrink(source, "image/jpeg", budget)

        assertNotNull(shrunk)
        val result = shrunk!!
        assertEquals("image/jpeg", result.contentType)
        assertTrue("encoded ${result.bytes.size} > $budget", result.bytes.size <= budget)
        assertTrue(
            "long edge ${maxOf(result.width, result.height)} should have dropped to 1280 or below",
            maxOf(result.width, result.height) <= 1280,
        )
        // 4:3 in, 4:3 out. A rung that rounds the short side independently would
        // produce a file this device and the web client cannot both reproduce.
        assertEquals(3 * result.width, 4 * result.height)

        // The reported size has to describe the bytes, not the rung that was
        // aimed at: the caller puts these dimensions on the wire.
        val decoded = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        assertNotNull(decoded)
        try {
            assertEquals(result.width, decoded.width)
            assertEquals(result.height, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun aTruncatedPartReturnsNullInsteadOfThrowing() {
        val source = detailedJpeg(800, 600)
        // Header and tables intact, scan data cut off mid-stream -- a part the
        // carrier is still downloading. Returning null is what lets MmsProvider
        // report an omission; throwing would escape into its blanket catch and
        // null the entire message, text included.
        val truncated = source.copyOf(source.size / 3)

        assertNull(ImageShrinker.shrink(truncated, "image/jpeg", budget))
        // Not an image at all, and not a header the decoder can even open.
        assertNull(ImageShrinker.shrink(ByteArray(64) { it.toByte() }, "image/jpeg", budget))
        assertNull(ImageShrinker.shrink(ByteArray(0), "image/jpeg", budget))
    }

    @Test
    fun anAnimatedGifIsNeverOfferedToTheShrinker() {
        // The policy is what keeps a GIF away from here: Bitmap.compress keeps
        // frame one and drops the animation, so a "successful" shrink would
        // destroy the only thing the part was.
        assertTrue(ImageShrinkPolicy.isPassThrough("image/gif"))
        assertFalse(ImageShrinkPolicy.isShrinkable("image/gif"))
        assertTrue(ImageShrinkPolicy.isPassThrough("IMAGE/GIF; name=\"cat.gif\""))

        // And the shrinker refuses on the label alone, even handed bytes it
        // could decode perfectly well -- the classification must not depend on
        // which of the two callers remembered to check first.
        assertNull(ImageShrinker.shrink(detailedJpeg(400, 300), "image/gif", budget))
    }

    @Test
    fun aTransparentPngComesOutWhiteRatherThanBlack() {
        val source = transparentDotPng(240)

        val shrunk = ImageShrinker.shrink(source, "image/png", budget)

        assertNotNull(shrunk)
        val decoded = BitmapFactory.decodeByteArray(shrunk!!.bytes, 0, shrunk.bytes.size)
        assertNotNull(decoded)
        try {
            // Well inside the region that was fully transparent. JPEG has no
            // alpha and Skia encodes a premultiplied transparent pixel as black,
            // so without the white composite every one of these reads 0.
            val corner = decoded.getPixel(3, 3)
            assertTrue(
                "transparent corner came out #${Integer.toHexString(corner)}",
                Color.red(corner) > 235 && Color.green(corner) > 235 && Color.blue(corner) > 235,
            )
            // The opaque half still has to survive the flatten: filling white
            // over the image instead of under it would pass the test above.
            val centre = decoded.getPixel(decoded.width / 2, decoded.height / 2)
            assertTrue(
                "centre came out #${Integer.toHexString(centre)}",
                Color.red(centre) > 180 && Color.green(centre) < 90 && Color.blue(centre) < 90,
            )
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun aOneByOneImageIsEncodedRatherThanDividedByZero() {
        val source = jpegOf(solid(1, 1, Color.rgb(180, 20, 20)), 90)

        val shrunk = ImageShrinker.shrink(source, "image/jpeg", budget)

        assertNotNull(shrunk)
        assertEquals(1, shrunk!!.width)
        assertEquals(1, shrunk.height)
        assertTrue(shrunk.bytes.isNotEmpty())
        assertEquals("image/jpeg", shrunk.contentType)

        // A zero budget is the allocator saying this part has no room, not a
        // target to encode down to.
        assertNull(ImageShrinker.shrink(source, "image/jpeg", 0))
    }

    /**
     * A JPEG expensive enough to walk the ladder: a smooth gradient under a
     * repeating block of per-pixel noise, which is roughly the local entropy of
     * foliage or fabric and is what makes a real camera photo 3-12 MB.
     *
     * The noise is seeded, so the fixture is byte-identical on every run and a
     * size assertion cannot pass on one device and fail on the next.
     */
    private fun detailedJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawPaint(
                Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, width.toFloat(), height.toFloat(),
                        Color.rgb(30, 90, 160), Color.rgb(230, 180, 90),
                        Shader.TileMode.CLAMP,
                    )
                },
            )
            val tile = noiseTile(64)
            try {
                canvas.drawPaint(
                    Paint().apply {
                        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                        // Nearly opaque, not half: the noise has to survive a
                        // 2.5x downscale still expensive enough that the top two
                        // rungs overshoot 240 KiB. A gentler blend produces a
                        // 1600 px encode that fits, and the ladder this test is
                        // about is then never walked at all.
                        alpha = 220
                    },
                )
            } finally {
                // The draw is done; the shader no longer needs the tile, and a
                // 12 MP fixture is not the place to leave bitmaps for the GC.
                tile.recycle()
            }
            return jpegOf(bitmap, 85)
        } finally {
            bitmap.recycle()
        }
    }

    private fun noiseTile(size: Int): Bitmap {
        val random = Random(20260912L)
        val pixels = IntArray(size * size) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** A red disc on a fully transparent field, PNG so the alpha survives. */
    private fun transparentDotPng(size: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            Canvas(bitmap).drawCircle(
                size / 2f, size / 2f, size / 4f,
                Paint().apply {
                    color = Color.rgb(220, 30, 30)
                    isAntiAlias = false
                },
            )
            val out = ByteArrayOutputStream()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun solid(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /**
     * Consumes [bitmap]: it is recycled before the bytes are handed back, so a
     * caller's own finally only covers the paths that never reach here.
     * Recycling twice is a no-op.
     */
    private fun jpegOf(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
