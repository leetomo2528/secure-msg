package com.yunjelee.securemsg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's own compose path: what the owner's picked photos become before a
 * single row is written.
 *
 * [OutgoingAttachmentPlanner.plan] is the whole decision and is pure by
 * construction, so the table is pinned here. The two things it is not -- the
 * content resolver's read and the bitmap re-encoder -- enter as lambdas, and
 * the recorder below makes "never opened" and "opened twice" both assertable.
 */
class OutgoingAttachmentPlannerTest {
    /** Records which sources were read, so a mis-correlated index is visible. */
    private class Reader(private val answers: List<ImageShrinkPolicy.PartRead>) {
        val indices = mutableListOf<Int>()

        fun fn(): (Int) -> ImageShrinkPolicy.PartRead = { index ->
            indices += index
            answers[index]
        }
    }

    /** A re-encoder that always lands exactly on its budget. */
    private class Shrinker(
        private val answer: (Int) -> MmsSender.ReEncoded? = { budget ->
            MmsSender.ReEncoded(ByteArray(budget) { 9 }, "image/jpeg")
        },
    ) {
        val budgets = mutableListOf<Int>()

        fun fn(): (
            OutgoingAttachmentPlanner.Source,
            ByteArray,
            Int,
        ) -> MmsSender.ReEncoded? = { _, _, budget ->
            budgets += budget
            answer(budget)
        }
    }

    private fun ok(size: Int, fill: Byte = 7) =
        ImageShrinkPolicy.PartRead.Ok(ByteArray(size) { fill })

    private fun jpeg(size: Int) = OutgoingAttachmentPlanner.Source("image/jpeg", size)

    private fun refusal(plan: OutgoingAttachmentPlanner.Plan): String {
        assertTrue(
            "expected a refusal, got $plan",
            plan is OutgoingAttachmentPlanner.Plan.Refused,
        )
        return (plan as OutgoingAttachmentPlanner.Plan.Refused).reason
    }

    private fun ready(plan: OutgoingAttachmentPlanner.Plan): List<RelayAttachment> {
        if (plan is OutgoingAttachmentPlanner.Plan.Refused) {
            throw AssertionError("expected a plan, got a refusal: ${plan.reason}")
        }
        return (plan as OutgoingAttachmentPlanner.Plan.Ready).attachments
    }

    /** Every user-visible string on this path is Korean, so every refusal is. */
    private fun assertKorean(reason: String) {
        assertTrue(
            "reason is not Korean: $reason",
            reason.any { it in '가'..'힣' },
        )
    }

    // ---- the fork that must not move -------------------------------------

    @Test
    fun `a photoless send is not an MMS`() {
        assertFalse(OutgoingAttachmentPlanner.needsMms(0, null))
        assertFalse(OutgoingAttachmentPlanner.needsMms(0, ""))
        assertFalse(OutgoingAttachmentPlanner.needsMms(0, "   "))
    }

    @Test
    fun `a picture or a subject makes it an MMS`() {
        assertTrue(OutgoingAttachmentPlanner.needsMms(1, null))
        // An SMS has nowhere to put a subject, so routing it there would drop
        // it without a word.
        assertTrue(OutgoingAttachmentPlanner.needsMms(0, "생일 사진"))
    }

    @Test
    fun `preflight refuses a non-carrier recipient in Korean`() {
        val reason = OutgoingAttachmentPlanner.preflight(false, "사진 보냅니다", 1)
        assertNotNull(reason)
        assertKorean(reason!!)
    }

    @Test
    fun `preflight refuses a body past the codec ceiling before encode can throw`() {
        val long = "가".repeat(OutgoingAttachmentPlanner.MAX_TEXT_CHARS + 1)
        assertKorean(OutgoingAttachmentPlanner.preflight(true, long, 1)!!)
        // Exactly at the ceiling still goes through: the codec accepts it.
        assertNull(
            OutgoingAttachmentPlanner.preflight(
                true,
                "가".repeat(OutgoingAttachmentPlanner.MAX_TEXT_CHARS),
                1,
            ),
        )
    }

    @Test
    fun `preflight passes a caption-less send that carries a picture`() {
        assertNull(OutgoingAttachmentPlanner.preflight(true, "", 1))
        assertKorean(OutgoingAttachmentPlanner.preflight(true, "   ", 0)!!)
    }

    // ---- the budget -------------------------------------------------------

    @Test
    fun `the budget shrinks with a Korean caption's UTF-8 length, not its characters`() {
        val short = OutgoingAttachmentPlanner.budgetFor(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            "",
            null,
            1,
        )
        val caption = "오늘 찍은 사진 보냅니다. 아주 잘 나왔어요. 확인해 보세요.".repeat(12)
        val long = OutgoingAttachmentPlanner.budgetFor(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            caption,
            null,
            1,
        )
        val utf8 = caption.toByteArray(Charsets.UTF_8).size
        assertEquals(short - utf8, long)
        // The whole point: Korean costs three bytes a character, so charging
        // characters would understate this caption roughly threefold.
        assertTrue(utf8 > caption.length * 2)
    }

    @Test
    fun `the subject is charged as the composer will write it`() {
        val subject = "생일".repeat(200)
        val withSubject = OutgoingAttachmentPlanner.budgetFor(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            "",
            subject,
            1,
        )
        val without = OutgoingAttachmentPlanner.budgetFor(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            "",
            null,
            1,
        )
        // Truncated to 120 characters exactly as MmsPduComposer truncates it,
        // so a 400-character subject costs 120 characters of UTF-8 and no more.
        assertEquals(
            without - subject.take(120).toByteArray(Charsets.UTF_8).size,
            withSubject,
        )
        // A blank subject is never written, so it costs nothing.
        assertEquals(
            without,
            OutgoingAttachmentPlanner.budgetFor(
                MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
                "",
                "   ",
                1,
            ),
        )
    }

    @Test
    fun `a generous carrier ceiling is still clamped to the wire cap`() {
        val budget = OutgoingAttachmentPlanner.budgetFor(
            MmsAttachmentBudget.MAX_MAX_MESSAGE_SIZE,
            "",
            null,
            1,
        )
        // The relay copy is these same bytes, so the codec's cap binds even when
        // the carrier would happily take two megabytes.
        assertEquals(RelayContentCodec.MAX_ATTACHMENT_BYTES, budget)
    }

    @Test
    fun `a mean carrier ceiling binds below the wire cap`() {
        val budget = OutgoingAttachmentPlanner.budgetFor(
            MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
            "",
            null,
            1,
        )
        assertTrue(budget < RelayContentCodec.MAX_ATTACHMENT_BYTES)
        assertEquals(
            MmsAttachmentBudget.forCarrier(
                MmsAttachmentBudget.DEFAULT_MAX_MESSAGE_SIZE,
                0,
                0,
                2,
            ),
            budget,
        )
    }

    // ---- the plan ---------------------------------------------------------

    @Test
    fun `a photo that already fits keeps its pixels byte for byte but loses its metadata`() {
        // SM-4: the camera's EXIF (GPS, capture time, model), XMP and comment
        // used to ride along to the carrier and the relay. Everything else --
        // JFIF, the ICC profile, the tables, the scan -- is untouched.
        val bytes = ImageFixtures.jpegWithMetadata(scanSize = 40_000)
        val shrinker = Shrinker()
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(jpeg(bytes.size)),
            budget = 200_000,
            read = { ImageShrinkPolicy.PartRead.Ok(bytes) },
            shrink = shrinker.fn(),
        )
        val out = ready(plan)
        assertEquals(1, out.size)
        val expected = ImageFixtures.jpegStripped(scanSize = 40_000)
        assertArrayEquals(expected, RelayContentCodec.decodeBytes(out[0].data))
        assertEquals(expected.size, out[0].size)
        assertFalse(String(RelayContentCodec.decodeBytes(out[0].data), Charsets.ISO_8859_1).contains(ImageFixtures.GPS_MARKER))
        // Re-encoding a photo that fits would cost quality for nothing.
        assertTrue(shrinker.budgets.isEmpty())
    }

    @Test
    fun `a photo with nothing to strip travels byte for byte`() {
        for ((type, bytes) in listOf(
            "image/jpeg" to ImageFixtures.jpegClean(scanSize = 40_000),
            "image/png" to ImageFixtures.pngStripped(idatSize = 40_000),
            // Not parseable as its type: uncertain framing is sent as it is.
            "image/jpeg" to ByteArray(40_000) { 3 },
        )) {
            val shrinker = Shrinker()
            val out = ready(
                OutgoingAttachmentPlanner.plan(
                    sources = listOf(OutgoingAttachmentPlanner.Source(type, bytes.size)),
                    budget = 200_000,
                    read = { ImageShrinkPolicy.PartRead.Ok(bytes) },
                    shrink = shrinker.fn(),
                ),
            )
            assertArrayEquals(type, bytes, RelayContentCodec.decodeBytes(out.single().data))
            assertTrue(shrinker.budgets.isEmpty())
        }
    }

    @Test
    fun `a PNG that fits loses its text, time and EXIF chunks`() {
        val bytes = ImageFixtures.pngWithMetadata(idatSize = 20_000)
        val out = ready(
            OutgoingAttachmentPlanner.plan(
                sources = listOf(OutgoingAttachmentPlanner.Source("image/png", bytes.size)),
                budget = 200_000,
                read = { ImageShrinkPolicy.PartRead.Ok(bytes) },
                shrink = Shrinker().fn(),
            ),
        ).single()
        assertEquals("image/png", out.contentType)
        assertArrayEquals(ImageFixtures.pngStripped(idatSize = 20_000), RelayContentCodec.decodeBytes(out.data))
    }

    @Test
    fun `a photo that only fits once stripped is not re-encoded`() {
        // 60 KB of COM on top of a 150 KB photo against a 200 KB budget: the
        // original is over, the stripped copy is under. Judging by the
        // original would spend a decode and a generation of quality on
        // metadata the send was going to drop anyway.
        val bytes = ImageFixtures.jpegWithMetadata(scanSize = 150_000, commentSize = 60_000)
        assertTrue(bytes.size > 200_000)
        val shrinker = Shrinker()
        val out = ready(
            OutgoingAttachmentPlanner.plan(
                sources = listOf(jpeg(bytes.size)),
                budget = 200_000,
                read = { ImageShrinkPolicy.PartRead.Ok(bytes) },
                shrink = shrinker.fn(),
            ),
        ).single()
        assertArrayEquals(ImageFixtures.jpegStripped(scanSize = 150_000), RelayContentCodec.decodeBytes(out.data))
        assertTrue(shrinker.budgets.isEmpty())
    }

    @Test
    fun `a rotated JPEG that fits is re-encoded upright at no more than its own size`() {
        // Stripping APP1 would delete Orientation=6 and the photo would arrive
        // sideways, so it goes through the re-encoder, which applies the
        // rotation and writes no EXIF.
        val bytes = ImageFixtures.jpegWithMetadata(orientation = 6, scanSize = 40_000)
        val shrinker = Shrinker { budget -> MmsSender.ReEncoded(ByteArray(budget - 100) { 9 }, "image/jpeg") }
        val out = ready(
            OutgoingAttachmentPlanner.plan(
                sources = listOf(jpeg(bytes.size)),
                budget = 200_000,
                read = { ImageShrinkPolicy.PartRead.Ok(bytes) },
                shrink = shrinker.fn(),
            ),
        ).single()
        assertEquals(listOf(bytes.size), shrinker.budgets)
        assertEquals(bytes.size - 100, out.size)
        assertEquals(9.toByte(), RelayContentCodec.decodeBytes(out.data)[0])
    }

    @Test
    fun `a rotated JPEG whose re-encode fails is sent as it always was, never refused`() {
        val bytes = ImageFixtures.jpegWithMetadata(orientation = 8, scanSize = 40_000)
        for (answer in listOf<(Int) -> MmsSender.ReEncoded?>(
            { null },
            { budget -> MmsSender.ReEncoded(ByteArray(budget + 1), "image/jpeg") },
            { MmsSender.ReEncoded(ByteArray(0), "image/jpeg") },
        )) {
            val out = ready(
                OutgoingAttachmentPlanner.plan(
                    sources = listOf(jpeg(bytes.size)),
                    budget = 200_000,
                    read = { ImageShrinkPolicy.PartRead.Ok(bytes) },
                    shrink = Shrinker(answer).fn(),
                ),
            ).single()
            assertArrayEquals(bytes, RelayContentCodec.decodeBytes(out.data))
        }
    }

    @Test
    fun `a HEIC that fits is still converted, because nothing can draw a HEIC`() {
        // The one format this app can shrink but neither client renders. Passing
        // it through because it happened to fit made a photo's viewability
        // depend on its file size: a 2MB HEIC came out a JPEG, a 300KB one
        // arrived as a download chip on the owner's own phone.
        val bytes = ByteArray(40_000) { 3 }
        val shrinker = Shrinker()
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(OutgoingAttachmentPlanner.Source("image/heic", bytes.size)),
            budget = 200_000,
            read = { ImageShrinkPolicy.PartRead.Ok(bytes) },
            shrink = shrinker.fn(),
        )
        val out = ready(plan)
        assertEquals(1, out.size)
        assertEquals("image/jpeg", out[0].contentType)
        assertTrue(shrinker.budgets.isNotEmpty())
    }

    @Test
    fun `every format that travels untouched is one both clients draw`() {
        for (type in listOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")) {
            assertTrue(type, ImageShrinkPolicy.isInlineRenderable(type))
        }
        // Shrinkable is a different question from renderable, and the send path
        // needs both: it converts what it can shrink but nobody can show.
        assertTrue(ImageShrinkPolicy.isShrinkable("image/heic"))
        assertFalse(ImageShrinkPolicy.isInlineRenderable("image/heic"))
        assertFalse(ImageShrinkPolicy.isInlineRenderable("image/heif"))
    }

    @Test
    fun `each source is read by its own index, never matched back by value`() {
        // Two picks that are equal as values: same type, same declared size.
        // A caller correlating by equality would read the first file twice and
        // send the owner two copies of one photo.
        val reader = Reader(listOf(ok(9_000, 1), ok(9_000, 2)))
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(jpeg(9_000), jpeg(9_000)),
            budget = 200_000,
            read = reader.fn(),
            shrink = Shrinker().fn(),
        )
        val out = ready(plan)
        assertEquals(listOf(0, 1), reader.indices)
        // Byte-typed on purpose: assertEquals(Int, Byte) picks the Object
        // overload and fails on the boxing rather than on the bytes.
        assertEquals(1.toByte(), RelayContentCodec.decodeBytes(out[0].data)[0])
        assertEquals(2.toByte(), RelayContentCodec.decodeBytes(out[1].data)[0])
    }

    @Test
    fun `the accepted total never exceeds the wire cap`() {
        // Eight full-cap photos against a carrier that would take two megabytes,
        // with a re-encoder that always spends its entire allowance.
        val sources = List(8) { jpeg(RelayContentCodec.MAX_ATTACHMENT_BYTES) }
        val shrinker = Shrinker()
        val plan = OutgoingAttachmentPlanner.plan(
            sources = sources,
            budget = OutgoingAttachmentPlanner.budgetFor(
                MmsAttachmentBudget.MAX_MAX_MESSAGE_SIZE,
                "",
                null,
                sources.size,
            ),
            read = { ok(RelayContentCodec.MAX_ATTACHMENT_BYTES) },
            shrink = shrinker.fn(),
        )
        val out = ready(plan)
        assertEquals(8, out.size)
        assertTrue(out.sumOf { it.size } <= RelayContentCodec.MAX_ATTACHMENT_BYTES)
        // And the content it builds is one the codec will actually accept —
        // an over-cap encode throws, and a throw here is a crash in a Compose
        // handler on a phone that installs its own updates.
        RelayContentCodec.encode(
            RelayContent(
                type = RelayContentCodec.TYPE_MMS,
                text = "",
                direction = RelayContentCodec.DIR_OUT,
                attachments = out,
            ),
        )
    }

    @Test
    fun `a ninth picture is refused before anything is opened`() {
        val reader = Reader(List(9) { ok(1_000) })
        val plan = OutgoingAttachmentPlanner.plan(
            sources = List(9) { jpeg(1_000) },
            budget = 200_000,
            read = reader.fn(),
            shrink = Shrinker().fn(),
        )
        assertKorean(refusal(plan))
        // The codec would drop the ninth in silence; nothing is read at all.
        assertTrue(reader.indices.isEmpty())
    }

    @Test
    fun `an absurd pick is refused on its declared length, before allocating`() {
        val reader = Reader(listOf(ok(1_000)))
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(jpeg(OutgoingAttachmentPlanner.SOURCE_MAX_BYTES + 1)),
            budget = 200_000,
            read = reader.fn(),
            shrink = Shrinker().fn(),
        )
        assertKorean(refusal(plan))
        assertTrue(reader.indices.isEmpty())
    }

    @Test
    fun `a pick that declares nothing is still bounded by the reader`() {
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(jpeg(-1)),
            budget = 200_000,
            read = { ImageShrinkPolicy.PartRead.TooLarge },
            shrink = Shrinker().fn(),
        )
        assertKorean(refusal(plan))
    }

    @Test
    fun `a set that cannot be made to fit is refused, never partially sent`() {
        // Three photos, and a re-encoder that cannot hit a third of the budget.
        val shrinker = Shrinker { budget ->
            if (budget < 120_000) null else MmsSender.ReEncoded(ByteArray(budget), "image/jpeg")
        }
        val plan = OutgoingAttachmentPlanner.plan(
            sources = List(3) { jpeg(4_000_000) },
            budget = 200_000,
            read = { ok(4_000_000) },
            shrink = shrinker.fn(),
        )
        val reason = refusal(plan)
        assertKorean(reason)
        // The owner picked all three. Sending the two that fit as though
        // nothing happened is the same silent loss this path exists to end —
        // `refusal` above is the assertion that nothing was planned at all.
        assertTrue(reason.contains("KB"))
    }

    @Test
    fun `a caption that leaves no room is refused rather than sent photoless`() {
        val budget = OutgoingAttachmentPlanner.budgetFor(
            MmsAttachmentBudget.MIN_MAX_MESSAGE_SIZE,
            "가".repeat(19_000),
            null,
            1,
        )
        assertEquals(0, budget)
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(jpeg(30_000)),
            budget = budget,
            read = { ok(30_000) },
            shrink = Shrinker().fn(),
        )
        assertKorean(refusal(plan))
    }

    @Test
    fun `a GIF travels whole or the send is refused`() {
        val fits = OutgoingAttachmentPlanner.plan(
            sources = listOf(OutgoingAttachmentPlanner.Source("image/gif", 60_000)),
            budget = 200_000,
            read = { ok(60_000) },
            shrink = Shrinker().fn(),
        )
        assertEquals("image/gif", ready(fits)[0].contentType)

        val shrinker = Shrinker()
        val tooBig = OutgoingAttachmentPlanner.plan(
            sources = listOf(OutgoingAttachmentPlanner.Source("image/gif", 900_000)),
            budget = 200_000,
            read = { ok(900_000) },
            shrink = shrinker.fn(),
        )
        assertKorean(refusal(tooBig))
        // Flattening an animation to its first frame is a silent loss dressed
        // up as a success, so the re-encoder is never even offered it.
        assertTrue(shrinker.budgets.isEmpty())
    }

    @Test
    fun `an animated WebP travels whole, like a GIF, and is never re-encoded`() {
        val sticker = ImageFixtures.animatedWebp(60_000)
        val shrinker = Shrinker()
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(OutgoingAttachmentPlanner.Source("image/webp", sticker.size)),
            budget = 200_000,
            read = { ImageShrinkPolicy.PartRead.Ok(sticker) },
            shrink = shrinker.fn(),
        )
        val out = ready(plan).single()
        assertEquals("image/webp", out.contentType)
        assertArrayEquals(sticker, RelayContentCodec.decodeBytes(out.data))
        assertTrue(shrinker.budgets.isEmpty())
    }

    @Test
    fun `an animated WebP over budget is refused rather than flattened to its first frame`() {
        // The defect: 600 KB of sticker went to the shrinker as "image/webp",
        // came back as a JPEG of frame one, and was reported as sent.
        val sticker = ImageFixtures.animatedWebp(600_000)
        val shrinker = Shrinker()
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(OutgoingAttachmentPlanner.Source("image/webp", sticker.size)),
            budget = 200_000,
            read = { ImageShrinkPolicy.PartRead.Ok(sticker) },
            shrink = shrinker.fn(),
        )
        assertKorean(refusal(plan))
        assertTrue(shrinker.budgets.isEmpty())

        // Inside the wire cap but over this carrier's budget: same answer.
        val midSized = ImageFixtures.animatedWebp(300_000)
        val refused = OutgoingAttachmentPlanner.plan(
            sources = listOf(OutgoingAttachmentPlanner.Source("image/webp", midSized.size)),
            budget = 200_000,
            read = { ImageShrinkPolicy.PartRead.Ok(midSized) },
            shrink = shrinker.fn(),
        )
        assertKorean(refusal(refused))
        assertTrue(shrinker.budgets.isEmpty())
    }

    @Test
    fun `an animated WebP beside photos passes wherever a GIF of the same size would`() {
        // Three big photos and a 150 KB animation in a 300 KB budget. An even
        // split would give the animation 75 KB it can never be squeezed into;
        // a GIF is reserved whole. The WebP must get the GIF's answer.
        val size = 150_000
        val budget = 300_000
        val photos = List(3) { jpeg(2_000_000) }
        fun planWith(type: String, bytes: ByteArray): OutgoingAttachmentPlanner.Plan =
            OutgoingAttachmentPlanner.plan(
                sources = listOf(OutgoingAttachmentPlanner.Source(type, bytes.size)) + photos,
                budget = budget,
                read = { index -> if (index == 0) ImageShrinkPolicy.PartRead.Ok(bytes) else ok(2_000_000) },
                shrink = Shrinker().fn(),
            )
        val gif = ready(planWith("image/gif", ByteArray(size) { 0x47 }))
        val reader = Reader(
            listOf(ImageShrinkPolicy.PartRead.Ok(ImageFixtures.animatedWebp(size))) + List(3) { ok(2_000_000) },
        )
        val shrinker = Shrinker()
        val webp = ready(
            OutgoingAttachmentPlanner.plan(
                sources = listOf(OutgoingAttachmentPlanner.Source("image/webp", size)) + photos,
                budget = budget,
                read = reader.fn(),
                shrink = shrinker.fn(),
            ),
        )
        assertEquals(size, gif[0].size)
        assertEquals(size, webp[0].size)
        assertEquals(gif.map { it.size }, webp.map { it.size })
        assertTrue(webp.sumOf { it.size } <= budget)
        // The animation is read once, in the pre-pass, and never re-encoded:
        // three shrinks for three photos.
        assertEquals(listOf(0, 1, 2, 3), reader.indices)
        assertEquals(3, shrinker.budgets.size)
    }

    @Test
    fun `a still WebP is still shrunk like any photo`() {
        val still = ImageFixtures.stillWebp(400_000)
        val shrinker = Shrinker()
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(OutgoingAttachmentPlanner.Source("image/webp", still.size)),
            budget = 200_000,
            read = { ImageShrinkPolicy.PartRead.Ok(still) },
            shrink = shrinker.fn(),
        )
        assertEquals("image/jpeg", ready(plan).single().contentType)
        assertEquals(listOf(200_000), shrinker.budgets)
    }

    @Test
    fun `an unreadable pick is named rather than dropped`() {
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(jpeg(30_000)),
            budget = 200_000,
            read = { ImageShrinkPolicy.PartRead.Failed },
            shrink = Shrinker().fn(),
        )
        assertKorean(refusal(plan))
    }

    @Test
    fun `an overshooting re-encoder is caught rather than trusted`() {
        val shrinker = Shrinker { budget -> MmsSender.ReEncoded(ByteArray(budget + 1), "image/jpeg") }
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(jpeg(900_000)),
            budget = 200_000,
            read = { ok(900_000) },
            shrink = shrinker.fn(),
        )
        assertKorean(refusal(plan))
    }

    @Test
    fun `a big photo beside a thumbnail gets the budget the thumbnail hands back`() {
        val shrinker = Shrinker()
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(jpeg(20_000), jpeg(3_000_000)),
            budget = 300_000,
            read = { index -> if (index == 0) ok(20_000) else ok(3_000_000) },
            shrink = shrinker.fn(),
        )
        val out = ready(plan)
        assertEquals(20_000, out[0].size)
        // An even split would have parked 150_000 on a part that needed 20_000.
        assertEquals(listOf(280_000), shrinker.budgets)
        assertEquals(280_000, out[1].size)
    }

    // ---- names ------------------------------------------------------------

    @Test
    fun `a name always matches the type the bytes ended up as`() {
        assertEquals("photo-1.jpg", OutgoingAttachmentPlanner.attachmentName(0, "image/jpeg"))
        assertEquals("photo-2.jpg", OutgoingAttachmentPlanner.attachmentName(1, "image/jpg"))
        assertEquals("photo-3.png", OutgoingAttachmentPlanner.attachmentName(2, "image/png"))
        assertEquals("photo-1.gif", OutgoingAttachmentPlanner.attachmentName(0, "image/gif"))
        // A parameterised type never writes punctuation into a file name, and
        // the composer writes this name twice per part.
        assertEquals(
            "photo-1.jpg",
            OutgoingAttachmentPlanner.attachmentName(0, "image/jpeg; charset=binary"),
        )
        assertEquals("photo-1.bin", OutgoingAttachmentPlanner.attachmentName(0, "nonsense"))
    }

    @Test
    fun `a re-encoded HEIC is not named heic`() {
        val plan = OutgoingAttachmentPlanner.plan(
            sources = listOf(OutgoingAttachmentPlanner.Source("image/heic", 3_000_000)),
            budget = 200_000,
            read = { ok(3_000_000) },
            shrink = Shrinker().fn(),
        )
        val out = ready(plan)
        // The PDU carries the name in the Content-Type name parameter and in
        // the Content-Location, so a viewer saving it by that name would
        // otherwise write JPEG bytes into a .heic.
        assertEquals("image/jpeg", out[0].contentType)
        assertEquals("photo-1.jpg", out[0].name)
    }

    @Test
    fun `a subject-only send plans to no attachments rather than a refusal`() {
        val out = ready(
            OutgoingAttachmentPlanner.plan(
                sources = emptyList(),
                budget = 200_000,
                read = { throw AssertionError("nothing to read") },
                shrink = Shrinker().fn(),
            ),
        )
        assertTrue(out.isEmpty())
    }
}
