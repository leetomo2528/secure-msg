package com.yunjelee.securemsg

import java.util.Locale

/**
 * One line of Korean body text standing in for MMS parts the other devices
 * will never receive — a video too large for the relay, an image that will not
 * decode.
 *
 * The notice rides inside the existing `text` field of [RelayContent] instead
 * of a new key on purpose: every decoder already deployed ignores an unknown
 * key, so an additive field would show nothing at all on the web client until
 * the last device had updated, while text already renders in the bubble, the
 * conversation-list preview and the notification with no client change. Not
 * showing anything is the one outcome that is never acceptable: the user has
 * no other copy of the message, this being the default SMS app.
 */
object IncomingOmissionNotice {
    /**
     * Ceiling [RelayContentCodec.encode] enforces on `text`. Crossing it throws
     * and takes the whole message down with it, so the notice is what gets cut.
     */
    const val MAX_TEXT_CHARS = 20_000

    enum class Kind { IMAGE, VIDEO, AUDIO, FILE }

    /**
     * One part that will not reach the other devices.
     *
     * [bytes] is the measured size of the part, and null when the part was lost
     * for any other reason. The wording turns on it because 용량이 커서 is a
     * claim about the cause, and that claim may only be made about a part that
     * was actually weighed.
     */
    data class Omission(val kind: Kind, val bytes: Int? = null)

    /**
     * The omission for a dropped part, or null when losing that part omits
     * nothing the user could have seen.
     *
     * A smil part is the layout script of the MMS and is never rendered by
     * anything in this app, so announcing its loss would be pure noise about a
     * message that arrived complete. A text part never reaches this path at all
     * — [MmsProvider] folds it into the body before the attachment loop — so it
     * cannot be omitted either.
     */
    fun omissionFor(contentType: String?, bytes: Int? = null): Omission? {
        val mediaType = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        val subtype = mediaType.substringAfter('/', "")
        if (mediaType.startsWith("text/") || subtype.startsWith("smil")) return null
        val kind = when {
            mediaType.startsWith("image/") -> Kind.IMAGE
            mediaType.startsWith("video/") -> Kind.VIDEO
            mediaType.startsWith("audio/") -> Kind.AUDIO
            else -> Kind.FILE
        }
        // A negative size is not a measurement; it must not license 용량이 커서.
        return Omission(kind, bytes?.takeIf { it >= 0 })
    }

    /**
     * [body] with at most one bracketed notice line appended, or [body]
     * untouched when nothing was omitted.
     *
     * The notice is only ever added after the body: the carrier text is the
     * part of the message the user most needs, and it is never traded for an
     * explanation of what is missing. That is also why the truncation is a
     * plain cut of the joined result — a body already at the ceiling keeps all
     * of itself and loses the notice, rather than the reverse.
     */
    fun appendTo(body: String, omissions: List<Omission>): String {
        val notice = notice(omissions) ?: return body
        val joined = if (body.isEmpty()) notice else "$body\n$notice"
        return joined.take(MAX_TEXT_CHARS)
    }

    private fun notice(omissions: List<Omission>): String? {
        if (omissions.isEmpty()) return null
        // Declaration order, not the order the parts were read: the encoded
        // content is the preimage of the relay dedupe fingerprint, so the same
        // MMS seen twice (live broadcast, then provider sweep) must produce a
        // byte-identical notice or it relays as two separate messages.
        val phrases = Kind.entries.mapNotNull { kind ->
            val group = omissions.filter { it.kind == kind }
            if (group.isEmpty()) return@mapNotNull null
            // One unweighed part in the group sinks the cause claim for all of
            // them; the count is the only thing the line can still assert.
            phrase(kind, group.size, group.all { it.bytes != null })
        }
        return phrases.joinToString(", ", prefix = "[", postfix = "]")
    }

    private fun phrase(kind: Kind, count: Int, measured: Boolean): String {
        val cause = if (measured) "용량이 커서 " else ""
        return "${label(kind)} $count${counter(kind)} ${cause}받지 못했습니다"
    }

    private fun label(kind: Kind): String = when (kind) {
        Kind.IMAGE -> "사진"
        Kind.VIDEO -> "동영상"
        Kind.AUDIO -> "음성"
        Kind.FILE -> "파일"
    }

    /**
     * Counter plus topic particle. The particle follows the counter, not the
     * noun: 장 ends in a consonant and takes 은, 개 ends in a vowel and takes
     * 는. The wrong one reads as machine translation to every Korean user.
     */
    private fun counter(kind: Kind): String = if (kind == Kind.IMAGE) "장은" else "개는"
}
