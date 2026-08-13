package com.yunjelee.securemsg

/** Pure readiness check for MMS provider rows, which may appear before download completes. */
object IncomingMmsPolicy {
    fun isReady(mms: ProviderMms?): Boolean {
        if (mms == null || mms.address.isBlank()) return false
        return !mms.subject.isNullOrBlank() || mms.body.isNotBlank() || mms.parts.isNotEmpty()
    }
}
