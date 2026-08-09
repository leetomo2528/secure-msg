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
        require(snapshot.size <= 500) {
            "서버 대화가 500개를 초과해 연락처 이름을 안전하게 동기화할 수 없습니다"
        }
        val entries = JSONArray().apply {
            snapshot.forEach { desired ->
                put(
                    JSONObject()
                        .put("cid", desired.cid)
                        .put("contact_name", desired.localContactName ?: JSONObject.NULL),
                )
            }
        }
        val response = try {
            api.syncContactNames(entries)
        } catch (_: Exception) {
            JSONObject().put("ok", false)
        }
        val uploaded = if (response.optBoolean("ok")) snapshot.size else 0
        val failed = if (response.optBoolean("ok")) 0 else snapshot.size.coerceAtLeast(1)
        if (response.optBoolean("ok")) {
            snapshot.forEach { desired ->
                dao.updateSyncedContactNameByCid(desired.cid, desired.localContactName)
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

    private fun readContactPhones(context: Context): List<ContactPhoneRow> {
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
