package com.yunjelee.securemsg

/**
 * Pure decisions for MMS provider rows, which may appear -- and whose parts may
 * still be landing -- before the download completes.
 */
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
     * it. Whether that photo's bytes are actually there yet is a separate
     * question, answered after materializing by [settle].
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

    /**
     * The material to persist, or null when the message went back to the
     * deferred-retry loop instead and nothing may be written for it.
     *
     * A part that could not be read yet ([RelayMaterial.pending]) holds the
     * whole message back while [tryDefer] can still schedule a retry.
     * Persisting now would claim the message in the relay outbox and, once
     * acknowledged, in processed_mms with that part simply absent. Both ledgers
     * key on the identity [MmsProvider.read] builds, and a non-text part joins
     * that identity only when its bytes read non-empty and fit what is left of
     * the 512 KiB identity cap. A part that stays out of it -- a camera photo
     * over 512 KiB, exactly the part [ProviderMms.relayCandidates] exists to
     * carry -- leaves every later read with the same identity, and the ledgers
     * dedupe those reads away while they hold the row: no sweep repairs the
     * message. That is how a photo-only MMS became an empty bubble on the
     * phone, the web and the notification, and stayed one.
     *
     * A part that does fit, once readable, changes the identity instead: a
     * later sweep that still sees the row fingerprints it differently,
     * [ProviderIdentityResolver] takes that as a reused provider id, and the
     * row is relayed again as a new message, the photo arriving as a second
     * bubble. That predates this and is left as it is -- identity is not this
     * decision's to change, and a late duplicate that carries the photo is an
     * acceptable outcome where an empty bubble is not. Deferring still spares
     * that case the empty first copy whenever the part lands in time.
     *
     * Once this message's retries are spent ([Deferral.EXHAUSTED]) the message
     * is persisted anyway, with one omission per part still unread. The text
     * and every part that did arrive must not be held hostage forever by one
     * part that never will, and the owner is told about the gap instead of
     * being shown nothing. Those omissions never carry a size: 용량이 커서 is a
     * claim about the cause, and a part that could not even be read was never
     * weighed.
     *
     * A tracking table with no room ([Deferral.NO_ROOM]) is not that. It says
     * nothing about this message -- its budget was never touched -- and it
     * is not rare either: every content-less or zero-length-part MMS used to
     * keep its entry for the life of the service, so 512 of them made the next
     * photo whose bytes were still landing come out as "[사진 1장은 받지 못했습니다]"
     * at once, and for good (a photo over the identity cap never changes the
     * identity the ledgers then hold). So nothing is written, exactly as for a
     * row that is not ready: the row stays in the provider for the next sweep.
     *
     * [tryDefer] reports whether a retry will come back to the message: one it
     * scheduled, or one already queued for it that it joined. A stop of the
     * service can still cancel that retry, and the row, with nothing written
     * for it, then waits in the provider for the next sweep. It is called
     * only when something is pending, so a complete message never spends a
     * retry and never starts a retry chain.
     */
    fun settle(material: RelayMaterial, tryDefer: () -> Deferral): RelayMaterial? {
        if (material.pending.isEmpty()) return material
        when (tryDefer()) {
            Deferral.RETRYING, Deferral.NO_ROOM -> return null
            Deferral.EXHAUSTED -> Unit
        }
        return RelayMaterial(
            parts = material.parts,
            omissions = material.omissions + material.pending.map {
                IncomingOmissionNotice.Omission(ImageShrinkPolicy.omissionKind(it.contentType), bytes = null)
            },
        )
    }

    /** What asking for a deferred retry of one MMS came to; see [settle]. */
    enum class Deferral {
        /** A retry was queued for the message, or it joined the one already queued. */
        RETRYING,

        /** Every retry this message gets has been used: persist it with a notice. */
        EXHAUSTED,

        /** The tracking table had no room for it: leave it for the next sweep. */
        NO_ROOM,
    }
}
