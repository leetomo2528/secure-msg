package com.yunjelee.securemsg

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * The one step of the outgoing size path that no host test can reach:
 * SmsManager.getCarrierConfigValues() on a real handset.
 *
 * MmsSenderTest pins the fallback by handing [MmsSender.carrierMaxMessageSize]
 * a reader that throws. What it cannot pin is that the real reader returns a
 * number at all — the call chain is three framework hops
 * (getSystemService -> createForSubscriptionId -> carrierConfigValues), any of
 * which an OEM may have changed, and a silent fallback to the AOSP default
 * would look identical to success while sizing every photo against 300 KiB on
 * a carrier that allows far more.
 *
 * NOT EXECUTED in this change: there is no device attached to the machine this
 * was written on. It compiles under :app:assembleDebugAndroidTest and needs a
 * SIM-bearing handset to mean anything.
 */
@RunWith(AndroidJUnit4::class)
class MmsCarrierCeilingInstrumentedTest {
    @Test
    fun carrierCeilingIsAUsableNumberOnThisHandset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ceiling = MmsSender.carrierMaxMessageSize(context)

        // Either the carrier reported something, or the read fell back. Both
        // are correct outcomes; a zero or a negative is neither, and would mean
        // the Bundle default is not being applied.
        assertTrue("carrier ceiling was $ceiling", ceiling > 0)
        // Anything the clamp would reject is a report this app cannot size
        // against, and is worth seeing in a test report rather than only in the
        // budget arithmetic downstream.
        assertTrue(
            "carrier reported an unusable ceiling: $ceiling",
            ceiling >= MmsAttachmentBudget.MIN_MAX_MESSAGE_SIZE,
        )
    }

    @Test
    fun aRealisticPhotoSetIsFittedRatherThanRefusedOnThisHandset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A multi-megapixel photo, which is what the web composer sends and
        // what no carrier ceiling in service accepts whole. With a real
        // ImageShrinker behind it this must come back fitted, never TooLarge:
        // a rejection here would mean the ladder cannot reach a real ceiling
        // and every photo from the web stops at this phone.
        val photo = jpegBytes(2400, 1800)
        val content = RelayContent(
            type = RelayContentCodec.TYPE_MMS,
            text = "사진 보냅니다",
            attachments = listOf(
                RelayAttachment(
                    name = "photo.jpg",
                    contentType = "image/jpeg",
                    data = RelayContentCodec.encodeBytes(photo),
                    size = photo.size,
                ),
            ),
        )

        val fit = MmsSender.fit(context, content)

        assertTrue("fit was ${fit::class.java.simpleName}", fit is MmsSender.Fit.Ready)
        val ready = fit as MmsSender.Fit.Ready
        val budget = MmsAttachmentBudget.forCarrier(
            maxMessageSize = ready.maxMessageSize,
            textBytes = content.text.toByteArray(Charsets.UTF_8).size,
            subjectBytes = 0,
            partCount = 2,
        )
        assertTrue(
            "fitted ${ready.attachments.single().size}B against a ${budget}B budget",
            ready.attachments.single().size <= budget,
        )
        // The durable copy is the point of the identity/payload split.
        assertEquals(1, content.attachments.size)
        assertTrue(content.attachments.single().size >= ready.attachments.single().size)
    }

    /** A real JPEG, because ImageShrinker decodes what it is given. */
    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Noise rather than a flat fill: a single-colour bitmap compresses to a
        // few KiB at any rung, which would fit the budget without ever
        // exercising the ladder.
        val random = Random(42)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = random.nextInt()
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
