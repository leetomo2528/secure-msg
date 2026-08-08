package com.yunjelee.securemsg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactSyncStatus(
    val lastSyncedAt: Long,
    val matchedThreadCount: Int,
    val contactPhoneCount: Int,
)

/**
 * Reads contacts and updates only the local Room database. Contact rows are never serialized or
 * passed to RelayApi, so names and address-book numbers never leave this Android device.
 */
object ContactSync {
    private const val PREFS = "contact_sync"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_MATCHED_THREADS = "matched_threads"
    private const val KEY_CONTACT_PHONES = "contact_phones"

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
        )
    }

    /** Clear account-adjacent UI status when the local device is forgotten. */
    fun clearStatus(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    suspend fun sync(context: Context): ContactSyncStatus {
        check(hasPermission(context)) { "READ_CONTACTS permission is required" }
        val contacts = readContactPhones(context)
        val dao = AppDatabase.get(context).threadDao()
        val mapping = ContactNameMapper.map(contacts, dao.getAll())
        mapping.updates.forEach {
            dao.updateLocalContactNameByCid(it.cid, it.localContactName)
        }

        val status = ContactSyncStatus(
            lastSyncedAt = System.currentTimeMillis(),
            matchedThreadCount = mapping.matchedThreadCount,
            contactPhoneCount = mapping.contactPhoneCount,
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SYNC, status.lastSyncedAt)
            .putInt(KEY_MATCHED_THREADS, status.matchedThreadCount)
            .putInt(KEY_CONTACT_PHONES, status.contactPhoneCount)
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
