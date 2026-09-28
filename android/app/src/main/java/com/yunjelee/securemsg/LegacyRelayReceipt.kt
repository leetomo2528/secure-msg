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
 *   collision was consumed before it reached the insert). Its createdAt is
 *   the relay's created_at of the dispatched row, the same value computed
 *   here, and its text is the dispatched text. Equal on both: this very row
 *   was dispatched, so [Decision.ADOPT] its outcome. Different: the legacy
 *   receipt is another conversation's, so this row is [Decision.UNRELATED]
 *   and is claimed and sent now -- it is a message the collision dropped.
 * - No rendered row (logout clears `messages`; the insert may have failed).
 *   A receipt claimed more than [CLOCK_SKEW_MS] before the relay created
 *   this row cannot be this row's: [Decision.UNRELATED]. Otherwise nothing
 *   local tells the two apart, and an automatic send could be a duplicate:
 *   [Decision.UNCERTAIN] records the row as failed with [UNCERTAIN_ERROR],
 *   which the relay shows the web instead of a silent drop, and the user can
 *   resend it.
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

    /** Phone and relay clocks; a legacy claim this much older than the row is not its own. */
    const val CLOCK_SKEW_MS = 10 * 60_000L

    const val UNCERTAIN_ERROR =
        "Not sent again: a pre-update relay receipt for this sequence cannot be told apart " +
            "from this message. Resend it if it did not arrive."

    fun serverKey(seq: Int): String = "$CID:$seq"

    enum class Decision { UNRELATED, ADOPT, WAIT, UNCERTAIN }

    /** The legacy ("", seq) receipt. */
    data class Dispatch(val status: String, val claimedAt: Long)

    /** The rendered legacy row (serverKey ":seq"). */
    data class Rendered(val createdAt: Long, val plaintext: String)

    /**
     * @param rowCreatedAtMs the relay's created_at of the row being pulled, in
     *   ms, computed exactly as the dispatch path stores it; null when absent.
     * @param rowText the decoded text of the row being pulled.
     */
    fun decide(
        legacy: Dispatch?,
        rendered: Rendered?,
        rowCreatedAtMs: Long?,
        rowText: String,
    ): Decision {
        if (legacy == null || legacy.status == "claimed") return Decision.UNRELATED
        val resolved = RelayReceiptRetryPolicy.action(legacy.status, claimIsStale = false) ==
            RelayReceiptRetryPolicy.Action.CONSUME_RESOLVED
        val undecided = if (resolved) Decision.UNCERTAIN else Decision.WAIT
        // The relay always sends created_at; without it neither test applies.
        if (rowCreatedAtMs == null) return undecided
        if (rendered != null) {
            val same = rendered.createdAt == rowCreatedAtMs && rendered.plaintext == rowText
            if (!same) return Decision.UNRELATED
            return if (resolved) Decision.ADOPT else Decision.WAIT
        }
        if (rowCreatedAtMs > legacy.claimedAt + CLOCK_SKEW_MS) return Decision.UNRELATED
        return undecided
    }

    /** What [carryOver] reads and writes; Room in production, a map in tests. */
    interface Store {
        suspend fun receipt(cid: String, seq: Int): RelayReceipt?
        suspend fun rendered(serverKey: String): Rendered?

        /** INSERT OR IGNORE, as RelayReceiptDao.claim. */
        suspend fun claim(receipt: RelayReceipt): Long
    }

    /**
     * Runs before the pull's own claim of ([cid], [seq]). Writes nothing when
     * (cid, seq) already has a receipt or the legacy one is [Decision.UNRELATED],
     * so the claim that follows is a fresh one; otherwise writes the receipt
     * the claim then finds, which the retry policy resolves as usual.
     * [Decision.WAIT] writes nothing and the caller retries the batch.
     */
    suspend fun carryOver(
        cid: String,
        seq: Int,
        rowCreatedAtMs: Long?,
        rowText: String,
        store: Store,
        now: Long = System.currentTimeMillis(),
    ): Decision {
        if (cid == CID || store.receipt(cid, seq) != null) return Decision.UNRELATED
        val legacy = store.receipt(CID, seq) ?: return Decision.UNRELATED
        val decision = decide(
            Dispatch(legacy.status, legacy.claimedAt),
            store.rendered(serverKey(seq)),
            rowCreatedAtMs,
            rowText,
        )
        when (decision) {
            Decision.ADOPT -> store.claim(
                // Unsynced: the relay has never seen this outcome under a
                // cid it accepts, and the web still shows the row as pending.
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

internal class RoomLegacyReceiptStore(private val db: AppDatabase) : LegacyRelayReceipt.Store {
    override suspend fun receipt(cid: String, seq: Int) = db.relayReceiptDao().get(cid, seq)

    override suspend fun rendered(serverKey: String) = db.messageDao().getByServerKey(serverKey)
        ?.let { LegacyRelayReceipt.Rendered(it.createdAt, it.plaintext) }

    override suspend fun claim(receipt: RelayReceipt) = db.relayReceiptDao().claim(receipt)
}
