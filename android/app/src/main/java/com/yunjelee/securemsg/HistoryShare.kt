package com.yunjelee.securemsg

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * History backfill for a device that registered after the fact.
 *
 * A relay envelope carries one wrapped message key per device that existed when
 * the message was sent, so a later device cannot read anything older than
 * itself. This driver walks the account's conversations, unwraps every message
 * key this device holds and re-wraps the ones the target is missing. The relay
 * only ever sees wrapped keys, exactly as it does for a normal send.
 *
 * A run is idempotent and resumable: the work is derived from what the target
 * is still missing, so re-running after an interruption does only what remains.
 */
object HistoryShare {

    private const val TAG = "HistoryShare"

    /** Server allows 1..1000 per offline pull; 500 matches the sync loop. */
    private const val PULL_LIMIT = 500

    /**
     * A backfill spends one missing-keys probe plus at least one messages-pull
     * per conversation, and those are exactly what the relay's per-minute sync
     * budget counts. Unpaced, an account with a few hundred conversations spent
     * that budget partway down the list; every conversation after it ended in a
     * 429 the walk reported as a plain failure, and the retry the user was told
     * to run burned the same budget on the same head of the list, so the tail
     * was never reachable at all. The pace keeps a normal run under the
     * tightest budget a deployed relay has enforced (2/s per scope).
     */
    private const val CONVERSATION_PACE_MS = 600L

    /**
     * Wait between retries of a rate-limited call. Retry-After never reaches
     * the parsed body, so the wait is fixed and repeated; [RATE_LIMIT_RETRIES]
     * of them outlast the relay's 60 s window, which is what has to slide past
     * before the budget frees up.
     */
    private const val RATE_LIMIT_BACKOFF_MS = 5_000L
    private const val RATE_LIMIT_RETRIES = 12

    data class Outcome(
        val ok: Boolean,
        val shared: Int = 0,
        val skipped: Int = 0,
        val conversations: Int = 0,
        val error: String? = null,
    )

    internal data class ConversationSummary(
        val shared: Int,
        val skipped: Int,
        val error: String?,
    )

    /**
     * Share every readable message key with [targetSid].
     *
     * The target's public key comes from the local pinned trust store and
     * nowhere else: a key read from a relay response would let a hostile server
     * name a device it controls and receive the whole history in the clear.
     * A target this device has not pinned is therefore refused, not fetched.
     *
     * [activeSids] is the caller's freshly verified key directory. A pin
     * outlives revocation by design, so the pinned key cannot say whether the
     * target is still part of the account; without this set a revoked device
     * would be refused only by the relay, and a hostile or forked relay simply
     * would not refuse it.
     *
     * Runs on [Dispatchers.IO] and cooperates with cancellation between calls.
     * [onProgress] is invoked on that worker, so a UI caller must marshal it.
     */
    suspend fun share(
        context: Context,
        creds: SavedCredentials,
        api: RelayApi,
        targetSid: String,
        activeSids: Set<String>,
        db: AppDatabase = AppDatabase.get(context),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        if (targetSid.isBlank() || targetSid == creds.sid) {
            return@withContext Outcome(ok = false, error = "다른 기기를 선택해 주세요.")
        }
        if (targetSid !in activeSids) {
            return@withContext Outcome(
                ok = false,
                error = "현재 승인된 기기가 아닙니다. 해지된 기기에는 대화 기록을 공유할 수 없습니다.",
            )
        }
        val target = db.deviceTrustDao().getPin(targetSid)
            ?: return@withContext Outcome(
                ok = false,
                error = "이 기기에 고정되지 않은 기기입니다. 기기 보안을 새로고침해 승인 상태를 확인한 뒤 다시 시도하세요.",
            )
        if (target.accountUid != creds.uid.toLong()) {
            return@withContext Outcome(ok = false, error = "다른 계정의 기기에는 대화 기록을 공유할 수 없습니다.")
        }

        val listing = api.listConversations()
        if (!listing.optBoolean("ok")) {
            return@withContext Outcome(
                ok = false,
                error = listing.optString("error", "대화 목록을 불러오지 못했습니다."),
            )
        }
        val cids = conversationIds(listing)
        val pins = PinnedKeys(db)
        var shared = 0
        var skipped = 0
        var failures = 0
        onProgress?.invoke(0, cids.size)
        cids.forEachIndexed { index, cid ->
            coroutineContext.ensureActive()
            if (index > 0) delay(CONVERSATION_PACE_MS)
            val summary = shareConversation(api, cid, targetSid) { row ->
                rewrapRow(row, pins, creds, target.pubKey)
            }
            shared += summary.shared
            skipped += summary.skipped
            if (summary.error != null) failures += 1
            Log.i(
                TAG,
                "history share cid=$cid target=$targetSid shared=${summary.shared} " +
                    "skipped=${summary.skipped} error=${summary.error ?: "none"}",
            )
            onProgress?.invoke(index + 1, cids.size)
        }
        Outcome(
            ok = failures == 0,
            shared = shared,
            skipped = skipped,
            conversations = cids.size,
            error = if (failures == 0) null else "대화 ${failures}개에서 공유에 실패했습니다. 다시 시도하면 남은 것만 처리합니다.",
        )
    }

    private fun conversationIds(listing: JSONObject): List<String> {
        val rows = listing.optJSONArray("conversations") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.optString("cid")?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * One conversation, walked forward by sequence.
     *
     * missing-keys is capped by the server, so it says where the first gap is
     * and never where the work ends: a whole page the target is missing can be
     * unreadable here while readable messages lie beyond it. The walk therefore
     * runs to the end of the conversation and decides per row, from the stored
     * envelope, whether the target already has a key.
     *
     * [rewrap] returns null for a row this device cannot open; those are
     * counted, not retried. Every relay failure ends the conversation with an
     * error instead — a transient HTTP error is not evidence about a key.
     * A spent sync budget is the one exception: it says nothing about this
     * conversation, so it is waited out rather than reported.
     *
     * [backoffMs] is that wait; only a test shortens it, there being no
     * virtual-time dispatcher on the unit-test classpath.
     */
    internal suspend fun shareConversation(
        api: RelayApi,
        cid: String,
        targetSid: String,
        backoffMs: Long = RATE_LIMIT_BACKOFF_MS,
        rewrap: suspend (JSONObject) -> CryptoUtil.EnvelopeKey?,
    ): ConversationSummary {
        val probe = pastRateLimit(backoffMs) { api.missingKeys(cid, targetSid) }
        if (!probe.optBoolean("ok")) {
            return ConversationSummary(0, 0, probe.optString("error", "missing-keys 조회 실패"))
        }
        val seqs = probe.optJSONArray("seqs") ?: JSONArray()
        val firstMissing = (0 until seqs.length())
            .map { seqs.optInt(it, -1) }
            .filter { it > 0 }
            .minOrNull()
            ?: return ConversationSummary(0, 0, null)

        var shared = 0
        var skipped = 0
        // `since` is exclusive; start on the last sequence the target already has.
        var cursor = firstMissing - 1
        val batch = mutableListOf<SharedKeyEntry>()

        // Returns an error string, or null when the batch was accepted.
        suspend fun flush(): String? {
            if (batch.isEmpty()) return null
            val result = pastRateLimit(backoffMs) { api.shareKeys(cid, targetSid, batch.toList()) }
            batch.clear()
            if (!result.optBoolean("ok")) return result.optString("error", "share-keys 실패")
            // "skipped" from the server means the target already had that key
            // (another device shared it first) — not a failure here.
            shared += result.optInt("added")
            return null
        }

        while (true) {
            coroutineContext.ensureActive()
            val response = pastRateLimit(backoffMs) { api.fetchMessages(cid, cursor, PULL_LIMIT) }
            if (!response.optBoolean("ok")) {
                return ConversationSummary(
                    shared, skipped, response.optString("error", "메시지 조회 실패"),
                )
            }
            val rows = response.optJSONArray("messages")
                ?: return ConversationSummary(shared, skipped, "메시지 응답 형식이 올바르지 않습니다.")
            if (rows.length() == 0) break
            val pageStart = cursor
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val seq = row.optInt("seq", -1)
                if (seq <= cursor) continue
                cursor = seq
                val keys = row.optJSONObject("payload")?.optJSONObject("keys")
                if (keys != null && keys.has(targetSid)) continue
                val rewrapped = rewrap(row)
                if (rewrapped == null) {
                    skipped += 1
                    continue
                }
                batch += SharedKeyEntry(seq, rewrapped.ek, rewrapped.n)
                if (batch.size >= RelayApi.MAX_SHARE_ENTRIES) {
                    flush()?.let { return ConversationSummary(shared, skipped, it) }
                }
            }
            // A page that advances nothing would be requested forever.
            if (cursor == pageStart) {
                return ConversationSummary(shared, skipped, "서버가 같은 구간을 반복해 중단했습니다.")
            }
            if (rows.length() < PULL_LIMIT) break
        }
        flush()?.let { return ConversationSummary(shared, skipped, it) }
        return ConversationSummary(shared, skipped, null)
    }

    /**
     * [call] repeated until the relay stops answering 429, or until the retries
     * run out and the caller gets the 429 body to report.
     */
    private suspend fun pastRateLimit(
        backoffMs: Long,
        call: () -> JSONObject,
    ): JSONObject {
        var attempts = 0
        while (true) {
            coroutineContext.ensureActive()
            val response = call()
            if (response.optInt("_http_status") != 429) return response
            if (attempts >= RATE_LIMIT_RETRIES) return response
            attempts += 1
            delay(backoffMs)
        }
    }

    private suspend fun rewrapRow(
        row: JSONObject,
        pins: PinnedKeys,
        creds: SavedCredentials,
        targetPubKey: String,
    ): CryptoUtil.EnvelopeKey? {
        val payload = row.optJSONObject("payload") ?: return null
        val envelope = try {
            CryptoUtil.envelopeFromJson(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed envelope at seq=${row.optInt("seq", -1)}", e)
            return null
        }
        val myKey = envelope.keys[creds.sid] ?: return null
        // Whoever sealed this device's entry must be pinned locally. Taking
        // `sender_pub_key` from the row would let a hostile relay name a key it
        // controls and have this device re-wrap history under it.
        val sealerSid = myKey.by ?: row.optString("sender_sid")
        val sealerPubKey = pins.pubKey(sealerSid) ?: return null
        return CryptoUtil.rewrapMessageKey(
            envelope, creds.sid, creds.keypair, sealerPubKey, targetPubKey,
        )
    }

    /** Per-run memo of trust-store lookups; the pinned key is the only source. */
    private class PinnedKeys(private val db: AppDatabase) {
        private val cache = mutableMapOf<String, String?>()

        suspend fun pubKey(sid: String): String? {
            if (sid.isBlank()) return null
            if (!cache.containsKey(sid)) {
                cache[sid] = db.deviceTrustDao().getPin(sid)?.pubKey
            }
            return cache[sid]
        }
    }
}

/**
 * Process-scoped driver for [HistoryShare].
 *
 * A backfill walks every conversation and can run for minutes. Started from a
 * composition scope it would die with that composition — a screen rotation
 * recreates the activity — leaving no error, no result and no trace that a run
 * had ever started. It runs here instead and the UI only observes [state].
 *
 * A run does not survive process death; [state] resets and the next run redoes
 * whatever the interrupted one had not yet uploaded.
 */
object HistoryShareRunner {

    private const val TAG = "HistoryShareRunner"

    data class State(
        /** Label of the device being backfilled; non-null exactly while a run is in flight. */
        val runningLabel: String? = null,
        val progress: String? = null,
        /** Terminal message for the user; the UI clears it with [consumeResult]. */
        val result: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Start a backfill of [targetSid]. Returns false when one is already in
     * flight — the caller must tell the user, because nothing is queued.
     */
    @Synchronized
    fun start(
        context: Context,
        creds: SavedCredentials,
        targetSid: String,
        targetLabel: String,
    ): Boolean {
        if (_state.value.runningLabel != null) return false
        _state.value = State(runningLabel = targetLabel, progress = "기기 보안을 확인하는 중…")
        val app = context.applicationContext
        scope.launch {
            val outcome = try {
                run(app, creds, targetSid)
            } catch (e: Exception) {
                Log.w(TAG, "history share failed", e)
                HistoryShare.Outcome(ok = false, error = e.message ?: "알 수 없는 오류")
            }
            _state.value = State(result = message(targetLabel, outcome))
        }
        return true
    }

    /** Acknowledge the terminal message so it is shown once. */
    fun consumeResult() {
        _state.value = _state.value.copy(result = null)
    }

    private suspend fun run(
        app: Context,
        creds: SavedCredentials,
        targetSid: String,
    ): HistoryShare.Outcome {
        val db = AppDatabase.get(app)
        val api = RelayApi(ServerConfig.url(app)).also { it.token = creds.token }
        // The same directory gate the send path applies before it encrypts to a
        // directory key (SmsBridgeService.validateTrustedRecipients). A warned
        // directory — legacy TOFU included — may name a device the relay chose,
        // and sharing hands that device every message this one can open.
        val view = DeviceSecurityController(
            RelayTrustedDeviceApi(api), creds, DeviceTrustRepository(db),
        ).refresh()
        if (view.serverUnsupported || view.selfPending || view.error != null ||
            view.trustWarning != null
        ) {
            Log.e(TAG, "History share directory refresh rejected: $view")
            return HistoryShare.Outcome(
                ok = false,
                error = view.trustWarning ?: view.error
                    ?: "이 서버/계정 상태에서는 기기 신뢰를 확인할 수 없습니다.",
            )
        }
        return HistoryShare.share(app, creds, api, targetSid, view.activeSids, db) { done, total ->
            _state.value = _state.value.copy(progress = "대화 $done/$total 처리 중…")
        }
    }

    private fun message(targetLabel: String, outcome: HistoryShare.Outcome): String {
        if (!outcome.ok) {
            return "이전 대화 공유 실패: ${outcome.error ?: "알 수 없는 오류"} " +
                "(${outcome.shared}건 공유됨)"
        }
        val skipped = if (outcome.skipped > 0) {
            " · 이 기기가 열 수 없는 ${outcome.skipped}건 제외"
        } else ""
        return "${targetLabel}에 이전 대화 키 ${outcome.shared}건을 공유했습니다$skipped."
    }
}
