package com.yunjelee.securemsg

/**
 * Carries relay_receipts written under the blank cid over to the real one.
 *
 * From 5af167b (2026-09-05) through v0.23.1 the carrier path of the history pull took
 * its cid from the history row, which never has one (GET
 * /conversation/<cid>/messages puts the cid on the response only). Every
 * receipt was therefore written as ("", seq), the rendered row as
 * serverKey ":seq", and carrier callbacks came back under "" too. Seqs are per
 * conversation, so one conversation's receipt answered for every other
 * conversation's row at that seq: a second web message at a seq already used
 * elsewhere hit CONSUME_RESOLVED and was never sent, and nothing reported it.
 *
 * The pull now keys everything by the pulled thread's cid. Without a
 * transition that alone would re-send every web message already dispatched
 * under the blank key: the relay's carrier_status for those rows is still
 * `none` (a status report under "" is refused by the relay), and after the
 * update the cursor walks them again. So before claiming (cid, seq) for the
 * first time, the legacy ("", seq) receipt is weighed as follows:
 *
 * - None, or only `claimed`: nothing under that key ever reached the carrier
 *   API ('claimed' is written before 'attempting'), and no code writes a new
 *   blank-cid claim any more. Claim (cid, seq) as normal.
 * - The rendered legacy row (serverKey ":seq") exists. It was written by the
 *   one dispatch that holds the legacy receipt (the insert REPLACEs on
 *   serverKey, and a second dispatch under the same key never happened: a
 *   collision was consumed before it reached the insert). It records what
 *   was dispatched: the relay's created_at (the same value computed here),
 *   the text, content type, subject and web sender. Any of them different:
 *   the legacy receipt is another conversation's, so this row is
 *   [Decision.UNRELATED] and is claimed and sent now -- it is a message the
 *   collision dropped.
 * - All of them equal is still not proof of the conversation: the rendered
 *   row carries neither a cid nor a recipient, and two conversations whose
 *   rows share the seq, the second, the text, the type, the subject and the
 *   web device both match it. Taking the first match as the dispatched one
 *   would mark the other conversation's row sent -- and advance its cursor --
 *   when it was the one that never left, whichever of the two is pulled
 *   first. So [Decision.ADOPT] also needs evidence that names the recipient:
 *   the SMS path wrote the dispatched text to the system SMS store under the
 *   thread's own phone number (SmsSender -> SmsProvider.insertSent) moments
 *   after the claim, on the same phone clock as [Dispatch.claimedAt]
 *   ([Store.dispatchedTo], within [DISPATCH_WINDOW_MS]). An MMS dispatch
 *   leaves no such record this app writes, and the rendered comparison does
 *   not cover its attachments, so an MMS match never has that evidence.
 *   A match without it is [Decision.UNCERTAIN]: recorded as failed with
 *   [UNCERTAIN_ERROR], which the relay shows the web; nothing is sent again
 *   and nothing is marked sent that may not have been.
 * - A legacy receipt is adopted at most once ([Store.adoptedElsewhere], the
 *   adopted copy keeps the legacy claimedAt): two conversations with the same
 *   phone number can both carry that evidence for the one dispatch, and the
 *   second is [Decision.UNCERTAIN] too.
 * - No rendered row (logout clears `messages`; the insert may have failed):
 *   nothing local tells this row apart from the legacy dispatch. The legacy
 *   claim time is the phone's clock and created_at the relay's, so comparing
 *   them would stake a possible duplicate SMS on the two clocks agreeing;
 *   they are not compared. A resolved legacy outcome makes this row
 *   [Decision.UNCERTAIN]: recorded as failed with [UNCERTAIN_ERROR], which
 *   the relay shows the web instead of a silent drop, and the owner can
 *   resend it. An unresolved one makes it [Decision.WAIT].
 * - An unresolved legacy outcome ('attempting', or a status this build does
 *   not know) that is or may be this row's: [Decision.WAIT], exactly as the
 *   receipt itself would make the pull wait. Its carrier callback still
 *   arrives under the blank key, and the next pull decides again.
 *
 * The legacy rows themselves are left in place: another conversation may
 * still need them to tell its own row apart, and a late delivery report for
 * an old dispatch still lands on them.
 */
internal object LegacyRelayReceipt {
    const val CID = ""

    const val UNCERTAIN_ERROR =
        "Not sent again: a pre-update relay receipt for this sequence cannot be told apart " +
            "from this message. Resend it if it did not arrive."

    /**
     * How long after the legacy claim the dispatch's own system-store row can
     * be dated. Between the claim and SmsProvider.insertSent the SMS path only
     * writes the receipt and splits the text; the window is kept short so an
     * unrelated send of the same text to the same number cannot stand in for it.
     */
    const val DISPATCH_WINDOW_MS = 30_000L

    /** Tolerance for the store's date landing on the claim's millisecond or just before it. */
    const val DISPATCH_SLACK_MS = 1_000L

    fun serverKey(seq: Int): String = "$CID:$seq"

    enum class Decision { UNRELATED, ADOPT, WAIT, UNCERTAIN }

    /** The legacy ("", seq) receipt. */
    data class Dispatch(val status: String, val claimedAt: Long)

    /**
     * What a row says about itself: the rendered legacy row (serverKey ":seq")
     * as the dispatch path stored it, or the row being pulled, computed the
     * same way. [createdAt] is the relay's created_at in ms; null when the
     * pulled row has none.
     */
    data class Rendered(
        val createdAt: Long?,
        val plaintext: String,
        val contentType: String,
        val subject: String?,
        val senderSid: String,
    )

    /**
     * @param row the row being pulled, in the rendered row's terms.
     * @param adoptedElsewhere whether another conversation already adopted
     *   this legacy receipt.
     * @param dispatchedHere whether the system SMS store holds the legacy
     *   dispatch under this row's recipient ([Store.dispatchedTo]).
     */
    fun decide(
        legacy: Dispatch?,
        rendered: Rendered?,
        row: Rendered,
        adoptedElsewhere: Boolean = false,
        dispatchedHere: Boolean = false,
    ): Decision {
        if (legacy == null || legacy.status == "claimed") return Decision.UNRELATED
        val resolved = RelayReceiptRetryPolicy.action(legacy.status, claimIsStale = false) ==
            RelayReceiptRetryPolicy.Action.CONSUME_RESOLVED
        val undecided = if (resolved) Decision.UNCERTAIN else Decision.WAIT
        // The relay always sends created_at; without it, or without the
        // rendered row, nothing tells the two apart.
        if (row.createdAt == null || rendered == null) return undecided
        if (rendered != row) return Decision.UNRELATED
        if (!resolved) return Decision.WAIT
        if (adoptedElsewhere || !dispatchedHere) return Decision.UNCERTAIN
        return Decision.ADOPT
    }

    /** What [carryOver] reads and writes; Room in production, a map in tests. */
    interface Store {
        suspend fun receipt(cid: String, seq: Int): RelayReceipt?
        suspend fun rendered(serverKey: String): Rendered?

        /**
         * Whether a conversation other than [cid] already holds the adopted
         * copy of the legacy receipt at [seq]: a receipt there with the
         * legacy [claimedAt], which only [carryOver] writes (a fresh claim
         * takes the current time).
         */
        suspend fun adoptedElsewhere(cid: String, seq: Int, claimedAt: Long): Boolean

        /**
         * Whether the system SMS store holds a sent-direction row (sent,
         * outbox, failed or queued) with exactly [text] as its body, dated
         * [from]..[until] on this phone's clock, to an address that
         * normalizes to [phoneNumber]'s. False when the store cannot be read.
         */
        suspend fun dispatchedTo(phoneNumber: String, text: String, from: Long, until: Long): Boolean

        /** INSERT OR IGNORE, as RelayReceiptDao.claim. */
        suspend fun claim(receipt: RelayReceipt): Long
    }

    /**
     * Runs before the pull's own claim of ([cid], [seq]). Writes nothing when
     * (cid, seq) already has a receipt or the legacy one is [Decision.UNRELATED],
     * so the claim that follows is a fresh one; otherwise writes the receipt
     * the claim then finds, which the retry policy resolves as usual.
     * [Decision.WAIT] writes nothing and the caller retries the batch.
     *
     * @param phoneNumber the pulled thread's number, the one the carrier path
     *   dispatches to.
     */
    suspend fun carryOver(
        cid: String,
        seq: Int,
        phoneNumber: String,
        row: Rendered,
        store: Store,
        now: Long = System.currentTimeMillis(),
    ): Decision {
        if (cid == CID || store.receipt(cid, seq) != null) return Decision.UNRELATED
        val legacy = store.receipt(CID, seq) ?: return Decision.UNRELATED
        val rendered = store.rendered(serverKey(seq))
        // Only a text row reached SmsSender, the one path that leaves a
        // recipient-bearing record; only asked when the rendered row matches.
        val dispatchedHere = row.contentType == RelayContentCodec.TYPE_TEXT &&
            row.createdAt != null && rendered == row &&
            store.dispatchedTo(
                phoneNumber,
                row.plaintext,
                from = legacy.claimedAt - DISPATCH_SLACK_MS,
                until = legacy.claimedAt + DISPATCH_WINDOW_MS,
            )
        val decision = decide(
            Dispatch(legacy.status, legacy.claimedAt),
            rendered,
            row,
            store.adoptedElsewhere(cid, seq, legacy.claimedAt),
            dispatchedHere,
        )
        when (decision) {
            Decision.ADOPT -> store.claim(
                // Unsynced: the relay has never seen this outcome under a
                // cid it accepts, and the web still shows the row as pending.
                // claimedAt stays the legacy one: it marks the copy as the
                // adoption (Store.adoptedElsewhere).
                legacy.copy(cid = cid, statusSynced = false),
            )
            Decision.UNCERTAIN -> store.claim(
                RelayReceipt(cid, seq, claimedAt = now, status = "failed", lastError = UNCERTAIN_ERROR),
            )
            Decision.UNRELATED, Decision.WAIT -> Unit
        }
        return decision
    }
}

/**
 * @param sentBox [LegacyRelayReceipt.Store.dispatchedTo] over the system SMS
 *   store (SmsProvider.hasSentTo in production).
 */
internal class RoomLegacyReceiptStore(
    private val db: AppDatabase,
    private val sentBox: (phoneNumber: String, text: String, from: Long, until: Long) -> Boolean,
) : LegacyRelayReceipt.Store {
    override suspend fun receipt(cid: String, seq: Int) = db.relayReceiptDao().get(cid, seq)

    override suspend fun rendered(serverKey: String) = db.messageDao().getByServerKey(serverKey)
        ?.let { LegacyRelayReceipt.Rendered(it.createdAt, it.plaintext, it.contentType, it.subject, it.senderSid) }

    override suspend fun adoptedElsewhere(cid: String, seq: Int, claimedAt: Long) =
        db.relayReceiptDao().hasCopyElsewhere(cid, seq, claimedAt)

    override suspend fun dispatchedTo(phoneNumber: String, text: String, from: Long, until: Long) =
        sentBox(phoneNumber, text, from, until)

    override suspend fun claim(receipt: RelayReceipt) = db.relayReceiptDao().claim(receipt)
}
