package com.yunjelee.securemsg.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.ImageShrinkPolicy
import com.yunjelee.securemsg.RelayContentCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// The composer's side of sending a photo: what is staged, what is refused,
// which send a tap on 보내기 runs. Every one of those decisions is a pure
// function in [PhotoStaging] so it runs in the host suite; the Compose code
// below only picks, draws and removes.
// ---------------------------------------------------------------------------

private const val TAG = "ComposerAttachments"

/**
 * What an MMS send reported back to the composer.
 *
 * The UI-facing shape, deliberately not the dispatcher's own result type, so
 * nothing in `ui/` knows about Room or the carrier. It still keeps the one
 * distinction the composer acts on — whether a message row was written —
 * because that decides whether the number-entry composer may move into the
 * thread or must keep the draft (see [SendResultPolicy]). MainActivity adapts
 * the dispatcher's result into this in one expression.
 */
sealed interface MmsSendOutcome {
    /** Handed to the carrier and recorded for the relay, exactly like an SMS. */
    data object Sent : MmsSendOutcome

    /**
     * Nothing was written: no row, no outbox entry, no carrier call. [message]
     * is Korean and is shown above the composer, whose draft stays put.
     */
    data class Refused(val message: String) : MmsSendOutcome

    /**
     * A row was written and then failed; it carries its own failed badge in
     * the thread. [message] is Korean and is shown above the composer.
     */
    data class Failed(val message: String) : MmsSendOutcome
}

/**
 * The send the composer calls when pictures are staged.
 *
 * Null at the [MessagesPane] call site means the host has no MMS path wired,
 * and the paperclip is then not drawn at all — a button that can only ever
 * fail is worse than no button.
 */
typealias SendPhotoMessage =
    suspend (phone: String, text: String, photos: List<Uri>) -> MmsSendOutcome

/**
 * One picked picture, waiting above the input.
 *
 * [key] is `uri.toString()` taken once at pick time: it is both the list key
 * and the identity two picks are de-duplicated on, and recomputing it per
 * frame for every thumbnail would be the only work this row does.
 */
@Immutable
data class StagedPhoto(val uri: Uri, val key: String)

/** Which send a tap on 보내기 runs, once there is anything to send. */
sealed interface SendPlan {
    /** Text only — the SMS path that has always been here. */
    data object Text : SendPlan

    /** Caption plus pictures, over MMS. */
    data object Photos : SendPlan

    /** Nothing is dispatched; [message] is the Korean line to show. */
    data class Refused(val message: String) : SendPlan
}

/**
 * Staging rules and the send decision, as pure functions.
 *
 * [MAX] is read from [RelayContentCodec] rather than restated: the cap is
 * mirrored byte-for-byte in the web client, and a second literal here would be
 * the copy that drifts.
 */
object PhotoStaging {
    /** Pictures one message may carry. Never raised here — see RelayContentCodec. */
    const val MAX = RelayContentCodec.MAX_ATTACHMENTS

    /** Long edge a staged thumbnail is decoded to, in pixels. */
    internal const val THUMBNAIL_EDGE_PX = 256

    const val FULL_NOTICE = "사진은 최대 ${MAX}장까지 첨부할 수 있습니다."
    const val PICKER_UNAVAILABLE = "사진을 선택할 수 없습니다 — 기기의 사진 앱을 확인하세요."
    const val PHOTOS_UNSUPPORTED = "이 기기에서는 사진을 보낼 수 없습니다."
    const val PHOTO_SEND_FAILED = "사진 전송 실패 — 잠시 후 다시 시도하세요."

    /**
     * [staged] with [picked] appended, minus anything already staged and
     * anything past [MAX].
     *
     * Generic over the item so the rule is exercised on plain strings in the
     * host suite; the pane passes [StagedPhoto] and its `key`. Order is
     * pick order and the existing items keep their places, because the row is
     * what the user is about to send and re-ordering it under them would make
     * the remove buttons point somewhere else.
     */
    fun <T> merge(staged: List<T>, picked: List<T>, key: (T) -> String): List<T> {
        if (picked.isEmpty()) return staged
        val seen = HashSet<String>(staged.size + picked.size)
        val out = ArrayList<T>(minOf(MAX, staged.size + picked.size))
        for (item in staged) {
            if (out.size >= MAX) break
            if (seen.add(key(item))) out += item
        }
        for (item in picked) {
            if (out.size >= MAX) break
            if (seen.add(key(item))) out += item
        }
        return out
    }

    /**
     * The line a pick leaves above the composer, or null when every picture
     * the user chose was staged.
     *
     * Counted from what [merge] actually produced rather than from the cap
     * alone, so the two ways a picture can be dropped are told apart: the cap
     * names the limit, a duplicate says it was already there. A pick that lost
     * some of each is reported as the cap, which is the one the user has to act
     * on.
     */
    fun pickNotice(stagedBefore: Int, pickedCount: Int, stagedAfter: Int): String? {
        val dropped = stagedBefore + pickedCount - stagedAfter
        if (dropped <= 0) return null
        return if (stagedAfter >= MAX) {
            "사진은 최대 ${MAX}장까지 첨부할 수 있어 ${dropped}장을 제외했습니다."
        } else {
            "이미 첨부한 사진 ${dropped}장은 건너뛰었습니다."
        }
    }

    /** Count above the thumbnails. Blank-safe: callers hide the row at zero. */
    fun countLabel(staged: Int): String = "사진 ${staged.coerceAtLeast(0)}장 · 최대 ${MAX}장"

    /**
     * What 보내기 does, or null when there is nothing to send.
     *
     * Null and [SendPlan.Refused] are different answers on purpose: null is an
     * empty composer, which must stay silent, while a refusal is a staged
     * picture that will not be dispatched and has to say why.
     */
    fun plan(text: String, photoCount: Int, photosSupported: Boolean): SendPlan? = when {
        photoCount <= 0 -> if (text.isNotBlank()) SendPlan.Text else null
        !photosSupported -> SendPlan.Refused(PHOTOS_UNSUPPORTED)
        // merge() already holds the cap; this is the second lock on the door,
        // because the PDU composer's refusal would arrive as an opaque failure.
        photoCount > MAX -> SendPlan.Refused(FULL_NOTICE)
        else -> SendPlan.Photos
    }

    /**
     * The Korean line for a failed photo send.
     *
     * The dispatcher's own reason is preferred — it is the one that names the
     * carrier ceiling or the unreadable file — and [PHOTO_SEND_FAILED] covers a
     * result that came back with nothing to say, so a failure is never silent.
     */
    fun failureLine(message: String?): String =
        message?.takeIf { it.isNotBlank() } ?: PHOTO_SEND_FAILED
}

/**
 * Staged pictures above the input: a count, then a scrolling row of thumbnails
 * each with its own remove button.
 *
 * [enabled] goes false while a send is running, which is the same moment
 * [SmComposer] stops accepting taps — a picture removed mid-dispatch would be
 * sent anyway and the row would then be lying about what went out.
 */
@Composable
internal fun StagedPhotoRow(
    photos: List<StagedPhoto>,
    enabled: Boolean,
    onRemove: (StagedPhoto) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (photos.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(PhotoStaging.countLabel(photos.size), color = Sm.text4, fontSize = 11.sp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            photos.forEachIndexed { index, photo ->
                StagedPhotoThumbnail(
                    photo = photo,
                    // 1-based, and spoken: eight identical "사진 제거" buttons
                    // in a row are unusable with TalkBack, and the position is
                    // the only thing telling them apart.
                    position = index + 1,
                    enabled = enabled,
                    onRemove = { onRemove(photo) },
                )
            }
        }
    }
}

/** One 64dp thumbnail with its remove badge. */
@Composable
private fun StagedPhotoThumbnail(
    photo: StagedPhoto,
    position: Int,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val bitmap = rememberThumbnail(photo)
    Box(Modifier.size(64.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Sm.surfaceAlt)
                .border(1.dp, Sm.ink.copy(alpha = 0.08f), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "첨부한 사진 $position",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // The decode is off the main thread and can also simply fail
                // (a revoked grant, a file the decoder cannot read). The
                // placeholder keeps the count honest either way: the picture is
                // staged whether or not this app could draw it.
                Text("사진", color = Sm.text4, fontSize = 10.sp)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Sm.ink.copy(alpha = 0.62f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "사진 $position 제거" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "×",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The staged picture, decoded small, off the main thread.
 *
 * Keyed on the uri string rather than the [StagedPhoto]: the row recomposes on
 * every keystroke in the composer above it, and keying on an instance would
 * re-run a full decode each time the list is rebuilt.
 *
 * The suppression is for a lint false positive, not a real gap: the producer
 * below does assign `value`, yet ProduceStateDoesNotAssignValue still flags it
 * (also with the decode hoisted into a local, and without the explicit type
 * argument), and as an error it fails lintDebug and the whole build with it.
 */
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberThumbnail(photo: StagedPhoto): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, photo.key) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(context, photo.uri) }
    }
    return bitmap
}

/**
 * Subsampled decode of [uri], or null.
 *
 * Never throws, for the same reason [ImageShrinker] does not: this runs for
 * whatever the user picked, and a file the decoder chokes on must cost a grey
 * square, not the app. The sample size comes from [ImageShrinkPolicy] so a
 * 12 MP source never reaches the heap at full size.
 */
private fun decodeThumbnail(context: Context, uri: Uri): ImageBitmap? = try {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        decoder.setTargetSampleSize(
            ImageShrinkPolicy.sampleSizeFor(
                info.size.width,
                info.size.height,
                PhotoStaging.THUMBNAIL_EDGE_PX,
            ),
        )
    }.asImageBitmap()
} catch (e: Exception) {
    Log.w(TAG, "Thumbnail decode failed", e)
    null
} catch (e: OutOfMemoryError) {
    Log.w(TAG, "Thumbnail decode ran out of memory", e)
    null
}
