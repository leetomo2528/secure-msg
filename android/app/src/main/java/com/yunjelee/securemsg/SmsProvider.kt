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

/** Writes/reads the system SMS Provider. Only valid while SecureMsg is default SMS. */
object SmsProvider {
    private const val TAG = "SmsProvider"

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
}
