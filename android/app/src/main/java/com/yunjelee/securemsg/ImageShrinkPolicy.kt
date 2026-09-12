package com.yunjelee.securemsg

import java.util.Locale

/**
 * Sizing rules for re-encoding an image until it fits a wire that caps
 * attachments, mirrored rung for rung by the web composer's shrink module.
 *
 * No cap moves: [RelayContentCodec.MAX_ATTACHMENT_BYTES],
 * [RelayContentCodec.MAX_ATTACHMENTS] and the server envelope ceiling are what
 * they always were. What changes is that a real phone photo -- 3 to 12 MB out
 * of any modern camera -- is now measured against a budget *below* those caps
 * and encoded down to it, because an over-cap attachment is dropped in silence
 * at both ends: [RelayContentCodec.decode] skips the row and nothing tells the
 * user the photo they were sent no longer exists.
 *
 * Deliberately free of every android.* type so the whole table runs in the host
 * unit-test suite. The ladder only works while both platforms walk the same
 * rungs, and a drift caught by a JVM assertion is one that never reaches a
 * phone.
 */
object ImageShrinkPolicy {
    /**
     * Total attachment budget for one relayed incoming MMS.
     *
     * This is the wire cap itself, not a margin below it. A reserve looks
     * prudent and is not: every relayed byte is bounded twice already --
     * [ImageShrinker] returns only an encode it has measured against the target,
     * and materialize's own accept() refuses any part that would push the
     * running total past the cap. What a reserve does instead is refuse files
     * that fit perfectly well, and a received part refused here is gone for
     * good: the ledger dedupes the message away on every later sweep. An
     * animated GIF at 400 KiB is the case that made this concrete -- inside the
     * wire cap, outside a 384 KiB reserve, and reported to the owner as
     * "용량이 커서 받지 못했습니다" when the only thing too small was our own
     * self-imposed budget.
     */
    const val INCOMING_ATTACHMENT_BUDGET = RelayContentCodec.MAX_ATTACHMENT_BYTES

    /**
     * One (long edge, quality) step of the ladder.
     *
     * Quality is an integer percent on both platforms -- Kotlin hands it to
     * Bitmap.compress as-is, the web encoder divides by 100 for canvas.toBlob
     * -- so the two tables stay literally comparable instead of differing by a
     * float conversion nobody can diff.
     */
    data class Rung(val edge: Int, val quality: Int)

    /** Pixel dimensions of a re-encode target. */
    data class Size(val width: Int, val height: Int)

    /** One part offered to [allocate]: what it is, and how big it claims to be. */
    data class Candidate(val contentType: String, val declaredSize: Int)

    /**
     * The shared ladder, highest rung first.
     *
     * Quality is not monotonic (82, 68, 72, 58, ...) on purpose: every second
     * step cuts resolution, and raising quality again right after a cut buys
     * back more of the artefacts that cut introduced than it costs in bytes.
     * Nothing here may change without the web table changing in the same
     * commit -- ImageShrinkPolicyTest pins all eight entries literally so a
     * one-sided edit fails the build instead of shipping two ladders.
     */
    val SHRINK_RUNGS: List<Rung> = listOf(
        Rung(1600, 82),
        Rung(1600, 68),
        Rung(1280, 72),
        Rung(1280, 58),
        Rung(1024, 66),
        Rung(1024, 50),
        Rung(800, 58),
        Rung(640, 50),
    )

    /**
     * Outcome of reading one attachment part's bytes.
     *
     * Today every failure collapses to an empty ByteArray, which hides the one
     * distinction the caller must act on: [TooLarge] is a part that exists and
     * is merely too big, so it is shrunk or announced as omitted, while
     * [Failed] is a part the carrier has not finished downloading -- there the
     * message must keep DEFERRING, because announcing a loss would tell the
     * user a photo is gone seconds before it arrives, and this being the
     * default SMS app they have no second copy to check against.
     */
    sealed interface PartRead {
        class Ok(val bytes: ByteArray) : PartRead

        object TooLarge : PartRead

        object Failed : PartRead
    }

    /**
     * The ladder as it applies to a source whose long edge is [sourceLongEdge].
     *
     * Rungs clamp down, never up: re-encoding a 900 px photo at 1600 invents
     * pixels and usually produces a *larger* file, the exact opposite of why
     * the ladder exists. Clamping makes neighbouring rungs collide, and a
     * collision is dropped so the loop cannot encode the identical image twice
     * and count the second pass as progress. The lowest rung always survives:
     * it is the floor the loop gives up on, and an empty ladder would leave the
     * caller with nothing to try at all.
     *
     * A non-positive [sourceLongEdge] means the header pass could not measure
     * the source; the full ladder is returned and reality is decided by the
     * encoder, which measures every rung it walks anyway.
     */
    fun rungsFor(sourceLongEdge: Int): List<Rung> {
        if (sourceLongEdge <= 0) return SHRINK_RUNGS
        val collapsed = LinkedHashSet<Rung>()
        SHRINK_RUNGS.forEach { collapsed += Rung(minOf(it.edge, sourceLongEdge), it.quality) }
        return collapsed.toList()
    }

    /**
     * Dimensions to scale [srcW] x [srcH] to for a rung of long edge [edge].
     *
     * The long edge is assigned rather than computed so it lands on exactly
     * min(edge, source long edge): deriving both sides from one float ratio
     * lets the long side come out a pixel short, and a rung that is 1599 px
     * wide on one platform and 1600 on the other breaks the byte-for-byte
     * comparison the shared ladder is for. The short side floors, and never
     * below 1, because a zero-height bitmap cannot be allocated.
     */
    fun targetSize(srcW: Int, srcH: Int, edge: Int): Size {
        if (srcW <= 0 || srcH <= 0) return Size(1, 1)
        val sourceLong = maxOf(srcW, srcH)
        val target = minOf(edge, sourceLong).coerceAtLeast(1)
        if (target >= sourceLong) return Size(srcW, srcH)
        val short = { side: Int -> ((side.toLong() * target) / sourceLong).toInt().coerceAtLeast(1) }
        return if (srcW >= srcH) Size(target, short(srcH)) else Size(short(srcW), target)
    }

    /**
     * `inSampleSize` for the decoder's header pass: the largest power of two
     * that still leaves both dimensions at or above [targetSize].
     *
     * Subsampling is what keeps a 12 MP source off the heap, but it must never
     * undershoot the target -- the final scale would then be an *upscale*,
     * throwing away the detail the rung was chosen to keep and inflating the
     * encode instead of shrinking it. A power of two because BitmapFactory
     * rounds anything else down to one, and never below 1 because 0 would
     * decode nothing.
     */
    fun sampleSizeFor(srcW: Int, srcH: Int, edge: Int): Int {
        if (srcW <= 0 || srcH <= 0) return 1
        val target = targetSize(srcW, srcH, edge)
        var sample = 1
        while (srcW / (sample * 2) >= target.width && srcH / (sample * 2) >= target.height) {
            sample *= 2
        }
        return sample
    }

    /**
     * Index of the rung to jump to after measuring one encode.
     *
     * Encoded size tracks pixel count almost linearly and quality roughly
     * quadratically, so a single real measurement predicts the rest of the
     * ladder well enough to skip the rungs that cannot possibly fit -- worth
     * doing because every skipped rung is a full decode plus encode of a
     * multi-megapixel bitmap on a phone. The aim is 90% of [budget], not
     * [budget]: the model is an approximation, and a rung that lands 3% over
     * costs an entire extra pass.
     *
     * The probe's own rung is recovered from its measurement -- the tightest
     * rung carrying the probe's quality that could have produced that many
     * pixels, since no rung yields more than edge^2 of them. That ambiguity
     * (quality 58 and 50 each appear twice) is resolved *downwards* on purpose:
     * guessing lower than the probe's true rung costs a slightly smaller image,
     * while guessing higher returns an index behind the probe and sends the
     * loop back up the ladder it just walked down, where it can re-encode
     * forever. For the same reason an unmeasurable probe drops straight to the
     * floor instead of pretending the top rung will do.
     */
    fun predictRung(
        probePixels: Int,
        probeQuality: Int,
        probeBytes: Int,
        budget: Int,
        rungs: List<Rung>,
    ): Int {
        if (rungs.isEmpty()) return 0
        val floor = rungs.lastIndex
        if (probePixels <= 0 || probeQuality <= 0 || probeBytes <= 0 || budget <= 0) return floor
        val start = probeRungIndex(probePixels, probeQuality, rungs)
        val probe = rungs[start]
        val aim = budget * 0.9
        for (i in start..floor) {
            // Clamped because a clamped ladder can put equal edges next to each
            // other; an edge above the probe's would predict an upscale that
            // the encoder never performs.
            val edgeRatio = minOf(rungs[i].edge, probe.edge).toDouble() / probe.edge
            val qualityRatio = rungs[i].quality.toDouble() / probeQuality
            // bytes ~ pixels * quality^2, and pixels ~ edge^2 at a fixed aspect
            val predicted = probeBytes * edgeRatio * edgeRatio * qualityRatio * qualityRatio
            if (predicted <= aim) return i
        }
        return floor
    }

    /**
     * How many bytes each candidate may occupy, index for index with
     * [candidates]. 0 means the part cannot be made to fit and is an omission
     * -- unless [isIgnorable] says losing it costs the user nothing.
     *
     * Reserve, then split, then recredit:
     *
     * A pass-through part is reserved first at its full declared size. It
     * cannot be re-encoded, so it either travels as measured or not at all, and
     * taking its bytes out of the pool before the split stops the shrinkable
     * images from being handed budget that was never available to them.
     *
     * What remains splits evenly, so one 12 MP photo cannot starve the three
     * beside it down to unreadable rungs.
     *
     * An image already smaller than its share hands the surplus back, and the
     * split repeats over the rest. That recredit is what lets a 20 KB thumbnail
     * travelling with one big photo spend almost the entire budget on the
     * photo, instead of parking half of it on a part that needed none of it.
     *
     * Slots past [RelayContentCodec.MAX_ATTACHMENTS] get nothing: the codec
     * refuses to encode a ninth attachment, so budgeting one would trade a
     * dropped photo for a thrown message.
     */
    fun allocate(candidates: List<Candidate>, total: Int): List<Int> {
        val budgets = MutableList(candidates.size) { 0 }
        if (candidates.isEmpty() || total <= 0) return budgets
        var pool = total
        var slots = 0
        val pending = mutableListOf<Int>()
        candidates.forEachIndexed { index, candidate ->
            val mime = mediaType(candidate.contentType)
            // Ignorable parts take no slot either: a layout script must not
            // push a real attachment past MAX_ATTACHMENTS any more than it may
            // take bytes from one.
            if (isIgnorable(mime) || slots >= RelayContentCodec.MAX_ATTACHMENTS) return@forEachIndexed
            when {
                // A GIF and a voice clip are the same problem: the bytes travel
                // verbatim or not at all, so the only question is whether they
                // fit. Giving a non-image nothing was what dropped a 40 KB
                // audio part that the previous build carried without trouble,
                // and announced it as a loss.
                isShrinkable(mime) -> {
                    pending += index
                    slots += 1
                }
                candidate.declaredSize in 1..pool -> {
                    budgets[index] = candidate.declaredSize
                    pool -= candidate.declaredSize
                    slots += 1
                }
                // Declared size unknown: reserve nothing, but still take the
                // slot and let the caller decide once it has read the bytes.
                // Reserving a guess would take budget from a photo that needs
                // it for a part that may not even be there.
                candidate.declaredSize < 0 -> slots += 1
            }
        }
        while (pending.isNotEmpty()) {
            val share = pool / pending.size
            if (share <= 0) break
            // A declared size of 0 is "not measured yet", never "needs
            // nothing": it must not settle at a budget of zero bytes.
            val settled = pending.filter { candidates[it].declaredSize in 1..share }
            if (settled.isEmpty()) {
                pending.forEach { budgets[it] = share }
                break
            }
            settled.forEach {
                budgets[it] = candidates[it].declaredSize
                pool -= candidates[it].declaredSize
            }
            pending.removeAll(settled)
        }
        return budgets
    }

    /**
     * Whether a part can be re-encoded to hit a budget.
     *
     * `image/jpg` is not the IANA name, but senders and gateways emit it and
     * the platform decodes it as a JPEG regardless; calling it unshrinkable
     * would announce a perfectly readable photo as lost.
     */
    fun isShrinkable(mime: String?): Boolean = mediaType(mime) in SHRINKABLE_TYPES

    /**
     * GIF, and nothing else.
     *
     * Bitmap.compress and canvas.toBlob both flatten an animated GIF to a
     * single still frame, so "shrinking" one destroys the only thing it was. It
     * travels untouched at its measured size or it does not travel.
     */
    fun isPassThrough(mime: String?): Boolean = mediaType(mime) == "image/gif"

    /**
     * A part whose loss costs the user nothing.
     *
     * SMIL is the MMS layout script -- which part appears where, for how long
     * -- and nothing in this app renders it. It must consume no budget, or a
     * 2 KB layout would push a photo down a rung for no one's benefit, and it
     * must never be reported as an omission. Matching the subtype prefix rather
     * than the exact `application/smil` keeps this aligned with
     * [IncomingOmissionNotice.omissionFor], so the two can never disagree over
     * whether `application/smil+xml` is noise or a lost attachment.
     */
    fun isIgnorable(mime: String?): Boolean =
        mediaType(mime).substringAfter('/', "").startsWith("smil")

    /**
     * How an omission notice names a part this policy could not fit.
     *
     * Delegates to [IncomingOmissionNotice.omissionFor] so a single table
     * decides both what a lost part is called and whether it is worth naming at
     * all. A part that notice refuses to announce is not an omission and is
     * filtered by [isIgnorable] before it ever reaches here, which is why the
     * fallback is the neutral FILE rather than a second copy of the prefix
     * table drifting alongside the first.
     */
    fun omissionKind(mime: String?): IncomingOmissionNotice.Kind =
        IncomingOmissionNotice.omissionFor(mime)?.kind ?: IncomingOmissionNotice.Kind.FILE

    private val SHRINKABLE_TYPES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp",
        "image/heic",
        "image/heif",
        "image/bmp",
    )

    /** Media type alone: a `; charset=` or `; name=` parameter never classifies a part. */
    private fun mediaType(value: String?): String =
        value.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)

    private fun probeRungIndex(probePixels: Int, probeQuality: Int, rungs: List<Rung>): Int {
        var index = 0
        rungs.forEachIndexed { i, rung ->
            if (rung.quality == probeQuality && rung.edge.toLong() * rung.edge >= probePixels) {
                index = i
            }
        }
        return index
    }
}
