package com.yunjelee.securemsg

/**
 * Flattens a GitHub release body into plain text the update notification can
 * show.
 *
 * The body is Markdown written for a release page — `##` headings, `**bold**`,
 * bullets, backticks, links, fenced code. A notification renders none of that,
 * so an unprocessed body reaches the user as literal markers. Pure and
 * Android-free on purpose: the shade cannot be asserted on in a unit test,
 * this can.
 */
object UpdateNotes {
    /**
     * Bound on both the raw body kept in [UpdateInfo.notes] and the formatted
     * text handed to BigTextStyle — stripping only ever shortens, so one
     * number covers both ends.
     *
     * Sized for a notification, not a banner: the platform truncates every
     * notification CharSequence at 5120 chars (Notification.MAX_CHARSEQUENCE_LENGTH)
     * and the whole notification crosses a binder transaction as one parcel,
     * so this stays well under that while carrying a full multi-section
     * release body (v0.18.0's runs ~1.2k including the markers stripped here).
     */
    const val MAX_LENGTH = 2000

    private const val BULLET = "• "
    private const val ELLIPSIS = "…"

    private val FENCE = Regex("^\\s*(?:```|~~~)")
    private val RULE = Regex("^ {0,3}(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val HEADING = Regex("^ {0,3}#{1,6}\\s+")
    private val QUOTE = Regex("^ {0,3}>+\\s?")
    private val UNORDERED = Regex("^\\s*[-*+]\\s+")
    private val ORDERED = Regex("^\\s*\\d{1,9}[.)]\\s+")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val AUTOLINK = Regex("<((?:https?://|mailto:)[^>\\s]+)>")
    private val CODE = Regex("`+([^`]*)`+")
    private val STRIKE = Regex("~~(.+?)~~")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Regex("(?<!\\*)\\*(?!\\s)([^*\\n]+?)\\*")
    // Underscore emphasis is boundary-guarded: without it an identifier such as
    // KEY_PENDING_UPDATE, which release notes are full of, loses its middle.
    // (?U) is what extends that guard past ASCII: java.util.regex leaves \w as
    // [a-zA-Z0-9_] by default, so every Hangul neighbour satisfies both
    // lookarounds and 설정_파일_이름 — or a URL path — loses its underscores.
    private val BOLD_UNDER = Regex("(?U)(?<![\\w_])__(.+?)__(?!\\w)")
    private val ITALIC_UNDER = Regex("(?U)(?<![\\w_])_(?!\\s)([^_\\n]+?)_(?!\\w)")

    /**
     * Markdown in, notification text out. Empty when [raw] carries nothing
     * printable — the caller falls back to the bare title rather than posting
     * an expandable notification with nothing inside it.
     */
    fun format(raw: String, maxLength: Int = MAX_LENGTH): String {
        if (raw.isBlank() || maxLength <= 0) return ""
        val out = StringBuilder()
        var pendingBlank = false
        var inFence = false
        raw.replace("\r\n", "\n").replace('\r', '\n').split("\n").forEach { source ->
            if (FENCE.containsMatchIn(source)) {
                inFence = !inFence
                return@forEach
            }
            // Fenced content is code: it never carries markdown to strip, and
            // stripping it would eat the code's own punctuation.
            val line = if (inFence) source.trim() else strip(source)
            if (line.isEmpty()) {
                // A blank line survives only as a separator between kept
                // paragraphs, so leading blanks and runs collapse to nothing.
                if (out.isNotEmpty()) pendingBlank = true
                return@forEach
            }
            if (out.isNotEmpty()) out.append(if (pendingBlank) "\n\n" else "\n")
            pendingBlank = false
            out.append(line)
        }
        return clip(out.toString(), maxLength)
    }

    /**
     * Bounds a raw release body at persist time.
     *
     * Deliberately not [format]: the pending entry keeps the Markdown as
     * GitHub wrote it, and only the notification renders.
     */
    fun capRaw(body: String, maxLength: Int = MAX_LENGTH): String =
        if (body.length <= maxLength) {
            body
        } else {
            body.substring(0, MessageSearch.codePointEnd(body, maxLength))
        }

    private fun strip(source: String): String {
        if (RULE.matches(source)) return ""
        // The placeholder character is dropped from the input first, so a body
        // carrying one cannot be mistaken for a marker this function wrote and
        // have a code span's text spliced in where it never appeared.
        var line = QUOTE.replace(source.replace(CODE_PLACEHOLDER, ""), "")
        line = HEADING.replace(line, "")
        val unordered = UNORDERED.containsMatchIn(line)
        val bullet = unordered || ORDERED.containsMatchIn(line)
        if (bullet) {
            // Every list marker becomes the same bullet: nesting depth is
            // invisible in a notification, so indentation only wastes width.
            line = if (unordered) UNORDERED.replace(line, "") else ORDERED.replace(line, "")
        }
        // Images before links: the link pattern would otherwise match inside
        // an image and leave its "!" behind.
        line = IMAGE.replace(line) { it.groupValues[1] }
        line = LINK.replace(line) { it.groupValues[1] }
        line = AUTOLINK.replace(line) { it.groupValues[1] }
        // Code spans are lifted out before the emphasis passes and put back
        // after. Merely stripping their backticks first does the opposite of
        // protecting them: the freed characters become ordinary line content,
        // so a backticked `**` is read as emphasis and can even swallow the
        // opening marker of real bold later on the same line.
        val spans = mutableListOf<String>()
        line = CODE.replace(line) { match ->
            spans += match.groupValues[1]
            "$CODE_PLACEHOLDER${spans.size - 1}$CODE_PLACEHOLDER"
        }
        line = STRIKE.replace(line) { it.groupValues[1] }
        line = BOLD.replace(line) { it.groupValues[1] }
        line = ITALIC.replace(line) { it.groupValues[1] }
        line = BOLD_UNDER.replace(line) { it.groupValues[1] }
        line = ITALIC_UNDER.replace(line) { it.groupValues[1] }
        if (spans.isNotEmpty()) {
            line = PLACEHOLDER_RE.replace(line) { match ->
                spans.getOrElse(match.groupValues[1].toInt()) { "" }
            }
        }
        line = line.trim()
        return when {
            line.isEmpty() -> ""
            bullet -> BULLET + line
            else -> line
        }
    }

    /** Private-use code point, stripped from the input before it is used as a
     * marker: nothing a release body contains can then forge one. */
    private const val CODE_PLACEHOLDER = "\uE000"
    private val PLACEHOLDER_RE = Regex("$CODE_PLACEHOLDER(\\d+)$CODE_PLACEHOLDER")

    private fun clip(text: String, maxLength: Int): String =
        if (text.length <= maxLength) {
            text
        } else {
            // Same UTF-16 boundary rule the search snippet needs: a cut
            // between surrogates leaves half an emoji, which the shade paints
            // as a replacement glyph.
            text.substring(0, MessageSearch.codePointEnd(text, maxLength)).trimEnd() + ELLIPSIS
        }
}
