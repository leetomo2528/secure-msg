package com.yunjelee.securemsg.ui

import com.yunjelee.securemsg.ImageShrinkPolicy
import com.yunjelee.securemsg.RelayContentCodec
import org.json.JSONArray
import java.util.Locale

/**
 * One attachment row as the chat bubble shows it.
 *
 * The same four fields [RelayContentCodec.attachmentsJson] writes, and nothing
 * derived: `data` is still the base64url payload, so a list of these is as
 * heavy as the column it came from and must never be cached. What the renderer
 * keeps between scrolls is [AttachmentSummary], which drops the bytes.
 */
data class ShownAttachment(
    val name: String,
    val contentType: String,
    val data: String,
    val size: Int,
)

/**
 * Everything the bubble needs to lay one attachment out before its bytes have
 * been touched: whether it is a picture or a chip, what the chip says, and the
 * key its decoded bitmap is cached under.
 *
 * Deliberately free of [ShownAttachment.data] so that a cache of these costs a
 * few hundred bytes per message instead of the 683 KiB of base64 a full
 * 512 KiB message carries.
 */
data class AttachmentSummary(
    val key: String,
    val index: Int,
    val inlineImage: Boolean,
    val label: String,
    val description: String,
)

/**
 * Every decision the chat bubble makes about `messages.attachmentsJson`:
 * what parses, what is drawn as a picture, what it is called, and how much
 * memory decoded pictures may hold.
 *
 * Free of every android.* type on purpose, the same way [ImageShrinkPolicy] is
 * -- the bubble is the app's main scrolling screen and the cheapest place to
 * catch a mistake in any of this is the host suite, not a phone. The framework
 * half (BitmapFactory, LruCache, Compose) is in MessageAttachments.kt and holds
 * no rules of its own.
 */
object MessageAttachmentPolicy {
    /**
     * Long-edge cap for a decoded preview, in pixels.
     *
     * A relayed attachment has already been walked down
     * [ImageShrinkPolicy.SHRINK_RUNGS] to at most 1600 px, but decoding one at
     * full size costs 1600x1200x4 = 7.3 MB of heap for a picture that is drawn
     * into a 200 dp box and, at most, a phone screen. 1024 is a rung of that
     * same ladder, still above the widest phone in portrait, and a third of the
     * memory.
     */
    const val INLINE_MAX_EDGE = 1024

    /** Lower bound of [cacheBudgetBytes]: two decoded photos, on the smallest heap. */
    const val CACHE_FLOOR_BYTES = 4 * 1024 * 1024

    /** Upper bound of [cacheBudgetBytes] -- see there for why it is not larger. */
    const val CACHE_CEILING_BYTES = 24 * 1024 * 1024

    /** How many summaries the renderer keeps. See [AttachmentSummary] for what one weighs. */
    const val SUMMARY_CACHE_ENTRIES = 256

    /**
     * Rows [parse] will look at before giving up, however many it accepted.
     *
     * Mirrors the same guard in [RelayContentCodec.decode]: a column holding a
     * thousand junk rows must cost a bounded amount of work on a list that is
     * being flung, not a loop proportional to whatever was written.
     */
    private const val MAX_ROWS_INSPECTED = 64

    private const val MAX_NAME_CHARS = 120
    private const val MAX_TYPE_CHARS = 120
    private const val DEFAULT_TYPE = "application/octet-stream"

    /**
     * Types drawn as a picture rather than named as a file.
     *
     * A fixed whitelist, not a wildcard over the `image` type: these bytes come from
     * whoever sent the message, and a wildcard would let a sender name an
     * exotic type whose decode behaviour nobody here has considered. Identical
     * to `isInlineImage` in frontend/src/store/helpers.ts, down to accepting
     * the non-IANA `image/jpg` that real gateways emit, so the same photo is a
     * photo on the phone and on the web.
     *
     * Wider than [ImageShrinkPolicy.isShrinkable] at one end and narrower at
     * the other, and neither is a mistake: GIF is displayable but not
     * re-encodable, HEIC is re-encodable but not something the web can draw.
     * The two lists answer different questions and must not be merged.
     */
    // The list itself lives in ImageShrinkPolicy: the send path needs the same
    // answer, and a second copy here is how the two drift.

    /**
     * The rows of [json], or an empty list.
     *
     * Never throws and never reports a failure: this runs for every row of the
     * conversation list's chat and a column that will not parse has to render
     * as a message with no attachments, not take the screen down with it. The
     * column is written by three paths ([IncomingMessageRepository] and two in
     * [SmsBridgeService]) and rewritten in place when the relay assigns a seq,
     * so "malformed" includes "written by a build that no longer exists".
     *
     * Rows are read, not trusted. A row is skipped rather than repaired when
     * anything about it is wrong, because a repaired row is a claim about a
     * file the user cannot check against a second copy.
     *
     * What this does NOT do is verify that `data` is valid base64 of exactly
     * `size` bytes, which [RelayContentCodec.decode] does on the wire path. A
     * 512 KiB attachment is 683 KiB of base64 and validating it means decoding
     * it; doing that here would put half a megabyte of work on the composition
     * thread per row. A payload that lies is caught by the decoder in
     * [AttachmentSummary]'s framework half, one frame later and off the main
     * thread, and shows as an unreadable picture.
     */
    fun parse(json: String?): List<ShownAttachment> {
        if (json.isNullOrBlank()) return emptyList()
        val rows = try {
            JSONArray(json)
        } catch (_: Throwable) {
            return emptyList()
        }
        val out = ArrayList<ShownAttachment>(minOf(rows.length(), RelayContentCodec.MAX_ATTACHMENTS))
        var index = 0
        var inspected = 0
        while (
            index < rows.length() &&
            out.size < RelayContentCodec.MAX_ATTACHMENTS &&
            inspected < MAX_ROWS_INSPECTED
        ) {
            val row = rows.optJSONObject(index)
            index += 1
            inspected += 1
            if (row == null) continue
            // isNull() rather than optString()'s fallback: over a JSON null the
            // platform's org.json returns the *string* "null" while the host
            // artifact honours the fallback, and a rule that reads differently
            // on the phone than in this suite is worse than no rule.
            if (row.isNull("data")) continue
            val data = row.optString("data")
            if (data.isBlank()) continue
            val size = if (row.isNull("size")) -1 else row.optInt("size", -1)
            // A size past the wire cap did not come from this app's encoder,
            // and a negative one is not a measurement.
            if (size < 0 || size > RelayContentCodec.MAX_ATTACHMENT_BYTES) continue
            val contentType = if (row.isNull("content_type")) "" else row.optString("content_type")
            val name = if (row.isNull("name")) "" else row.optString("name")
            out += ShownAttachment(
                name = name.take(MAX_NAME_CHARS),
                contentType = contentType.take(MAX_TYPE_CHARS).trim().ifBlank { DEFAULT_TYPE },
                data = data,
                size = size,
            )
        }
        return out
    }

    /** The rows of [json] reduced to what layout needs, with their bytes dropped. */
    fun summarize(messageId: Long, json: String?): List<AttachmentSummary> =
        parse(json).mapIndexed { index, item ->
            AttachmentSummary(
                key = cacheKey(messageId, index, item),
                index = index,
                inlineImage = isInlineImage(item.contentType),
                label = chipLabel(item),
                description = imageDescription(item),
            )
        }

    /** Whether [contentType] is drawn as a picture. */
    fun isInlineImage(contentType: String?): Boolean = ImageShrinkPolicy.isInlineRenderable(contentType)

    /**
     * Cache identity of one decoded picture.
     *
     * The message id and the slot within it are what actually identify an
     * attachment -- the column is written once when the row is inserted -- and
     * the size is folded in so that the one path that rewrites it
     * ([MessageDao.updateRelayResult], when the relay assigns a seq) cannot leave the
     * previous photo on screen under the new row's key.
     *
     * Deliberately not a hash of `data`: that is a pass over 683 KiB of base64
     * on the composition thread, which is the cost this whole file exists to
     * avoid.
     */
    fun cacheKey(messageId: Long, index: Int, attachment: ShownAttachment): String =
        "$messageId:$index:${attachment.size}"

    /** Chip text for something that is not a picture: `보고서.pdf · 12KB`. */
    fun chipLabel(attachment: ShownAttachment): String =
        "${displayName(attachment)} · ${sizeLabel(attachment.size)}"

    /** What a screen reader calls the picture. */
    fun imageDescription(attachment: ShownAttachment): String =
        attachment.name.trim().ifBlank { "사진" }

    /** A file name, or the neutral Korean noun when the sender supplied none. */
    fun displayName(attachment: ShownAttachment): String =
        attachment.name.trim().ifBlank { "첨부파일" }

    /** `840B` / `12KB` / `1.4MB`. Rounded up, so nothing ever reads as `0KB`. */
    fun sizeLabel(bytes: Int): String {
        if (bytes < 0) return "크기 알 수 없음"
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1024 * 1024) return "${(bytes + 1023) / 1024}KB"
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0))
    }

    /**
     * Bytes of decoded pictures the bubble may hold, given a heap of
     * [maxMemoryBytes].
     *
     * An eighth of the heap, never below [CACHE_FLOOR_BYTES] and never above
     * [CACHE_CEILING_BYTES]. The ceiling is the number that matters: at
     * [INLINE_MAX_EDGE] a 4:3 photo decodes to 1024x768x4 = 3.1 MB, so 24 MiB
     * is roughly seven photos held at once. A thread of a hundred photos
     * therefore costs the same as a thread of seven, which is the entire point
     * -- this app is the device's default SMS app and its heap is shared with a
     * foreground relay service, an MMS download and a Room database.
     *
     * The floor exists because a cache that holds less than the picture
     * currently on screen is a cache that decodes it again on every scroll.
     */
    fun cacheBudgetBytes(maxMemoryBytes: Long): Int =
        (maxMemoryBytes / 8)
            .coerceIn(CACHE_FLOOR_BYTES.toLong(), CACHE_CEILING_BYTES.toLong())
            .toInt()

    /** Media type alone: a `; charset=` or `; name=` parameter never classifies a part. */
    private fun mediaType(value: String?): String =
        value.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
}
