package com.yunjelee.securemsg

/** Pure readiness check for MMS provider rows, which may appear before download completes. */
object IncomingMmsPolicy {
    /**
     * Whether the row carries anything worth persisting, or is still the empty
     * shell the platform publishes before the download finishes.
     *
     * The candidate clause is what makes a photo-only MMS arrive at all. Its
     * one part is a camera photo, [MmsProvider.read] drops it from the identity
     * list for being over the attachment cap, subject and body are empty -- and
     * the row was then deferred forever: never stored, never notified, never
     * relayed, with the retry budget spent on a message that was in fact
     * complete. A candidate is metadata only (MIME and declared size), so this
     * stays a pure check: nothing is decoded and no part byte is read to answer
     * it.
     *
     * smil does not count, because every MMS carries one; a row holding nothing
     * but the layout script really is still a shell.
     */
    fun isReady(mms: ProviderMms?): Boolean {
        if (mms == null || mms.address.isBlank()) return false
        return !mms.subject.isNullOrBlank() ||
            mms.body.isNotBlank() ||
            mms.parts.isNotEmpty() ||
            mms.relayCandidates.any { !ImageShrinkPolicy.isIgnorable(it.contentType) }
    }
}
