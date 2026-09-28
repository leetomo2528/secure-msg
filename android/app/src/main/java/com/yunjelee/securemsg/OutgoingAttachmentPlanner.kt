package com.yunjelee.securemsg

import java.util.Locale

/**
 * What the owner's picked photos become, or why they are refused.
 *
 * The outgoing mirror of [MmsProvider.materialize]: every decision -- what the
 * budget is, how it splits, what fits, what is refused and in which words --
 * lives here and runs in the host suite, while the three things that cannot
 * (the content resolver's type, the resolver's declared length, and the bitmap
 * re-encoder) enter through parameters.
 *
 * It sits BEFORE [MmsSender.plan] rather than replacing it, and the two size to
 * different things on purpose. MmsSender.plan re-encodes for the carrier alone
 * and deliberately leaves the relay copy full-size, which is right for a photo
 * the web composed and this phone merely forwards. A photo picked here has no
 * full-size copy worth keeping: it came off this device's own storage, where it
 * still is. So it is sized once, down to whatever satisfies BOTH ceilings, and
 * the same bytes travel to the carrier and to the owner's other devices. That
 * is also what keeps [RelayContentCodec.encode] from throwing on a 12 MB camera
 * file -- the codec refuses anything over
 * [RelayContentCodec.MAX_ATTACHMENT_BYTES], and a throw on the send path is a
 * crash in a Compose handler on a phone that installs its own updates.
 *
 * Outgoing refuses where incoming omits. A received part that will not fit is
 * announced as an omission because the alternative is losing the whole message;
 * a picked one that will not fit is the owner's own choice, and sending the
 * other three as though nothing happened is the same silent loss this path
 * exists to end. Either everything travels -- shrunk where it can be -- or
 * nothing does and the owner is told why, in Korean, before a single row is
 * written.
 */
object OutgoingAttachmentPlanner {
    /**
     * The largest single pick this will read into the app process.
     *
     * A photo picker hands back whatever the user taps, including a 50 MB
     * screen recording, and this runs in the activity's own process rather than
     * in a service. The gate is applied to the resolver's declared length
     * first, so the absurd case is refused without allocating anything at all;
     * the read itself repeats the bound for a provider that declares nothing.
     *
     * 32 MiB rather than [MmsProvider.RELAY_SOURCE_MAX_BYTES]'s 8: that ceiling
     * bounds an unattended background sweep over the carrier's own store, while
     * this one bounds a file the owner is watching themselves pick, and a 48 MP
     * JPEG clears 8 MiB without being unreasonable. Only one source's bytes are
     * alive at a time -- each is shrunk and released before the next is opened
     * -- so the ceiling is a peak, not a total.
     */
    const val SOURCE_MAX_BYTES = 32 * 1024 * 1024

    /**
     * One picked attachment as the resolver describes it.
     *
     * @param contentType from `ContentResolver.getType`, never from the file
     *   name: a photo picker URI usually has no name at all, and the ones that
     *   do lie about HEIC often enough to matter.
     * @param declaredSize the resolver's length, or -1 when it will not say.
     *   [ImageShrinkPolicy.allocate] reads it the same way it reads a received
     *   part's: -1 takes a slot and reserves nothing.
     */
    data class Source(val contentType: String, val declaredSize: Int)

    sealed interface Plan {
        /** Ready to persist and dispatch; the total is within both ceilings. */
        class Ready(val attachments: List<RelayAttachment>) : Plan

        /**
         * Nothing is written and nothing is sent.
         *
         * @param reason Korean and user-visible. It is the whole point of the
         *   type: the caller shows it, and the alternative -- the silent drop
         *   both decoders perform on an over-cap attachment -- is a photo that
         *   simply is not there on the other device, with nothing said.
         */
        class Refused(val reason: String) : Plan
    }

    /**
     * Whether this compose action has to be composed as an MMS.
     *
     * The fork in the send path, kept pure so the one behaviour that must not
     * move is pinned by an assertion rather than by reading the dispatcher: a
     * send with no pictures and no subject is FALSE here and goes to
     * [OutgoingSmsDispatcher.queueAndSend] unchanged -- same text content, same
     * single carrier call, same row shape it writes today.
     *
     * A subject counts because an SMS has nowhere to put one. Routing a
     * subject-carrying send to the SMS path would drop it without a word, which
     * is the class of silent loss this whole path exists to end. Today nothing
     * can produce one, so today this is decided by the pictures alone.
     */
    fun needsMms(sourceCount: Int, subject: String?): Boolean =
        sourceCount > 0 || !subject.isNullOrBlank()

    /**
     * Why this send cannot be attempted at all, or null when it can.
     *
     * Everything here is knowable before a content resolver, a bitmap or a Room
     * transaction is touched, which is exactly why it is separated out: the
     * cheapest refusals belong furthest from the irreversible carrier call.
     *
     * @param phoneIsSmsAddress what [PhoneNumberNormalizer.isSmsAddress] says
     *   about the normalized recipient; passed in rather than re-derived so
     *   this stays free of the normalizer's own locale handling.
     */
    fun preflight(phoneIsSmsAddress: Boolean, text: String, sourceCount: Int): String? = when {
        !phoneIsSmsAddress -> "받는 번호가 올바르지 않습니다"
        // The codec's own ceiling. Refusing here names it; letting encode()
        // refuse it throws out of a Compose handler instead.
        text.length > MAX_TEXT_CHARS -> "메시지 내용이 너무 깁니다"
        // Nothing typed and nothing attached. Not an error worth a log line,
        // but the carrier would reject an empty PDU with an opaque integer.
        text.isBlank() && sourceCount <= 0 -> "보낼 내용이 없습니다"
        else -> null
    }

    /** [RelayContentCodec.encode]'s own text ceiling, refused before it throws. */
    const val MAX_TEXT_CHARS = 20_000

    /**
     * Bytes available for attachment data, honouring the carrier's ceiling and
     * the wire cap at once.
     *
     * The caption is charged in UTF-8 bytes, never characters: Korean costs
     * three bytes each, so a 200-character caption takes 600 bytes out of the
     * budget and counting characters would hand back a budget the PDU cannot
     * hold. The subject is charged exactly as [MmsPduComposer] will write it --
     * truncated to 120, and only when it is not blank.
     */
    fun budgetFor(
        maxMessageSize: Int,
        text: String,
        subject: String?,
        sourceCount: Int,
    ): Int {
        val carrier = MmsAttachmentBudget.forCarrier(
            maxMessageSize = maxMessageSize,
            textBytes = text.toByteArray(Charsets.UTF_8).size,
            subjectBytes = subject?.takeIf { it.isNotBlank() }
                ?.take(120)?.toByteArray(Charsets.UTF_8)?.size ?: 0,
            // The text part is always emitted, so the PDU carries one more part
            // than there are attachments.
            partCount = sourceCount.coerceAtLeast(0) + 1,
        )
        // The relay copy is these same bytes, so it must clear the wire cap too.
        return minOf(carrier, RelayContentCodec.MAX_ATTACHMENT_BYTES)
    }

    /**
     * The decision table.
     *
     * @param read pulls one source's bytes by its INDEX in [sources],
     *   distinguishing "too big to hold" from "could not be opened"; see
     *   [ImageShrinkPolicy.PartRead], reused rather than redeclared so the
     *   outgoing reader and the incoming one cannot drift over what the three
     *   outcomes mean. By index and not by [Source] because a Source is a value
     *   -- two picks of the same type and size are equal -- and a caller
     *   matching one back to its URI by equality would read the first file
     *   twice and send the owner two copies of one photo.
     * @param shrink re-encodes one image into an allowance, or returns null
     *   when it cannot. [MmsSender.ReEncoded] because the answer is the same
     *   pair -- the bytes, and the type they came back as.
     */
    fun plan(
        sources: List<Source>,
        budget: Int,
        read: (Int) -> ImageShrinkPolicy.PartRead,
        shrink: (Source, ByteArray, Int) -> MmsSender.ReEncoded?,
    ): Plan {
        // Not a refusal: a subject-carrying send with no pictures is a valid MMS
        // with one text part, and [needsMms] routes it here deliberately.
        if (sources.isEmpty()) return Plan.Ready(emptyList())
        if (sources.size > RelayContentCodec.MAX_ATTACHMENTS) {
            // Refused before anything is opened. The codec would drop the ninth
            // silently and the owner would never learn which one went missing.
            return Plan.Refused(TOO_MANY_REASON)
        }
        // Declared length first, so a 50 MB pick costs a descriptor and nothing
        // else. A provider that declares -1 is measured by the reader instead.
        sources.forEach { source ->
            if (source.declaredSize > SOURCE_MAX_BYTES) {
                return Plan.Refused(oversizeSourceReason(source.declaredSize.toLong()))
            }
        }
        // Clamped whatever the caller computed: everything below guarantees the
        // accepted total fits `cap`, and that guarantee is what keeps
        // RelayContentCodec.encode from throwing on the content built from it.
        val cap = budget.coerceIn(0, RelayContentCodec.MAX_ATTACHMENT_BYTES)
        if (cap <= 0) return Plan.Refused(NO_ROOM_REASON)

        // A WebP is the one type whose bytes decide how it may be budgeted: a
        // still one shrinks like any photo, an animated one travels whole or not
        // at all, exactly like a GIF (SM-9). The allocator has to know which
        // BEFORE it splits the pool, or an animation that fits is handed an even
        // share it can never be re-encoded into and refused where a GIF of the
        // same size would pass. So WebPs alone are read first. Bytes that can
        // still travel (inside the wire cap) are kept for the main pass rather
        // than read twice; an animation already past the cap is refused here,
        // because nothing below could ever send it.
        val preread = arrayOfNulls<ByteArray>(sources.size)
        val animated = BooleanArray(sources.size)
        sources.forEachIndexed { index, source ->
            if (!ImageShrinkPolicy.isWebp(source.contentType)) return@forEachIndexed
            val bytes = readOrRefuse(index, source, read) { return it }
            animated[index] = ImageBytes.isAnimatedWebp(bytes)
            if (animated[index] && bytes.size > cap) {
                return Plan.Refused(unshrinkableReason(bytes.size.toLong(), cap))
            }
            if (bytes.size <= cap) preread[index] = bytes
        }

        val allowances = ImageShrinkPolicy.allocate(
            sources.mapIndexed { index, source ->
                if (animated[index]) {
                    // Measured, not declared: the bytes are in hand, and a
                    // resolver that declared -1 must not cost the animation its
                    // reservation.
                    ImageShrinkPolicy.Candidate(
                        source.contentType,
                        preread[index]?.size ?: source.declaredSize,
                        passThrough = true,
                    )
                } else {
                    ImageShrinkPolicy.Candidate(source.contentType, source.declaredSize)
                }
            },
            cap,
        )
        val out = mutableListOf<RelayAttachment>()
        var used = 0
        sources.forEachIndexed { index, source ->
            // Aim at what is actually left, not merely at what was allocated: a
            // rung chosen for an allowance the running total can no longer hold
            // would spend a full decode and encode on a photo refused anyway.
            val room = minOf(allowances.getOrElse(index) { 0 }, cap - used)
            val bytes = preread[index]?.also { preread[index] = null }
                ?: readOrRefuse(index, source, read) { return it }
            // Verbatim or refused, never re-encoded: the shrinker would keep
            // frame one and report success.
            if (animated[index]) {
                if (bytes.size > room) return Plan.Refused(unshrinkableReason(bytes.size.toLong(), cap))
                out += attachment(index, source.contentType, bytes)
                used += bytes.size
                return@forEachIndexed
            }
            // Already inside its share AND in a format both ends draw: travels
            // byte for byte, because re-encoding a photo that fits costs
            // quality for nothing.
            //
            // The format test is not decoration. HEIC is the one type this app
            // can shrink but neither client can render: both inline whitelists
            // are png/jpeg/gif/webp/bmp. Passing it through unchanged made a
            // photo's viewability depend on its FILE SIZE — a 2 MB HEIC came
            // out a JPEG because the shrinker touched it, while a 300 KB one
            // arrived as a download chip the owner could not view on their own
            // phone or on the web, with nothing reporting a problem.
            //
            // Byte for byte in its pixels, not in its metadata (SM-4): the
            // copy that travels is stripped of EXIF/XMP/COM (JPEG) and
            // eXIf/tEXt/iTXt/zTXt/tIME (PNG) -- GPS, capture time, device
            // model -- exactly as the web composer strips its own picks. These
            // bytes are both the carrier's copy and the relay copy
            // (RelayOutbox.plaintext and the Room row are built from this
            // plan), so stripping here covers both. The fit is judged on the
            // STRIPPED size: a photo that only fits once its metadata is gone
            // must not be re-encoded for it.
            if (ImageShrinkPolicy.isInlineRenderable(source.contentType)) {
                when (val pass = ImageMetadataStripper.forPassThrough(bytes, source.contentType)) {
                    is ImageMetadataStripper.PassThrough.Send -> if (pass.bytes.size <= room) {
                        out += attachment(index, source.contentType, pass.bytes)
                        used += pass.bytes.size
                        return@forEachIndexed
                    }
                    // A JPEG whose EXIF says to rotate it. Stripping would drop
                    // that instruction and the photo would arrive sideways, so
                    // it goes through the re-encoder (which applies the
                    // rotation and writes no EXIF) at no more than its own
                    // size, so the running total can only come out smaller
                    // than it did before. If that fails, the original bytes go
                    // exactly as they did before this change: a pick that
                    // used to pass is never newly refused over its metadata.
                    ImageMetadataStripper.PassThrough.Reencode -> if (bytes.size <= room) {
                        val upright = shrink(source, bytes, bytes.size)
                            ?.takeIf { it.bytes.isNotEmpty() && it.bytes.size <= bytes.size }
                        if (upright != null) {
                            out += attachment(index, upright.contentType, upright.bytes)
                            used += upright.bytes.size
                        } else {
                            out += attachment(index, source.contentType, bytes)
                            used += bytes.size
                        }
                        return@forEachIndexed
                    }
                }
            }
            // A GIF and a video are the same problem here: both encoders flatten
            // an animation to its first frame, and nothing in this app re-encodes
            // a clip at all, so the bytes travel whole or the send is refused.
            if (!ImageShrinkPolicy.isShrinkable(source.contentType)) {
                return Plan.Refused(unshrinkableReason(bytes.size.toLong(), cap))
            }
            if (room <= 0) return Plan.Refused(shrinkFailedReason(bytes.size.toLong(), cap))
            val shrunk = shrink(source, bytes, room)
                ?: return Plan.Refused(shrinkFailedReason(bytes.size.toLong(), cap))
            // The shrinker gives up rather than overshoot, but it is the only
            // thing here that is not pure and the cost of trusting it wrongly is
            // an attachment the receiving decoder drops without telling anyone.
            if (shrunk.bytes.isEmpty() || shrunk.bytes.size > room) {
                return Plan.Refused(shrinkFailedReason(bytes.size.toLong(), cap))
            }
            out += attachment(index, shrunk.contentType, shrunk.bytes)
            used += shrunk.bytes.size
        }
        return Plan.Ready(out)
    }

    /**
     * One source's bytes, or the refusal its read earns, handed to [refuse] --
     * which every caller makes a non-local `return` out of [plan].
     */
    private inline fun readOrRefuse(
        index: Int,
        source: Source,
        read: (Int) -> ImageShrinkPolicy.PartRead,
        refuse: (Plan.Refused) -> Nothing,
    ): ByteArray {
        val bytes = when (val outcome = read(index)) {
            is ImageShrinkPolicy.PartRead.Ok -> outcome.bytes
            // The reader hit SOURCE_MAX_BYTES on a source that declared
            // nothing. Same refusal as the declared-length gate in [plan].
            ImageShrinkPolicy.PartRead.TooLarge ->
                refuse(Plan.Refused(oversizeSourceReason(source.declaredSize.toLong())))
            // Revoked URI, deleted file, a cloud provider that will not
            // download. Deterministic and worth naming: the composer would
            // meet the same failure and report it as a bare false.
            ImageShrinkPolicy.PartRead.Failed -> refuse(Plan.Refused(UNREADABLE_REASON))
        }
        if (bytes.isEmpty()) refuse(Plan.Refused(UNREADABLE_REASON))
        return bytes
    }

    /**
     * The name this attachment travels under.
     *
     * Minted here rather than taken from the picker's display name, which is
     * usually absent and, when present, is a user-chosen string of arbitrary
     * length and script. [MmsPduComposer] writes the name twice per part -- the
     * Content-Type name parameter and the Content-Location -- and
     * [MmsAttachmentBudget]'s 96-byte per-part estimate is sized for the short
     * ASCII names this app generates, with the explicit note that a caller
     * forwarding a user-chosen name should shorten it rather than lean on that
     * margin. A minted name also cannot disagree with the bytes: the extension
     * always follows the type the attachment actually ends up as, so a HEIC
     * re-encoded to JPEG never arrives as a `.heic` a viewer refuses to open.
     */
    fun attachmentName(index: Int, contentType: String): String =
        "photo-${index + 1}.${extensionFor(contentType)}"

    private fun attachment(index: Int, contentType: String, bytes: ByteArray): RelayAttachment =
        RelayAttachment(
            name = attachmentName(index, contentType),
            contentType = contentType,
            data = RelayContentCodec.encodeBytes(bytes),
            size = bytes.size,
        )

    /**
     * Only `image/jpeg` needs a table -- its subtype is not its extension. Every
     * other type this path can produce (png, gif, webp, heic) names itself, and
     * the filter keeps a parameterised or malformed type from writing punctuation
     * into a file name.
     */
    private fun extensionFor(contentType: String): String {
        val media = mediaType(contentType)
        if (media == "image/jpeg" || media == "image/jpg") return "jpg"
        val subtype = media.substringAfter('/', "").filter { it.isLetterOrDigit() }.take(8)
        return subtype.ifBlank { "bin" }
    }

    /** Media type alone: a `; charset=` or `; name=` parameter never classifies a part. */
    private fun mediaType(value: String?): String =
        value.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)

    private val TOO_MANY_REASON =
        "사진은 한 번에 ${RelayContentCodec.MAX_ATTACHMENTS}장까지 보낼 수 있습니다"

    private const val UNREADABLE_REASON = "첨부 파일을 읽지 못해 보내지 않았습니다"

    private const val NO_ROOM_REASON =
        "메시지 내용이 너무 길어 사진을 첨부할 수 없습니다. 글을 줄이고 다시 시도하세요"

    private fun oversizeSourceReason(declaredSize: Long): String {
        val limit = SOURCE_MAX_BYTES / (1024 * 1024)
        // Braced, and it has to be: Hangul is a letter to the Kotlin lexer, so a
        // bare `$measured너무` is read as one identifier named `measured너무`.
        val measured = if (declaredSize > 0) "${mib(declaredSize)}MB 파일은" else "이 파일은"
        return "${measured} 너무 커서 첨부할 수 없습니다. ${limit}MB 이하만 보낼 수 있습니다"
    }

    private fun unshrinkableReason(sourceBytes: Long, budget: Int): String =
        "첨부 ${kib(sourceBytes)}KB가 한도 ${kib(budget.toLong())}KB를 넘습니다. " +
            "사진이 아닌 첨부는 용량을 줄일 수 없어 보내지 않았습니다"

    private fun shrinkFailedReason(sourceBytes: Long, budget: Int): String =
        "사진 ${kib(sourceBytes)}KB를 한도 ${kib(budget.toLong())}KB까지 줄이지 못해 " +
            "보내지 않았습니다. 장수를 줄이고 다시 시도하세요"

    /** Rounded up, so a reason never reports a limit larger than the real one. */
    private fun kib(bytes: Long): Long = (bytes + 1023) / 1024

    private fun mib(bytes: Long): Long = (bytes + 1024 * 1024 - 1) / (1024 * 1024)
}
