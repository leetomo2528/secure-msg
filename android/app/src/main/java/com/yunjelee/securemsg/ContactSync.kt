package com.yunjelee.securemsg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

data class ContactSyncStatus(
    val lastSyncedAt: Long,
    val matchedThreadCount: Int,
    val contactPhoneCount: Int,
    val uploadedCount: Int = 0,
    val failedUploadCount: Int = 0,
)

/**
 * Reads contacts, applies names locally, then publishes only per-conversation names (not the
 * address-book phone list) to the authenticated relay for the user's other devices.
 */
object ContactSync {
    private const val PREFS = "contact_sync"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_MATCHED_THREADS = "matched_threads"
    private const val KEY_CONTACT_PHONES = "contact_phones"
    private const val KEY_UPLOADED = "uploaded"
    private const val KEY_UPLOAD_FAILED = "upload_failed"

    /** `POST /api/contact-names/sync` refuses a body with more than this many entries. */
    private const val MAX_ENTRIES_PER_REQUEST = 500

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun loadStatus(context: Context): ContactSyncStatus? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
        if (lastSync <= 0L) return null
        return ContactSyncStatus(
            lastSyncedAt = lastSync,
            matchedThreadCount = prefs.getInt(KEY_MATCHED_THREADS, 0),
            contactPhoneCount = prefs.getInt(KEY_CONTACT_PHONES, 0),
            uploadedCount = prefs.getInt(KEY_UPLOADED, 0),
            failedUploadCount = prefs.getInt(KEY_UPLOAD_FAILED, 0),
        )
    }

    /** Clear account-adjacent UI status when the local device is forgotten. */
    fun clearStatus(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    suspend fun sync(context: Context, api: RelayApi): ContactSyncStatus {
        check(hasPermission(context)) { "READ_CONTACTS permission is required" }
        val contacts = readContactPhones(context)
        val dao = AppDatabase.get(context).threadDao()
        val mapping = ContactNameMapper.map(contacts, dao.getAll())
        mapping.updates.forEach {
            dao.updateLocalContactNameByCid(it.cid, it.localContactName)
        }

        val snapshot = mapping.desiredNames.filterNot { it.cid.startsWith("local_") }
        // One request over the cap was refused outright, so an account with more
        // conversations than that threw here — after the local names had already
        // been written — and could never publish a single name. An empty snapshot
        // still posts once so an offline sync reports a failure, not a success.
        val batches = if (snapshot.isEmpty()) {
            listOf(emptyList<ContactNameUpdate>())
        } else {
            snapshot.chunked(MAX_ENTRIES_PER_REQUEST)
        }
        var uploaded = 0
        var failed = 0
        batches.forEach { batch ->
            val published = batch.map { it.cid to sanitizeContactName(it.localContactName) }
            val entries = JSONArray().apply {
                published.forEach { (cid, name) ->
                    put(
                        JSONObject()
                            .put("cid", cid)
                            .put("contact_name", name ?: JSONObject.NULL),
                    )
                }
            }
            val ok = try {
                api.syncContactNames(entries).optBoolean("ok")
            } catch (_: Exception) {
                false
            }
            if (ok) {
                uploaded += batch.size
                published.forEach { (cid, name) -> dao.updateSyncedContactNameByCid(cid, name) }
            } else {
                failed += batch.size.coerceAtLeast(1)
            }
        }

        val status = ContactSyncStatus(
            lastSyncedAt = System.currentTimeMillis(),
            matchedThreadCount = mapping.matchedThreadCount,
            contactPhoneCount = mapping.contactPhoneCount,
            uploadedCount = uploaded,
            failedUploadCount = failed,
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SYNC, status.lastSyncedAt)
            .putInt(KEY_MATCHED_THREADS, status.matchedThreadCount)
            .putInt(KEY_CONTACT_PHONES, status.contactPhoneCount)
            .putInt(KEY_UPLOADED, status.uploadedCount)
            .putInt(KEY_UPLOAD_FAILED, status.failedUploadCount)
            .apply()
        return status
    }

    /**
     * The relay validates a whole batch and rolls it back on the first bad name,
     * so one address-book label carrying a control character or running past 100
     * characters would keep every other name off the account's other devices.
     */
    internal fun sanitizeContactName(raw: String?): String? {
        val cleaned = raw?.filterNot { it.isISOControl() }?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        val capped = cleaned.take(100).trimEnd()
        // take() can cut a surrogate pair in half, and a lone surrogate is not
        // UTF-8 encodable — the request would fail before the relay ever saw it.
        return if (capped.lastOrNull()?.isHighSurrogate() == true) capped.dropLast(1) else capped
    }

    internal fun readContactPhones(context: Context): List<ContactPhoneRow> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        return buildList {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL",
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC",
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                )
                val numberIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty()
                    val number = cursor.getString(numberIndex).orEmpty()
                    if (name.isNotBlank() && number.isNotBlank()) {
                        add(ContactPhoneRow(name, number))
                    }
                }
            }
        }
    }
}
