package com.yunjelee.securemsg

import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps every relay_outbox row readable through one Android CursorWindow.
 *
 * SQLite hands a query's rows to Java through a CursorWindow of 2 MiB
 * (config_cursorWindowSize), and a single row that does not fit throws
 * SQLiteBlobTooBigException rather than being truncated. An outbox row carries
 * three large columns: `plaintext` (the relay content encoding, attachments in
 * base64: about 4A/3 for A bytes of attachments), `payload` (the encrypted
 * envelope of that plaintext, added by markPrepared: about 16A/9), and up to
 * v0.23.1 `attachmentsJson` (a second base64 copy: another 4A/3). With all
 * three a row reaches about 40A/9, which passes 2,097,152 bytes from
 * A ≈ 461 KiB -- under the 512 KiB attachment cap -- and that one row made
 * every `SELECT *` page of the outbox throw, on every flush, forever.
 *
 * Without the duplicate the prepared row is about 28A/9: 512 KiB of
 * attachments plus the longest text the codec accepts stays below
 * [LARGE_COLUMN_BUDGET] (OutboxRowBudgetTest pins the numbers).
 */
internal object OutboxRowBudget {
    const val CURSOR_WINDOW_BYTES: Long = 2L * 1024 * 1024

    /**
     * Headroom for everything that is not a large column: mid, cid, phone
     * number, state strings and integers, plus the window's own per-row and
     * per-field bookkeeping and NUL terminators. Those are well under 1 KiB
     * for any row; 64 KiB leaves room for a long subject or error string too.
     */
    const val SMALL_COLUMN_ALLOWANCE: Long = 64L * 1024

    const val LARGE_COLUMN_BUDGET: Long = CURSOR_WINDOW_BYTES - SMALL_COLUMN_ALLOWANCE

    /** Upper bound on one `keys` entry of an envelope, sid and optional `by` included. */
    const val ENVELOPE_KEY_ENTRY_BYTES: Long = 512

    /** `{"ct":"","nonce":"<32>","keys":{}}` and slack. */
    const val ENVELOPE_FIXED_BYTES: Long = 128

    fun fits(largeColumnBytes: Long): Boolean = largeColumnBytes in 0..LARGE_COLUMN_BUDGET

    /** UTF-8 byte length -- what the CursorWindow stores -- without encoding a copy. */
    fun utf8Length(value: String?): Long {
        if (value == null) return 0
        var bytes = 0L
        var i = 0
        while (i < value.length) {
            val c = value[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < value.length &&
                    Character.isLowSurrogate(value[i + 1]) -> {
                    i += 1
                    4
                }
                else -> 3
            }
            i += 1
        }
        return bytes
    }

    /** The same sum [RelayOutboxDao.pendingRefs] computes in SQL. */
    fun largeColumnBytes(row: RelayOutbox): Long =
        utf8Length(row.payload) + utf8Length(row.plaintext) +
            utf8Length(row.attachmentsJson) + utf8Length(row.subject)

    /**
     * Upper bound on the envelope JSON [CryptoUtil.encryptMessage] produces
     * for [plaintextBytes] of UTF-8 sealed to [recipients] devices: the
     * secretbox ciphertext (plaintext + 16-byte MAC) in unpadded base64url,
     * plus one bounded key entry per recipient.
     */
    fun envelopeBytes(plaintextBytes: Long, recipients: Int): Long {
        val sealed = plaintextBytes + 16
        val ct = (sealed * 4 + 2) / 3
        return ct + ENVELOPE_FIXED_BYTES + recipients.toLong() * ENVELOPE_KEY_ENTRY_BYTES
    }
}

/** A row [RelayOutboxDao.getById] could never read back, found before writing it. */
internal class OutboxRowTooLargeException(val largeColumnBytes: Long) :
    IllegalStateException("relay row exceeds the cursor window")

/**
 * Walks a page of [RelayOutboxRef]s and loads each full row on its own.
 *
 * One row that cannot be read -- over the window budget, or throwing for any
 * other reason -- is handed to [skip] (the flush records the attempt) and the
 * walk goes on to the next ref. That isolation is the point: the rows behind a
 * bad one, and flushReceiptStatuses after the flush, still run. Rows are loaded
 * one at a time on purpose; a page of 100 near-cap rows held at once would be
 * close to 200 MB of strings.
 */
internal class OutboxRowCursor<R : Any>(
    private val refs: List<RelayOutboxRef>,
    private val read: suspend (id: Long) -> R?,
    private val skip: suspend (ref: RelayOutboxRef, reason: String) -> Unit,
) {
    private var index = 0

    /** The next readable row, or null when the page is exhausted. */
    suspend fun next(): R? {
        while (index < refs.size) {
            val ref = refs[index++]
            if (!OutboxRowBudget.fits(ref.largeColumnBytes)) {
                skip(ref, OVERSIZED)
                continue
            }
            val row = try {
                read(ref.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skip(ref, "$UNREADABLE: ${e.javaClass.simpleName}")
                continue
            }
            // Null: the row was deleted since the page was listed. Nothing to
            // record, nothing to relay.
            if (row != null) return row
        }
        return null
    }

    companion object {
        const val OVERSIZED = "relay row exceeds cursor window"
        const val UNREADABLE = "relay row unreadable"
    }
}
