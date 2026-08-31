package com.yunjelee.securemsg

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Log

data class ProviderSms(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
)

/**
 * One stored SMS together with the direction the telephony store recorded.
 *
 * [type] is the raw `Telephony.Sms.TYPE`. It stays raw on purpose: mapping it
 * to a direction is the one decision a rebuild cannot get wrong, so it lives in
 * [HistoryRestorePlan.directionOf] where it is tested.
 */
data class ProviderSmsRecord(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
)

/**
 * A history read together with whether the store actually gave up all of it.
 *
 * [records] is a prefix at best whenever [complete] is false: a cursor that
 * throws part-way through a large store (CursorWindow overflow) and a query the
 * provider refuses outright both look exactly like an empty store otherwise,
 * and telling the user their phone holds no SMS when the read failed is worse
 * than telling them nothing.
 */
data class ProviderSmsHistory(
    val records: List<ProviderSmsRecord>,
    val complete: Boolean,
)

/** Writes/reads the system SMS Provider. Only valid while SecureMsg is default SMS. */
object SmsProvider {
    private const val TAG = "SmsProvider"

    /** Enough to cover years of ordinary use without holding a huge cursor result. */
    const val DEFAULT_HISTORY_LIMIT = 5_000
    private const val MAX_HISTORY_LIMIT = 20_000

    fun insertIncoming(context: Context, address: String, body: String, date: Long): Long? {
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.CREATOR, context.packageName)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                ?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "failed to persist incoming SMS", e)
            null
        }
    }

    fun insertSent(
        context: Context,
        address: String,
        body: String,
        date: Long,
        status: Int = Telephony.Sms.STATUS_PENDING,
    ): Long? {
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                // A failed dispatch must not linger in the system Outbox, which
                // other UIs treat as "still waiting to send".
                put(
                    Telephony.Sms.TYPE,
                    if (status == Telephony.Sms.STATUS_FAILED) {
                        Telephony.Sms.MESSAGE_TYPE_FAILED
                    } else {
                        Telephony.Sms.MESSAGE_TYPE_OUTBOX
                    },
                )
                put(Telephony.Sms.STATUS, status)
                put(Telephony.Sms.CREATOR, context.packageName)
            }
            context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
                ?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "failed to persist sent SMS", e)
            null
        }
    }

    fun updateSentStatus(context: Context, id: Long, status: Int, failed: Boolean = false) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.STATUS, status)
                put(
                    Telephony.Sms.TYPE,
                    if (failed) Telephony.Sms.MESSAGE_TYPE_FAILED else Telephony.Sms.MESSAGE_TYPE_SENT,
                )
            }
            context.contentResolver.update(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
                values,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to update sent SMS status", e)
        }
    }

    fun recentInbox(context: Context, limit: Int = 200): List<ProviderSms> {
        val out = mutableListOf<ProviderSms>()
        val safeLimit = limit.coerceIn(1, 500)
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(BaseColumns._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $safeLimit",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateCol = cursor.getColumnIndex(Telephony.Sms.DATE)
                while (cursor.moveToNext()) {
                    out += ProviderSms(
                        id = cursor.getLong(idCol),
                        address = if (addressCol >= 0) cursor.getString(addressCol).orEmpty() else "",
                        body = if (bodyCol >= 0) cursor.getString(bodyCol).orEmpty() else "",
                        date = if (dateCol >= 0) cursor.getLong(dateCol) else System.currentTimeMillis(),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to read inbox", e)
        }
        return out
    }

    /**
     * Every delivered SMS the system store still holds, oldest first.
     *
     * Unlike [recentInbox] this reads both directions and reports which one
     * each row was, which is what makes a rebuild possible at all: a relay
     * envelope does not record direction, while this store does.
     *
     * Drafts, outbox and failed rows are excluded in SQL — they are composition
     * state, not history, and excluding them there means the cap is spent on
     * real messages. The cap keeps the newest [limit] rows, so an oversized
     * store loses its oldest end rather than its most recent.
     *
     * A partial or refused read is reported rather than logged and dropped —
     * see [ProviderSmsHistory.complete].
     */
    fun allMessages(context: Context, limit: Int = DEFAULT_HISTORY_LIMIT): ProviderSmsHistory {
        val out = mutableListOf<ProviderSmsRecord>()
        val safeLimit = limit.coerceIn(1, MAX_HISTORY_LIMIT)
        // Set only after the cursor is walked to its end, so a throw mid-walk
        // and a null cursor both leave it false.
        var complete = false
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    BaseColumns._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                ),
                "${Telephony.Sms.TYPE} IN (?, ?)",
                arrayOf(
                    Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
                    Telephony.Sms.MESSAGE_TYPE_SENT.toString(),
                ),
                "${Telephony.Sms.DATE} DESC, ${BaseColumns._ID} DESC LIMIT $safeLimit",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateCol = cursor.getColumnIndex(Telephony.Sms.DATE)
                val typeCol = cursor.getColumnIndex(Telephony.Sms.TYPE)
                while (cursor.moveToNext()) {
                    out += ProviderSmsRecord(
                        id = cursor.getLong(idCol),
                        address = if (addressCol >= 0) cursor.getString(addressCol).orEmpty() else "",
                        body = if (bodyCol >= 0) cursor.getString(bodyCol).orEmpty() else "",
                        // 0 rather than "now": a row whose date cannot be read
                        // must be dropped, not stamped with the restore time and
                        // dragged to the top of the conversation.
                        date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                        // MESSAGE_TYPE_ALL never maps to a direction. A store
                        // that will not report the column cannot say who sent
                        // the message, and guessing renders a received SMS as
                        // one this account sent.
                        type = if (typeCol >= 0) {
                            cursor.getInt(typeCol)
                        } else {
                            Telephony.Sms.MESSAGE_TYPE_ALL
                        },
                    )
                }
                complete = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to read SMS history", e)
        }
        // Queried newest-first so the cap keeps recent history; returned in the
        // order the messages actually happened.
        return ProviderSmsHistory(out.reversed(), complete)
    }
}
