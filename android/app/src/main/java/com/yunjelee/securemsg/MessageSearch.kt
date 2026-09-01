package com.yunjelee.securemsg

/**
 * One hit's text, flattened to a single line and windowed around the match.
 *
 * [matchStart]/[matchEnd] index into [text] — leading ellipsis included — so
 * the renderer styles the match without searching the string a second time and
 * cannot drift from the window this computed.
 */
data class SearchSnippet(
    val text: String,
    val matchStart: Int,
    val matchEnd: Int,
) {
    val hasMatch: Boolean get() = matchEnd > matchStart
}

/**
 * One cross-conversation search result.
 *
 * The conversation's labels travel with the message because a hit is never
 * shown detached from who it is with, and the list resolves them exactly as
 * [SmsThread.displayName] does for a normal row.
 */
data class MessageHit(
    val cid: String,
    val displayName: String,
    val phoneNumber: String,
    val showsPhoneSubtitle: Boolean,
    val messageId: Long,
    val createdAt: Long,
    val snippet: SearchSnippet,
    /** True when this account sent the message; the row says so, because the
     * conversation's name and avatar otherwise present it as the other side's. */
    val mine: Boolean,
)

/** Pure, local-only filtering for the Android message UI. */
object MessageSearch {
    /**
     * Newest-first cap on one global search, matching the web client's
     * `searchMessages`. The count line names the cap when it may have been
     * reached, so a truncated result set is never passed off as complete.
     */
    const val GLOBAL_LIMIT = 50

    private const val ELLIPSIS = "…"
    private val WHITESPACE = Regex("\\s+")

    fun filterThreads(threads: List<SmsThread>, query: String): List<SmsThread> {
        val term = query.trim()
        if (term.isEmpty()) return threads

        return threads.filter { thread ->
            thread.displayName.contains(term, ignoreCase = true) ||
                thread.phoneNumber.contains(term, ignoreCase = true)
        }
    }

    fun filterMessages(messages: List<MessageRow>, query: String): List<MessageRow> {
        val term = query.trim()
        if (term.isEmpty()) return messages

        return messages.filter { matches(it, term) }
    }

    /**
     * Escapes the LIKE metacharacters so a query matches literally.
     *
     * Pairs with the `ESCAPE '\'` clause in [MessageDao.searchAll]: without it
     * "50%" would match every message and "a_b" would match "axb". The escape
     * character itself has to be doubled or a trailing backslash would escape
     * the pattern's own closing wildcard.
     */
    fun escapeLike(term: String): String {
        val out = StringBuilder(term.length + 4)
        term.forEach { ch ->
            if (ch == '\\' || ch == '%' || ch == '_') out.append('\\')
            out.append(ch)
        }
        return out.toString()
    }

    /**
     * Labels DAO rows for the conversation list's 메시지 section.
     *
     * [matches] is re-applied on purpose: it is the predicate the opened
     * conversation filters with, and tapping a hit hands the query to that
     * pane — a row the SQL matched but this rejects would open a chat whose
     * search reports nothing. SQLite's LIKE folds case for ASCII only, so its
     * matches are a subset of this one's and nothing legitimate is dropped.
     *
     * Input order is kept (the DAO orders newest-first); a hit whose
     * conversation is not in [threads] is dropped because there would be no
     * conversation to open or to name it with.
     */
    fun globalHits(
        messages: List<MessageRow>,
        threads: List<SmsThread>,
        query: String,
        limit: Int = GLOBAL_LIMIT,
    ): List<MessageHit> {
        val term = query.trim()
        if (term.isEmpty() || limit <= 0) return emptyList()
        val byCid = threads.associateBy { it.cid }

        return messages.asSequence()
            .filter { matches(it, term) }
            .mapNotNull { row ->
                val thread = byCid[row.cid] ?: return@mapNotNull null
                MessageHit(
                    cid = row.cid,
                    displayName = thread.displayName,
                    phoneNumber = thread.phoneNumber,
                    showsPhoneSubtitle = thread.showsPhoneSubtitle,
                    messageId = row.id,
                    createdAt = row.createdAt,
                    snippet = snippet(hitSource(row), term),
                    mine = row.mine,
                )
            }
            .take(limit)
            .toList()
    }

    /**
     * A one-line window of [source] centred on the first occurrence of [query].
     *
     * Both sides are flattened to single-spaced text first: the row is two
     * lines tall, and the raw body's newlines would otherwise blow the window
     * out while shifting every index the caller styles with. A query that does
     * not occur (matched on the other field, or only under SQLite's own case
     * folding) still yields a readable head, marked as having no match rather
     * than highlighting an arbitrary span.
     */
    fun snippet(
        source: String,
        query: String,
        radius: Int = 24,
        maxLength: Int = 96,
    ): SearchSnippet {
        val flat = flatten(source)
        val term = flatten(query)
        val at = if (term.isEmpty()) -1 else flat.indexOf(term, ignoreCase = true)
        if (at < 0) return SearchSnippet(clip(flat, maxLength), 0, 0)

        val start = codePointStart(flat, (at - radius).coerceAtLeast(0))
        var end = codePointEnd(flat, (at + term.length + radius).coerceAtMost(flat.length))
        if (end - start > maxLength) {
            end = codePointEnd(flat, (start + maxLength).coerceAtMost(flat.length))
        }
        val head = if (start > 0) ELLIPSIS else ""
        val tail = if (end < flat.length) ELLIPSIS else ""
        val matchStart = head.length + (at - start)
        val matchEnd = matchStart + minOf(term.length, end - at).coerceAtLeast(0)
        return SearchSnippet(head + flat.substring(start, end) + tail, matchStart, matchEnd)
    }

    /**
     * Line shown under the result counts when quarantined spam also matched.
     *
     * 격리된 스팸 is a separate store: those bodies never enter the conversation
     * table, so a global search cannot return them and must not — spam would be
     * posing as normal history. They are readable in 설정, so the match is
     * counted and pointed at instead of silently dropped.
     */
    fun quarantineNotice(count: Int): String? =
        if (count <= 0) {
            null
        } else {
            "격리된 스팸에서 ${count}건이 일치합니다 — 설정 › 격리된 스팸에서 같은 말로 검색하세요."
        }

    /**
     * Quarantined rows whose body matches [query]; input order (newest-first)
     * is kept.
     *
     * Mirrors [BlockedSmsDao.countMatching] so 설정 › 격리된 스팸 can reach
     * every row [quarantineNotice] counted — the notice names a number the
     * destination has to be able to produce. SQLite's LIKE folds case for ASCII
     * only, so this predicate is a superset of the SQL one and cannot hide a
     * counted row. Body only: matching the sender number here would list rows
     * the notice never counted.
     */
    fun filterQuarantine(items: List<BlockedSms>, query: String): List<BlockedSms> {
        val term = query.trim()
        if (term.isEmpty()) return items
        return items.filter { it.body.contains(term, ignoreCase = true) }
    }

    /** Count line above the results; names the cap when the hit list may be truncated. */
    fun resultSummary(threadCount: Int, messageCount: Int): String {
        val messages =
            if (messageCount >= GLOBAL_LIMIT) "최근 ${GLOBAL_LIMIT}건 이상" else "${messageCount}건"
        return "대화 상대 ${threadCount}건 · 메시지 $messages"
    }

    private fun matches(message: MessageRow, term: String): Boolean =
        !message.blocked && (
            message.plaintext.contains(term, ignoreCase = true) ||
                message.subject?.contains(term, ignoreCase = true) == true
            )

    /** Subject-carrying rows read "제목 — 본문", the same shape the web client's hit list uses. */
    private fun hitSource(message: MessageRow): String {
        val subject = message.subject?.takeIf { it.isNotBlank() } ?: return message.plaintext
        return "$subject — ${message.plaintext}"
    }

    private fun flatten(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun clip(text: String, maxLength: Int): String =
        if (text.length <= maxLength) {
            text
        } else {
            text.substring(0, codePointEnd(text, maxLength)) + ELLIPSIS
        }

    /**
     * Snap a window edge off the middle of a surrogate pair.
     *
     * Every index here is a UTF-16 offset, so an emoji straddling the boundary
     * would otherwise leave a lone surrogate in the snippet — which Compose
     * paints as a replacement glyph. Moving the start forward and the end back
     * drops the split character rather than emitting half of it.
     *
     * [codePointEnd] is internal, not private: [UpdateNotes] clips release
     * notes on this same rule, and a second copy of it would drift.
     */
    private fun codePointStart(text: String, index: Int): Int =
        if (index > 0 && index < text.length && Character.isLowSurrogate(text[index])) {
            index + 1
        } else {
            index
        }

    internal fun codePointEnd(text: String, index: Int): Int =
        if (index in 1 until text.length && Character.isHighSurrogate(text[index - 1])) {
            index - 1
        } else {
            index
        }
}
