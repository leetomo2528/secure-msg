package com.yunjelee.securemsg.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import android.util.LruCache
import java.nio.ByteBuffer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yunjelee.securemsg.ImageShrinkPolicy
import com.yunjelee.securemsg.RelayContentCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pictures and files of one chat bubble.
 *
 * Everything below the composables is about one thing: this is the app's main
 * scrolling screen, and `messages.attachmentsJson` is up to 683 KiB of base64
 * per row. Parsing that string, base64-decoding it and decoding a bitmap out
 * of it are all far past a frame's budget, so none of them ever runs on the
 * composition thread and none of them runs twice for the same picture -- see
 * [AttachmentSummaries] and [AttachmentBitmaps], whose bounds are set by
 * [MessageAttachmentPolicy].
 */

// ---------------------------------------------------------------------------
// Caches
// ---------------------------------------------------------------------------

/**
 * Layout facts per message, so that scrolling a picture back into view costs a
 * map lookup rather than a JSON parse.
 *
 * Bounded by entry count rather than bytes because an [AttachmentSummary]
 * holds no payload at all -- at most eight of them per message, each a handful
 * of short strings. [MessageAttachmentPolicy.SUMMARY_CACHE_ENTRIES] of those is
 * a few hundred kilobytes for a conversation longer than anyone scrolls in one
 * sitting.
 *
 * Keyed by message id *and* the length of the column, so the one path that
 * rewrites attachmentsJson in place cannot be served a stale summary.
 */
private object AttachmentSummaries {
    private val cache = LruCache<String, List<AttachmentSummary>>(
        MessageAttachmentPolicy.SUMMARY_CACHE_ENTRIES,
    )

    private fun key(messageId: Long, json: String): String = "$messageId:${json.length}"

    /** The cached summaries, or null when this message has not been read yet. Never parses. */
    fun peek(messageId: Long, json: String?): List<AttachmentSummary>? {
        if (json.isNullOrBlank()) return emptyList()
        return cache.get(key(messageId, json))
    }

    /** Parses and caches. Call from a background dispatcher only. */
    fun load(messageId: Long, json: String?): List<AttachmentSummary> {
        if (json.isNullOrBlank()) return emptyList()
        val summaries = MessageAttachmentPolicy.summarize(messageId, json)
        cache.put(key(messageId, json), summaries)
        return summaries
    }
}

/**
 * Decoded pictures, bounded in bytes by
 * [MessageAttachmentPolicy.cacheBudgetBytes] -- roughly seven photos, however
 * long the thread is.
 *
 * Nothing here recycles an evicted bitmap, and that is the important part. A
 * bitmap leaves this cache because a *newer* one needed the room, not because
 * it stopped being drawn: a picture that has just scrolled a little way up the
 * list is still composed and still on its way through the draw pass. Recycling
 * one is the "Canvas: trying to use a recycled bitmap" crash, on the main
 * screen of an app that installs itself unattended. Eviction drops the last
 * strong reference and the collector does the rest once the draw pass has
 * finished with it, which is both correct and free.
 */
private object AttachmentBitmaps {
    private const val TAG = "AttachmentBitmaps"

    private val cache = object : LruCache<String, Bitmap>(
        MessageAttachmentPolicy.cacheBudgetBytes(Runtime.getRuntime().maxMemory()),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** The cached bitmap, or null. Never decodes, so it is safe on the composition thread. */
    fun peek(key: String): Bitmap? = cache.get(key)

    /**
     * The picture at [index] of [json], decoded and cached.
     *
     * Re-parses [json] instead of being handed a [ShownAttachment]: holding one
     * of those across a composition would pin the base64 payload in memory for
     * every row on screen, which is the cost [AttachmentSummary] exists to
     * avoid. The parse is a few milliseconds against a bitmap decode of tens,
     * and both are on a background dispatcher.
     *
     * Call from a background dispatcher only. Never throws -- Throwable, not
     * Exception, for the same reason [ImageShrinker] catches it: a
     * multi-megapixel decode is the largest allocation this screen makes and
     * OutOfMemoryError is an Error.
     */
    fun load(key: String, json: String?, index: Int): Bitmap? {
        cache.get(key)?.let { return it }
        return try {
            val row = MessageAttachmentPolicy.parse(json).getOrNull(index) ?: return null
            val bitmap = decodeBounded(RelayContentCodec.decodeBytes(row.data)) ?: return null
            cache.put(key, bitmap)
            bitmap
        } catch (t: Throwable) {
            // No name, no bytes, no dimensions: this is the default SMS app and
            // logcat is readable by the user's other tooling.
            Log.w(TAG, "attachment decode failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * [bytes] decoded with its long edge held at or under
     * [MessageAttachmentPolicy.INLINE_MAX_EDGE].
     *
     * ImageDecoder with an explicit target size, not BitmapFactory with a
     * sample size. Two reasons, and both were real bugs before this:
     *
     * Subsampling alone does not bound anything here. [ImageShrinkPolicy.sampleSizeFor]
     * returns a power of two that deliberately OVERSHOOTS the target -- its
     * other caller follows it with setTargetSize to land exactly -- so a source
     * under twice the target gets sample size 1. Relayed photos come off a
     * ladder whose top rung is 1600px, so in practice every one of them decoded
     * at full resolution: 7.3 MiB for a 1600x1200 instead of the ~3 MiB the
     * cache ceiling is sized against, leaving room for two photos where the
     * budget assumed seven, and re-decoding the rest on every scroll.
     *
     * And BitmapFactory ignores the JPEG EXIF orientation tag. A photo that
     * travelled byte for byte because it already fitted still carries one, so
     * it rendered sideways here while the web client, the recipient's handset
     * and this app's own composer thumbnail all showed it upright. ImageDecoder
     * applies the rotation, which is why every other decode in this app already
     * uses it.
     *
     * An animated GIF arrives as its first frame; the alternative is refusing
     * to show a GIF the web client displays.
     */
    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
            ) { decoder, info, _ ->
                val target = ImageShrinkPolicy.targetSize(
                    info.size.width,
                    info.size.height,
                    MessageAttachmentPolicy.INLINE_MAX_EDGE,
                )
                decoder.setTargetSize(target.width, target.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                // A truncated attachment is a picture this device cannot show,
                // not a reason to throw inside a background decode.
                decoder.setOnPartialImageListener { false }
            }
        } catch (t: Throwable) {
            // Includes OutOfMemoryError: a malformed header claiming enormous
            // dimensions must degrade to "이 사진을 열 수 없습니다", never take
            // the message list down with it.
            Log.w(TAG, "inline attachment decode failed: ${t.javaClass.simpleName}")
            null
        }
    }
}

// ---------------------------------------------------------------------------
// Bubble
// ---------------------------------------------------------------------------

/** Thumbnail height. Fixed, not measured -- see [AttachmentImage]. */
private val ThumbHeight = 200.dp

private val AttachmentShape = RoundedCornerShape(14.dp)
private val ChipShape = RoundedCornerShape(999.dp)

/**
 * The attachments of one message, above its bubble and aligned with it.
 *
 * Sits outside [ChatBubble] rather than inside it because a picture is not
 * bubble chrome: an MMS whose only content is a photo has an empty body, and
 * the bubble below then carries the timestamp alone, which is what it is for.
 * The 78%-wide box and the mine/theirs alignment are copied from [ChatBubble]
 * so the two read as one message.
 *
 * Renders nothing at all -- no spinner, no box -- until the column has been
 * parsed off the main thread. A blocked message must not be given one of
 * these; its bytes are exactly what the user asked not to see.
 */
@Composable
fun MessageAttachments(
    messageId: Long,
    attachmentsJson: String?,
    mine: Boolean,
    modifier: Modifier = Modifier,
) {
    if (attachmentsJson.isNullOrBlank()) return
    // Keyed on the column's length rather than the column, which is the same
    // identity [AttachmentSummaries] caches under: a 683 KiB string as a
    // remember key is an O(n) equals on every recomposition, and Room hands out
    // a fresh MessageRow -- so a fresh String -- on every emission, which is
    // exactly when the reference check would stop short-circuiting it.
    //
    // Seeded from the cache so a picture scrolled back into view is laid out in
    // the same frame; only a message being read for the first time waits. Keyed
    // rather than bare, so a column rewritten under a row drops the old
    // summaries instead of laying the new bytes out to the old shape.
    var summaries by remember(messageId, attachmentsJson.length) {
        mutableStateOf(AttachmentSummaries.peek(messageId, attachmentsJson))
    }
    LaunchedEffect(messageId, attachmentsJson.length) {
        if (summaries == null) {
            summaries = withContext(Dispatchers.Default) {
                AttachmentSummaries.load(messageId, attachmentsJson)
            }
        }
    }
    val items = summaries ?: return
    if (items.isEmpty()) return

    var viewing by remember(messageId) { mutableStateOf<AttachmentSummary?>(null) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(0.78f),
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Column(
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    if (item.inlineImage) {
                        AttachmentImage(
                            summary = item,
                            attachmentsJson = attachmentsJson,
                            onOpen = { viewing = item },
                        )
                    } else {
                        AttachmentChip(item.label)
                    }
                }
            }
        }
    }

    viewing?.let { target ->
        AttachmentViewer(
            summary = target,
            attachmentsJson = attachmentsJson,
            onDismiss = { viewing = null },
        )
    }
}

/**
 * One picture, at a fixed [ThumbHeight] and cropped to it.
 *
 * Fixed rather than sized from the decoded bitmap so that the row never
 * changes height. The chat list is `reverseLayout`, anchored at the bottom: a
 * row that grows when its picture finishes decoding shoves everything the user
 * is reading up the screen, and it would do it once per photo on the first
 * pass through a thread. Cropping costs part of a tall photo in the thumbnail
 * and the whole of it is one tap away in [AttachmentViewer], which is the
 * cheaper half of the trade.
 *
 * A picture that will not decode -- a truncated payload, a type the platform
 * cannot read, a heap that said no -- keeps the same box and says so, because
 * silence here looks exactly like a message that had no photo in it.
 */
@Composable
private fun AttachmentImage(
    summary: AttachmentSummary,
    attachmentsJson: String?,
    onOpen: () -> Unit,
) {
    val state = rememberAttachmentBitmap(summary, attachmentsJson)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ThumbHeight)
            .clip(AttachmentShape)
            .background(Sm.surfaceAlt)
            .border(1.dp, Sm.ink.copy(alpha = 0.06f), AttachmentShape)
            .then(
                if (state.bitmap != null) {
                    Modifier.clickable(role = Role.Button, onClick = onOpen)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val image = state.bitmap
        when {
            image != null -> Image(
                bitmap = image.asImageBitmap(),
                contentDescription = summary.description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            !state.settled -> Text("사진 불러오는 중…", color = Sm.text4, fontSize = 12.sp)
            else -> Text("사진을 열 수 없습니다", color = Sm.text3, fontSize = 12.sp)
        }
    }
}

/** Anything that is not a picture: named, sized, and not pretending to be openable. */
@Composable
private fun AttachmentChip(label: String) {
    Row(
        modifier = Modifier
            .clip(ChipShape)
            .background(Sm.surface.copy(alpha = 0.92f))
            .border(1.dp, Sm.ink.copy(alpha = 0.08f), ChipShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SmIcon(SmIconKind.Paperclip, size = 13.dp, tint = Sm.text3, strokeWidth = 1.8.dp)
        Text(
            label,
            color = Sm.text3,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The whole picture, over the whole screen.
 *
 * A [Dialog] rather than a pane of this screen's own state, which is what the
 * rest of the app already reaches for ([SmConfirmDialog]): it comes with the
 * back gesture wired to [onDismiss], so the chat screen's existing BackHandler
 * -- which already arbitrates between the composer, the search pill and the
 * conversation -- gains no fourth case to get wrong.
 *
 * Shows the same cached bitmap the thumbnail drew, at
 * [MessageAttachmentPolicy.INLINE_MAX_EDGE], scaled to fit instead of cropped.
 * Re-decoding it at full resolution would be a second copy of a photo already
 * in memory for a difference no phone screen resolves.
 */
@Composable
private fun AttachmentViewer(
    summary: AttachmentSummary,
    attachmentsJson: String?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val state = rememberAttachmentBitmap(summary, attachmentsJson)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                // No ripple: a splash across a full-screen photo reads as a
                // rendering fault, not as a control.
                // Labelled on the action rather than the node: a
                // contentDescription here would be read *instead of* the
                // photo's own, which is the one thing on this screen.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "사진 닫기",
                    role = Role.Button,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val image = state.bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = summary.description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            } else if (state.settled) {
                Text("사진을 열 수 없습니다", color = Sm.onAccent, fontSize = 13.sp)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .size(width = 64.dp, height = 36.dp)
                    .clip(ChipShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("닫기", color = Sm.onAccent, fontSize = 13.sp)
            }
        }
    }
}

/**
 * One picture's decode, off the composition thread and at most once.
 *
 * [AttachmentBitmapState.settled] is what separates "still decoding" from
 * "decoded to nothing": the two render differently, and a bare nullable bitmap
 * cannot tell them apart.
 *
 * A plain keyed remember rather than produceState, because produceState holds
 * its state in an *unkeyed* remember -- a row whose attachment changed would
 * keep showing the previous photo until the new one finished decoding, and
 * would keep showing it forever if the new one failed.
 */
@Composable
private fun rememberAttachmentBitmap(
    summary: AttachmentSummary,
    attachmentsJson: String?,
): AttachmentBitmapState {
    var state by remember(summary.key) {
        val cached = AttachmentBitmaps.peek(summary.key)
        mutableStateOf(AttachmentBitmapState(cached, settled = cached != null))
    }
    // The key alone: it already names the message, the slot and the byte count,
    // and [attachmentsJson] is only the source the bytes are re-read from on a
    // miss, never part of this picture's identity.
    LaunchedEffect(summary.key) {
        if (state.bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            AttachmentBitmaps.load(summary.key, attachmentsJson, summary.index)
        }
        // Cancelled with the row leaving the list: withContext never returns
        // and nothing is assigned to a composition that is already gone.
        state = AttachmentBitmapState(decoded, settled = true)
    }
    return state
}

/** A decode in one of its three states: pending, drawn, or failed. */
private class AttachmentBitmapState(val bitmap: Bitmap?, val settled: Boolean)