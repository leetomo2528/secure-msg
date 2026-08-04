package com.yunjelee.securemsg

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Read-only provider used by SmsManager.sendMultimediaMessage(). */
class MmsPduProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.startsWith("r")) {
            // ContentProvider contract: openFile must raise
            // FileNotFoundException/SecurityException, not IllegalArgumentException.
            throw SecurityException("MMS PDUs are read-only")
        }
        val id = uri.lastPathSegment?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) }
            ?: throw java.io.FileNotFoundException("invalid MMS PDU")
        val appContext = context ?: throw java.io.FileNotFoundException("provider detached")
        val file = File(appContext.cacheDir, "mms-pdu/$id.pdu")
        if (!file.isFile) throw java.io.FileNotFoundException(file.path)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.wap.mms-message"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
