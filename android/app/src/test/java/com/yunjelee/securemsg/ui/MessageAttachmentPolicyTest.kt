package com.yunjelee.securemsg.ui

import com.yunjelee.securemsg.ImageShrinkPolicy
import com.yunjelee.securemsg.RelayAttachment
import com.yunjelee.securemsg.RelayContent
import com.yunjelee.securemsg.RelayContentCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAttachmentPolicyTest {
    private fun b64(bytes: ByteArray) = RelayContentCodec.encodeBytes(bytes)

    private fun attachment(
        name: String = "photo.jpg",
        type: String = "image/jpeg",
        bytes: ByteArray = ByteArray(12) { it.toByte() },
    ) = RelayAttachment(name = name, contentType = type, data = b64(bytes), size = bytes.size)

    /** The same row, as the bubble's own type reads it back out of the column. */
    private fun shown(item: RelayAttachment) =
        ShownAttachment(name = item.name, contentType = item.contentType, data = item.data, size = item.size)

    private fun columnOf(vararg items: RelayAttachment): String =
        RelayContentCodec.attachmentsJson(
            RelayContent(type = RelayContentCodec.TYPE_MMS, text = "", attachments = items.toList()),
        )!!

    // -----------------------------------------------------------------------
    // parse: the column as the three writers actually write it
    // -----------------------------------------------------------------------

    @Test
    fun readsBackWhatTheCodecWrites() {
        val bytes = ByteArray(40) { (it * 7).toByte() }
        val json = columnOf(attachment(name = "휴가.jpg", bytes = bytes))
        val rows = MessageAttachmentPolicy.parse(json)
        assertEquals(1, rows.size)
        assertEquals("휴가.jpg", rows[0].name)
        assertEquals("image/jpeg", rows[0].contentType)
        assertEquals(40, rows[0].size)
        assertEquals(b64(bytes), rows[0].data)
    }

    @Test
    fun malformedColumnsRenderAsNothingRatherThanThrowing() {
        // Every one of these is a shape this column could be left in by a
        // half-written row or a build that no longer exists. None may take the
        // chat list down with it.
        listOf(
            null,
            "",
            "   ",
            "not json at all",
            "{\"name\":\"x\"}", // the object, without the array around it
            "[",
            "[1, 2, 3]",
            "[null]",
            "[[]]",
            "[{}]",
        ).forEach { json ->
            assertEquals("input: $json", emptyList<ShownAttachment>(), MessageAttachmentPolicy.parse(json))
        }
    }

    @Test
    fun skipsRowsItCannotTrustAndKeepsTheRest() {
        val good = attachment(name = "ok.png", type = "image/png")
        val json = "[" +
            "{\"name\":\"nodata\",\"content_type\":\"image/png\",\"size\":4}," +
            "{\"name\":\"blank\",\"content_type\":\"image/png\",\"data\":\"\",\"size\":4}," +
            "{\"name\":\"negative\",\"content_type\":\"image/png\",\"data\":\"AAAA\",\"size\":-1}," +
            "{\"name\":\"nosize\",\"content_type\":\"image/png\",\"data\":\"AAAA\"}," +
            "{\"name\":\"oversize\",\"content_type\":\"image/png\",\"data\":\"AAAA\"," +
            "\"size\":${RelayContentCodec.MAX_ATTACHMENT_BYTES + 1}}," +
            "7," +
            "{\"name\":\"${good.name}\",\"content_type\":\"${good.contentType}\"," +
            "\"data\":\"${good.data}\",\"size\":${good.size}}" +
            "]"
        val rows = MessageAttachmentPolicy.parse(json)
        assertEquals(1, rows.size)
        assertEquals("ok.png", rows[0].name)
    }

    @Test
    fun aJsonNullIsNeverReadAsTheStringNull() {
        // The platform's org.json returns "null" from optString over a JSON
        // null while this suite's artifact honours the fallback. A row guarded
        // by isNull() is skipped identically on both, which is the only way an
        // assertion here says anything about the phone.
        val rows = MessageAttachmentPolicy.parse(
            "[{\"name\":null,\"content_type\":null,\"data\":null,\"size\":null}]",
        )
        assertEquals(emptyList<ShownAttachment>(), rows)
    }

    @Test
    fun anAbsentContentTypeFallsBackRatherThanBecomingAPicture() {
        val rows = MessageAttachmentPolicy.parse("[{\"name\":\"x\",\"data\":\"AAAA\",\"size\":3}]")
        assertEquals(1, rows.size)
        assertEquals("application/octet-stream", rows[0].contentType)
        assertFalse(MessageAttachmentPolicy.isInlineImage(rows[0].contentType))
    }

    @Test
    fun neverReturnsMoreThanTheWireCapAllows() {
        val many = (0 until RelayContentCodec.MAX_ATTACHMENTS + 6).joinToString(",") {
            "{\"name\":\"p$it\",\"content_type\":\"image/png\",\"data\":\"AAAA\",\"size\":3}"
        }
        assertEquals(
            RelayContentCodec.MAX_ATTACHMENTS,
            MessageAttachmentPolicy.parse("[$many]").size,
        )
    }

    @Test
    fun aColumnOfJunkCostsBoundedWorkInsteadOfItsOwnLength() {
        // 5000 unusable rows: the loop gives up after its inspection budget
        // rather than walking all of them on a list that is being flung. The
        // usable row past the budget is the price, and it is the right one --
        // nothing this app writes puts a real attachment there.
        val junk = (0 until 5000).joinToString(",") { "{\"name\":\"j$it\"}" }
        val good = attachment(name = "last.png", type = "image/png")
        val json = "[$junk,{\"name\":\"${good.name}\",\"content_type\":\"${good.contentType}\"," +
            "\"data\":\"${good.data}\",\"size\":${good.size}}]"
        assertEquals(emptyList<ShownAttachment>(), MessageAttachmentPolicy.parse(json))
    }

    // -----------------------------------------------------------------------
    // isInlineImage: the same fixed whitelist the web draws from
    // -----------------------------------------------------------------------

    @Test
    fun drawsExactlyTheTypesTheWebDraws() {
        // Mirrors frontend/src/store/helpers.ts isInlineImage, which
        // helpers.test.ts pins to this same list.
        listOf("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp", "image/bmp")
            .forEach { assertTrue(it, MessageAttachmentPolicy.isInlineImage(it)) }
    }

    @Test
    fun refusesEverythingElseIncludingAWildcard() {
        listOf(
            null, "", "image", "image/", "image/*", "image/svg+xml", "image/heic", "image/heif",
            "image/tiff", "application/pdf", "video/mp4", "text/plain", "application/octet-stream",
            "notimage/png", "image/png/extra",
        ).forEach { assertFalse("$it", MessageAttachmentPolicy.isInlineImage(it)) }
    }

    @Test
    fun classifiesOnTheMediaTypeAloneAndIgnoresCase() {
        assertTrue(MessageAttachmentPolicy.isInlineImage("IMAGE/PNG"))
        assertTrue(MessageAttachmentPolicy.isInlineImage("image/jpeg; name=\"a.jpg\""))
        assertTrue(MessageAttachmentPolicy.isInlineImage("  image/webp  "))
    }

    @Test
    fun theDrawableAndShrinkableListsDisagreeOnPurpose() {
        // They answer different questions and must never be merged: the shrink
        // ladder re-encodes HEIC and cannot re-encode an animated GIF, while
        // the web draws GIF and cannot draw HEIC.
        assertTrue(ImageShrinkPolicy.isShrinkable("image/heic"))
        assertFalse(MessageAttachmentPolicy.isInlineImage("image/heic"))
        assertFalse(ImageShrinkPolicy.isShrinkable("image/gif"))
        assertTrue(MessageAttachmentPolicy.isInlineImage("image/gif"))
    }

    // -----------------------------------------------------------------------
    // Labels -- Korean, and never zero
    // -----------------------------------------------------------------------

    @Test
    fun sizesReadInKoreanUnitsAndNeverRoundToZero() {
        assertEquals("0B", MessageAttachmentPolicy.sizeLabel(0))
        assertEquals("840B", MessageAttachmentPolicy.sizeLabel(840))
        assertEquals("1KB", MessageAttachmentPolicy.sizeLabel(1024))
        // A byte over 1 KiB is 2KB, not 1KB: the label rounds up, so nothing
        // that exists is ever described as taking no room.
        assertEquals("2KB", MessageAttachmentPolicy.sizeLabel(1025))
        assertEquals("1.0MB", MessageAttachmentPolicy.sizeLabel(1024 * 1024))
        assertEquals("크기 알 수 없음", MessageAttachmentPolicy.sizeLabel(-1))
    }

    @Test
    fun anUnnamedAttachmentStillHasAKoreanName() {
        val unnamed = ShownAttachment(name = "  ", contentType = "application/pdf", data = "AAAA", size = 2048)
        assertEquals("첨부파일 · 2KB", MessageAttachmentPolicy.chipLabel(unnamed))
        assertEquals("사진", MessageAttachmentPolicy.imageDescription(unnamed))
    }

    @Test
    fun aNamedAttachmentKeepsItsName() {
        val named = ShownAttachment(name = "보고서.pdf", contentType = "application/pdf", data = "AAAA", size = 12_300)
        assertEquals("보고서.pdf · 13KB", MessageAttachmentPolicy.chipLabel(named))
        assertEquals("보고서.pdf", MessageAttachmentPolicy.imageDescription(named))
    }

    // -----------------------------------------------------------------------
    // Cache identity and the memory bound
    // -----------------------------------------------------------------------

    @Test
    fun everyPictureOnScreenHasItsOwnCacheKey() {
        val a = shown(attachment(name = "a.jpg"))
        val b = shown(attachment(name = "b.jpg"))
        val keys = setOf(
            MessageAttachmentPolicy.cacheKey(1L, 0, a),
            MessageAttachmentPolicy.cacheKey(1L, 1, b),
            MessageAttachmentPolicy.cacheKey(2L, 0, a),
        )
        assertEquals(3, keys.size)
    }

    @Test
    fun rewritingAColumnInPlaceRetiresTheOldKey() {
        // MessageDao.updateRelayResult rewrites attachmentsJson on a row that
        // may already be on screen. Folding the size into the key means the new
        // bytes cannot be served the previous photo.
        val before = shown(attachment(bytes = ByteArray(10)))
        val after = shown(attachment(bytes = ByteArray(11)))
        assertTrue(
            MessageAttachmentPolicy.cacheKey(9L, 0, before) !=
                MessageAttachmentPolicy.cacheKey(9L, 0, after),
        )
    }

    @Test
    fun summariesCarryLayoutFactsAndNoPayload() {
        val json = columnOf(
            attachment(name = "shot.png", type = "image/png"),
            attachment(name = "note.pdf", type = "application/pdf"),
        )
        val summaries = MessageAttachmentPolicy.summarize(messageId = 5L, json = json)
        assertEquals(2, summaries.size)
        assertTrue(summaries[0].inlineImage)
        assertEquals(0, summaries[0].index)
        assertFalse(summaries[1].inlineImage)
        assertEquals(1, summaries[1].index)
        assertEquals("note.pdf · 12B", summaries[1].label)
        // Whatever a summary holds, it is not the base64: caching one per
        // message must not cost what the column costs.
        val payload = attachment().data
        listOf(summaries[0].key, summaries[0].label, summaries[0].description)
            .forEach { assertFalse(it, it.contains(payload)) }
    }

    @Test
    fun summarizingAnEmptyColumnIsEmpty() {
        assertEquals(emptyList<AttachmentSummary>(), MessageAttachmentPolicy.summarize(1L, null))
        assertEquals(emptyList<AttachmentSummary>(), MessageAttachmentPolicy.summarize(1L, "[]"))
    }

    @Test
    fun theBitmapCacheIsBoundedOnEveryHeapItCanBeAskedAbout() {
        val floor = MessageAttachmentPolicy.CACHE_FLOOR_BYTES
        val ceiling = MessageAttachmentPolicy.CACHE_CEILING_BYTES
        // A large heap still stops at the ceiling: a thread of a hundred photos
        // must cost what a thread of seven costs.
        assertEquals(ceiling, MessageAttachmentPolicy.cacheBudgetBytes(4L * 1024 * 1024 * 1024))
        assertEquals(ceiling, MessageAttachmentPolicy.cacheBudgetBytes(512L * 1024 * 1024))
        // An eighth, in the band between the two bounds.
        assertEquals(16 * 1024 * 1024, MessageAttachmentPolicy.cacheBudgetBytes(128L * 1024 * 1024))
        // And never below the floor, or the picture on screen is decoded again
        // on every scroll.
        assertEquals(floor, MessageAttachmentPolicy.cacheBudgetBytes(16L * 1024 * 1024))
        assertEquals(floor, MessageAttachmentPolicy.cacheBudgetBytes(0L))
        assertEquals(floor, MessageAttachmentPolicy.cacheBudgetBytes(-1L))
    }

    @Test
    fun theBudgetHoldsAHandfulOfPicturesNotAThread() {
        // 1024x768 ARGB_8888 = 3 MiB, the largest a decode bounded by
        // INLINE_MAX_EDGE produces at 4:3. The ceiling is measured against
        // that, and it has to stay single digits.
        val perPhoto =
            MessageAttachmentPolicy.INLINE_MAX_EDGE * (MessageAttachmentPolicy.INLINE_MAX_EDGE * 3 / 4) * 4
        val photos = MessageAttachmentPolicy.CACHE_CEILING_BYTES / perPhoto
        assertTrue("$photos", photos in 2..12)
    }

    @Test
    fun subsamplingAloneDoesNotBoundARelayedPhoto() {
        // Why the inline decode asks for an exact target size instead of a
        // sample size. sampleSizeFor deliberately OVERSHOOTS -- its other
        // caller follows it with setTargetSize -- so for every shape a relayed
        // photo actually has it returns 1, and the decode comes out at full
        // resolution. Relayed photos come off the shared ladder, whose top rung
        // is 1600px, so this is not an edge case: it was every photo.
        val edge = MessageAttachmentPolicy.INLINE_MAX_EDGE
        for ((w, h) in listOf(1600 to 1200, 1280 to 960, 1024 to 768, 1200 to 1600)) {
            assertEquals("$w x $h", 1, ImageShrinkPolicy.sampleSizeFor(w, h, edge))
            val target = ImageShrinkPolicy.targetSize(w, h, edge)
            assertTrue("$w x $h -> $target", maxOf(target.width, target.height) <= edge)
        }
    }

    @Test
    fun theInlineEdgeIsARungOfTheSharedLadder() {
        // Reusing a rung keeps one answer in this app to "how large may a
        // decoded image be", rather than a second number drifting beside it.
        assertTrue(ImageShrinkPolicy.SHRINK_RUNGS.any { it.edge == MessageAttachmentPolicy.INLINE_MAX_EDGE })
    }

    @Test
    fun anEmptyColumnHasNoFirstRow() {
        assertNull(MessageAttachmentPolicy.parse(null).firstOrNull())
    }
}
