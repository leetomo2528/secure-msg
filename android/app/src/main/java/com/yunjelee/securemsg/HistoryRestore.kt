package com.yunjelee.securemsg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.yunjelee.securemsg.ui.LastOpened
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The decisions behind a conversation rebuild, kept free of Room and the
 * platform so every one of them can be exercised directly.
 */
object HistoryRestorePlan {

    /**
     * Sequence stamped on every provider-sourced row.
     *
     * The relay assigns sequences starting at 1, and a local row still waiting
     * for one carries 0, so the negative range is free by construction. One
     * sentinel is enough because nothing orders or addresses a message by seq:
     * a conversation sorts on createdAt, carrier status is addressed through
     * serverKey, and no query writes by `(cid, seq)` at all — `blocked` is
     * decided before the row is inserted (SmsReceiver, SmsBridgeService), never
     * patched afterwards.
     */
    const val RESTORED_SEQ = -1

    /**
     * serverKey namespace for provider-sourced rows.
     *
     * Relay rows use `<cid>:<seq>`, so a key carrying this prefix cannot
     * collide with one unless a cid contains a colon — neither a relay cid nor
     * the `local_<uuid>` form does. It deliberately does not embed the cid: a
     * provisional local thread is later merged into the relay cid
     * (MessageDao.moveConversation) and the link has to survive that move.
     */
    const val SERVER_KEY_PREFIX = "restore:sms:"

    /** RelayContentCodec's own text cap; a longer row cannot be encoded at all. */
    const val MAX_BODY_CHARS = 20_000

    /**
     * How far a stored row's timestamp may sit from a local row's when both
     * were stamped from this device's own clock.
     *
     * That covers every received message — the live import stamps createdAt
     * with the same provider date this reads — and every send made in this
     * app's composer, whose provider row is written moments after the local
     * one. The tolerance only absorbs the gap between those two writes.
     */
    const val SAME_CLOCK_MATCH_WINDOW_MS = 120_000L

    /**
     * The tolerance for a send composed on another device of this account and
     * gateway-dispatched by this phone.
     *
     * There the two timestamps come from different events, not one clock: the
     * local row carries the relay's created_at, while the provider row is
     * stamped when this phone actually reached the carrier. A phone in Doze or
     * out of coverage picks such a send up long afterwards, and matching within
     * two minutes republished those messages as duplicates.
     *
     * It stays bounded on purpose. A match consumes the local row it matched
     * and the nearest one is chosen greedily, so an unbounded window would let
     * an old provider row consume the slot of a recent identical send and
     * duplicate that one instead.
     */
    const val RELAY_CLOCK_MATCH_WINDOW_MS = 24 * 60 * 60 * 1000L

    enum class Direction { INCOMING, OUTGOING }

    /**
     * The tolerance a local row brings to the comparison, decided by which
     * clock stamped it rather than by the direction alone.
     */
    fun matchWindowMs(mine: Boolean, composedOnThisDevice: Boolean): Long =
        if (mine && !composedOnThisDevice) RELAY_CLOCK_MATCH_WINDOW_MS
        else SAME_CLOCK_MATCH_WINDOW_MS

    /**
     * Null for anything that is not delivered history — drafts, queued, outbox
     * and failed rows. A null direction is never guessed at: showing a received
     * SMS as one this account sent is worse than not restoring it.
     */
    fun directionOf(providerType: Int): Direction? = when (providerType) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> Direction.INCOMING
        Telephony.Sms.MESSAGE_TYPE_SENT -> Direction.OUTGOING
        else -> null
    }

    /**
     * The conversation address a stored row belongs to, or null when the row
     * cannot become a message at all.
     *
     * The address gate is deliberately the one the live receive path applies —
     * non-blank and nothing more. An alphanumeric sender id ("Google", a bank's
     * short name) becomes an ordinary conversation when it arrives live, so
     * refusing to rebuild those threads would leave a whole class of
     * conversations permanently unrestorable while reporting them as failures.
     */
    fun conversationAddress(record: ProviderSmsRecord): String? {
        if (record.body.isBlank() || record.body.length > MAX_BODY_CHARS) return null
        if (record.date <= 0) return null
        if (directionOf(record.type) == null) return null
        return PhoneNumberNormalizer.normalize(record.address).takeIf { it.isNotBlank() }
    }

    /** 64 bits of the row hash: enough to separate two contents under one id. */
    const val FINGERPRINT_KEY_CHARS = 16

    /**
     * The row's own facts, hashed the way the incoming ledger hashes them.
     *
     * Sharing that function is deliberate: the value written into
     * `processed_sms` here has to be the one [ProviderIdentityResolver] later
     * recomputes for the same provider row, or the claim reads as a conflicting
     * observation and rotates the provider epoch instead of matching.
     */
    fun fingerprintOf(record: ProviderSmsRecord): String =
        IncomingMessageIdentity.sourceFingerprint(
            PhoneNumberNormalizer.normalize(record.address),
            record.date,
            RelayContentCodec.encode(RelayContentCodec.text(record.body)),
        )

    /**
     * Identity of one stored row under the unique serverKey index.
     *
     * The provider id alone is not one. The AOSP `sms` table is `_id INTEGER
     * PRIMARY KEY` with no AUTOINCREMENT, so a deleted row's number comes back
     * on an unrelated message, and [MessageDao.insert] is INSERT OR REPLACE
     * against that unique index: a reused id silently deletes the message
     * restored under the old one, and it reappears attributed to the new
     * sender. Pairing the id with the row's own fingerprint makes reuse produce
     * a different key by itself, rather than relying on the live ingest path
     * having observed the reuse and rotated the provider epoch first — the
     * reusing message may never reach that path at all.
     *
     * The pair is also stable across runs, which is what makes a second restore
     * a no-op: the id is unique among the rows the store holds at any one
     * moment, and the fingerprint depends only on facts the store already has.
     */
    fun serverKey(providerId: Long, sourceFingerprint: String): String =
        "$SERVER_KEY_PREFIX$providerId:${sourceFingerprint.take(FINGERPRINT_KEY_CHARS)}"

    fun isRestored(serverKey: String?): Boolean =
        serverKey != null && serverKey.startsWith(SERVER_KEY_PREFIX)
}

/** One provider row resolved onto a conversation and ready to be written. */
data class RestoreCandidate(
    val providerId: Long,
    /** [HistoryRestorePlan.fingerprintOf] for this row; half of its identity. */
    val fingerprint: String,
    val cid: String,
    val body: String,
    val at: Long,
    val direction: HistoryRestorePlan.Direction,
) {
    val mine: Boolean get() = direction == HistoryRestorePlan.Direction.OUTGOING
    val serverKey: String get() = HistoryRestorePlan.serverKey(providerId, fingerprint)
}

/**
 * What a conversation already holds, so a rebuild adds only what is missing.
 *
 * Two questions, two answers. A row this restorer wrote before is settled by
 * its provider-id serverKey, which makes a repeat run a no-op. A row that
 * arrived through the normal receive path carries no such key, so it is settled
 * by content: same conversation, same direction, same text, close enough in
 * time.
 *
 * A content match *consumes* the local row it matched. Without that, someone
 * who really did send "네" three times would have two of them swallowed by the
 * single local copy that happened to survive.
 *
 * [selfSid] is this device's sid, which is what separates a send made in this
 * app's composer from one composed on another device and gateway-dispatched
 * here; the two carry timestamps from different clocks and cannot share a
 * tolerance.
 */
class RestoreDedupeIndex(existing: List<MessageRow>, selfSid: String) {

    private data class Key(val cid: String, val mine: Boolean, val body: String)

    /** A local row still available to absorb a stored one, with its own tolerance. */
    private data class Slot(val at: Long, val window: Long)

    private val restoredKeys: Set<String> = existing.mapNotNullTo(mutableSetOf()) { row ->
        row.serverKey?.takeIf { HistoryRestorePlan.isRestored(it) }
    }

    private val unmatched: Map<Key, MutableList<Slot>> = existing
        .groupBy { Key(it.cid, it.mine, it.plaintext) }
        .mapValues { (_, rows) ->
            rows.mapTo(mutableListOf()) { row ->
                Slot(
                    at = row.createdAt,
                    window = HistoryRestorePlan.matchWindowMs(
                        mine = row.mine,
                        composedOnThisDevice = row.senderSid == selfSid,
                    ),
                )
            }
        }

    /** True when [candidate] is already on this device; consumes the match. */
    fun isDuplicate(candidate: RestoreCandidate): Boolean {
        if (candidate.serverKey in restoredKeys) return true
        val slots = unmatched[Key(candidate.cid, candidate.mine, candidate.body)] ?: return false
        val closest = slots.withIndex().minByOrNull { abs(it.value.at - candidate.at) } ?: return false
        if (abs(closest.value.at - candidate.at) > closest.value.window) return false
        slots.removeAt(closest.index)
        return true
    }
}

/**
 * Rebuilds local conversations from this phone's own telephony store.
 *
 * Logging out clears the decrypted plaintext tables, and neither of the two
 * things that look like they would bring them back actually does: the relay
 * cannot say which direction a gateway uploaded a message in, so the receive
 * path consumes those rows as history without rendering them, and the startup
 * inbox import is one-directional, capped, and skipped for every row the
 * idempotency ledger already names. The system store has none of those
 * problems — it holds both directions and says which is which — so it is the
 * source this reads.
 *
 * Nothing here reaches the carrier or the relay. These messages were delivered
 * long ago and every one of them is already on the server; a re-send would
 * duplicate them on the carrier network and on every other device of this
 * account. That is also why this writes MessageRow directly rather than going
 * through IncomingMessageRepository, whose entire purpose is to pair a visible
 * row with a RelayOutbox upload.
 *
 * SMS only. MmsProvider reads a single message by id from the inbox and takes
 * its address from the MMS `from` field alone, so it cannot enumerate sent MMS
 * or state a direction; restoring MMS from it would silently mislabel every
 * sent picture message as received.
 */
object HistoryRestore {

    private const val TAG = "HistoryRestore"

    data class Outcome(
        val ok: Boolean,
        val restored: Int = 0,
        /** Already present locally — the count that makes a repeat run visibly safe. */
        val skipped: Int = 0,
        /** Stored rows that could not be turned into a message at all. */
        val failed: Int = 0,
        val conversations: Int = 0,
        /** The store stopped answering part-way; older history was never read. */
        val partial: Boolean = false,
        val error: String? = null,
    )

    internal data class ConversationSummary(
        val restored: Int,
        val skipped: Int,
        val failed: Int,
        /** Non-null only when this rebuild created the conversation. */
        val createdCid: String? = null,
        /** Newest restored message; the read stamp for a conversation created here. */
        val latest: Long = 0L,
    )

    /**
     * Rebuild every conversation the system store can account for.
     *
     * Runs on [Dispatchers.IO] and cooperates with cancellation between
     * conversations — the logout wipe depends on that. [onProgress] is invoked
     * on that worker, so a UI caller has to marshal it.
     */
    suspend fun restore(
        context: Context,
        creds: SavedCredentials,
        db: AppDatabase = AppDatabase.get(context),
        limit: Int = SmsProvider.DEFAULT_HISTORY_LIMIT,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Without this the provider query throws, is swallowed, and an
            // empty read would be reported as "0 messages found".
            return@withContext Outcome(
                ok = false,
                error = "SMS 읽기 권한이 없어 이 기기의 메시지 저장소를 읽을 수 없습니다.",
            )
        }

        val history = SmsProvider.allMessages(context, limit)
        if (!history.complete && history.records.isEmpty()) {
            // Nothing was read and the read failed. Reporting "no SMS to
            // restore" here would be a false statement about the user's phone.
            return@withContext Outcome(
                ok = false,
                partial = true,
                error = "이 기기의 메시지 저장소를 읽지 못했습니다. 기본 문자 앱 상태를 확인한 뒤 다시 시도하세요.",
            )
        }

        // The namespace the provider ids in this read belong to. Taken once:
        // rotation is reuse evidence the live ingest path owns, and a rebuild
        // must not advance it behind that path's back.
        val providerEpoch = db.carrierProviderStateDao().currentEpoch(ProviderIdentity.SMS)

        var failed = 0
        val byPhone = linkedMapOf<String, MutableList<ProviderSmsRecord>>()
        for (record in history.records) {
            val phone = HistoryRestorePlan.conversationAddress(record)
            if (phone != null) {
                byPhone.getOrPut(phone) { mutableListOf() } += record
            } else {
                failed += 1
            }
        }

        var restored = 0
        var skipped = 0
        var conversations = 0
        onProgress?.invoke(0, byPhone.size)
        byPhone.entries.forEachIndexed { index, (phone, records) ->
            coroutineContext.ensureActive()
            val summary = try {
                restoreConversation(db, creds, providerEpoch, phone, records)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One unwritable conversation must not abandon the rest; the
                // transaction rolled it back whole, so a retry redoes it.
                Log.e(TAG, "restore failed for ${PhoneNumberNormalizer.redact(phone)}", e)
                ConversationSummary(restored = 0, skipped = 0, failed = records.size)
            }
            restored += summary.restored
            skipped += summary.skipped
            failed += summary.failed
            if (summary.restored > 0) conversations += 1
            // Outside the transaction: the stamp lives in SharedPreferences and
            // is not part of the rollback. Only a conversation this rebuild
            // created gets one — an existing thread already carries the user's
            // real read position, and history restored under it is older.
            summary.createdCid?.takeIf { summary.latest > 0 }?.let {
                LastOpened.setIfAbsent(context, it, summary.latest)
            }
            onProgress?.invoke(index + 1, byPhone.size)
        }
        Log.i(
            TAG,
            "history restore restored=$restored skipped=$skipped failed=$failed " +
                "conversations=$conversations partial=${!history.complete}",
        )
        Outcome(
            ok = true,
            restored = restored,
            skipped = skipped,
            failed = failed,
            conversations = conversations,
            partial = !history.complete,
        )
    }

    /**
     * One conversation, in a single transaction so a thread is never left
     * behind without the history that justified creating it.
     *
     * An existing thread for the number is reused — [ThreadDao.getByPhone]
     * already prefers the relay-assigned cid over a provisional one — and only
     * a number with no thread at all gets a new `local_` conversation, created
     * exactly the way the incoming path creates it. Unlike the incoming path
     * this queues no outbox row, so nothing here would later merge that
     * conversation into the relay cid; [ThreadDao.provisionalByPhone] and the
     * bridge's thread sync do it by number instead.
     */
    private suspend fun restoreConversation(
        db: AppDatabase,
        creds: SavedCredentials,
        providerEpoch: Long,
        phone: String,
        records: List<ProviderSmsRecord>,
    ): ConversationSummary = db.withTransaction {
        val existing = db.threadDao().getByPhone(phone)
        val thread = existing ?: SmsThread(
            cid = "local_${UUID.randomUUID().toString().replace("-", "")}",
            phoneNumber = phone,
            serverName = null,
        ).also { db.threadDao().upsert(it) }

        val index = RestoreDedupeIndex(db.messageDao().getForCid(thread.cid), creds.sid)
        var restored = 0
        var skipped = 0
        var failed = 0
        var latest = 0L
        for (record in records) {
            val direction = HistoryRestorePlan.directionOf(record.type)
            if (direction == null) {
                failed += 1
                continue
            }
            val fingerprint = HistoryRestorePlan.fingerprintOf(record)
            val candidate = RestoreCandidate(
                providerId = record.id,
                fingerprint = fingerprint,
                cid = thread.cid,
                body = record.body,
                at = record.date,
                direction = direction,
            )
            if (index.isDuplicate(candidate)) {
                skipped += 1
                continue
            }
            db.messageDao().insert(
                MessageRow(
                    cid = candidate.cid,
                    seq = HistoryRestorePlan.RESTORED_SEQ,
                    senderSid = if (candidate.mine) creds.sid else "",
                    plaintext = candidate.body,
                    createdAt = candidate.at,
                    mine = candidate.mine,
                    contentType = RelayContentCodec.TYPE_TEXT,
                    serverKey = candidate.serverKey,
                    // The store keeps a sent row only once the carrier accepted
                    // it, so a restored outgoing message has already left this
                    // device. There is no pending work here to represent, and
                    // "queued" would invite a retry of a message that was sent
                    // years ago.
                    carrierStatus = if (candidate.mine) "sent" else "none",
                ),
            )
            if (!candidate.mine) claimInboxRow(db, providerEpoch, record.id, fingerprint)
            restored += 1
            latest = maxOf(latest, candidate.at)
        }
        // Ordering key for the thread list. MAX() inside the query means a
        // rebuild can only ever move a conversation to where its own newest
        // message puts it, never backwards.
        if (latest > 0) db.threadDao().touch(thread.cid, latest)
        ConversationSummary(
            restored = restored,
            skipped = skipped,
            failed = failed,
            createdCid = if (existing == null) thread.cid else null,
            latest = latest,
        )
    }

    /**
     * Claims a rendered inbox row in the incoming idempotency ledger.
     *
     * The bridge's startup/reconnect sweep gates only on this ledger. A row
     * this rebuild rendered but the bridge never ingested passes that gate, and
     * the second visible copy it inserts has no dedupe path that could ever
     * find the restored one. The claim is written in the same transaction as
     * the message, so the row is never visible without it.
     *
     * The ledger entry also suppresses the relay upload that sweep would have
     * performed, which is the same rule the rest of this file follows: a
     * rebuild renders history, it never sends it anywhere.
     *
     * [fingerprint] is the ledger's own hash of the row, so a later reuse of
     * this provider id still reads as reuse to [ProviderIdentityResolver] and
     * still rotates the namespace.
     */
    private suspend fun claimInboxRow(
        db: AppDatabase,
        providerEpoch: Long,
        providerId: Long,
        fingerprint: String,
    ) {
        db.processedSmsDao().insert(
            ProcessedSms(
                providerEpoch = providerEpoch,
                providerId = providerId,
                sourceFingerprint = fingerprint,
            ),
        )
    }
}

/**
 * Process-scoped driver for [HistoryRestore].
 *
 * A rebuild walks every conversation on the phone and can run for a while.
 * Started from a composition scope it would die with that composition — a
 * screen rotation recreates the activity — leaving no error, no result and a
 * half-rebuilt database with nothing saying so. It runs here instead and the UI
 * only observes [state].
 *
 * A run does not survive process death; [state] resets and the next run redoes
 * whatever the interrupted one had not yet written, skipping what it had.
 */
object HistoryRestoreRunner {

    private const val TAG = "HistoryRestoreRunner"

    data class State(
        /** Non-null exactly while a rebuild is in flight. */
        val progress: String? = null,
        /** Terminal message for the user; the UI clears it with [consumeResult]. */
        val result: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Start a rebuild. Returns false when one is already running — the caller
     * must say so, because nothing is queued.
     */
    @Synchronized
    fun start(context: Context, creds: SavedCredentials): Boolean {
        if (job?.isActive == true) return false
        _state.value = State(progress = "이 기기의 메시지 저장소를 읽는 중…")
        val app = context.applicationContext
        job = scope.launch {
            val outcome = try {
                HistoryRestore.restore(app, creds) { done, total ->
                    _state.value = _state.value.copy(progress = "대화 $done/$total 복원 중…")
                }
            } catch (e: CancellationException) {
                // Stopped on purpose — by [cancelAndAwait] before a logout wipe.
                // A cancelled run has no result to report and must not leave a
                // failure message pointing at a database that is being cleared.
                _state.value = State()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "history restore failed", e)
                HistoryRestore.Outcome(ok = false, error = e.message ?: "알 수 없는 오류")
            }
            _state.value = State(result = message(outcome))
        }
        return true
    }

    /**
     * Stop a rebuild and wait until it has stopped writing.
     *
     * The logout wipe is only one-way while nothing is inserting behind it. A
     * rebuild is process-scoped and the logout button is not gated on it, so
     * without this the tables come back — with decrypted plaintext and a
     * senderSid from the session that was just revoked — for every conversation
     * the run had not yet reached. Joining, not just cancelling, is what makes
     * the wipe the last write.
     */
    suspend fun cancelAndAwait() {
        val running = synchronized(this) { job.also { job = null } } ?: return
        running.cancelAndJoin()
        _state.value = State()
    }

    /** Acknowledge the terminal message so it is shown once. */
    fun consumeResult() {
        _state.value = _state.value.copy(result = null)
    }

    internal fun message(outcome: HistoryRestore.Outcome): String {
        if (!outcome.ok) {
            return "대화 복원 실패: ${outcome.error ?: "알 수 없는 오류"}"
        }
        if (outcome.restored == 0 && outcome.skipped == 0 && outcome.failed == 0) {
            return if (outcome.partial) {
                "메시지 저장소를 끝까지 읽지 못해 복원할 SMS를 찾지 못했습니다. 다시 시도해 주세요."
            } else {
                "이 기기의 메시지 저장소에 복원할 SMS가 없습니다."
            }
        }
        return buildString {
            append("대화 ${outcome.conversations}개에 ${outcome.restored}건을 복원했습니다")
            append(" · 이미 있어 건너뜀 ${outcome.skipped}건")
            if (outcome.failed > 0) append(" · 복원 실패 ${outcome.failed}건")
            append(". MMS는 복원되지 않습니다.")
            if (outcome.partial) {
                append(" 메시지 저장소를 끝까지 읽지 못해 오래된 메시지 일부는 빠졌을 수 있습니다.")
            }
        }
    }
}
