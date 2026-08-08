package com.yunjelee.securemsg

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device shared block rules (keywords + senders).
 *
 * The relay server stores the account-wide rule set. Local Room rows stay
 * user-editable and are pushed on sync; the authoritative server set is
 * cached in SharedPreferences and applied by BlocklistManager next to the
 * Room rows. A rule added on any device reaches every other device through
 * the server (+ `blocklist_updated` socket fan-out).
 */
object BlocklistSync {

    private const val TAG = "BlocklistSync"
    private const val PREFS = "securemsg_block_sync"
    private const val KEY = "shared_rules"
    private val operationMutex = Mutex()

    data class SharedRules(
        val keywords: List<String>,
        val senders: List<String>,
        /** "type|value" -> server rule id (needed for removal). */
        val ids: Map<String, Long>,
    )

    fun load(context: Context = SecureMsgApp.instance): SharedRules {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return SharedRules(emptyList(), emptyList(), emptyMap())
        return parseRules(raw)
    }

    /** Remove account-scoped cache when this device identity is forgotten. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Pure parser (unit-testable without Android context). */
    fun parseRules(raw: String): SharedRules {
        return try {
            val obj = JSONObject(raw)
            val keywords = mutableListOf<String>()
            val senders = mutableListOf<String>()
            val ids = mutableMapOf<String, Long>()
            val arr = obj.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val type = r.optString("type")
                val value = r.optString("value")
                if (value.isBlank()) continue
                when (type) {
                    "keyword" -> keywords += value
                    "sender" -> senders += value
                }
                ids["$type|$value"] = r.optLong("id")
            }
            SharedRules(keywords, senders, ids)
        } catch (e: Exception) {
            SharedRules(emptyList(), emptyList(), emptyMap())
        }
    }

    private fun save(context: Context, arr: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, JSONObject().put("rules", arr).toString()).apply()
    }

    /** Make an explicit local add durable as "new" even if its first sync fails. */
    private fun forgetCachedRule(context: Context, type: String, value: String) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return
        try {
            val source = JSONObject(raw).optJSONArray("rules") ?: return
            val kept = JSONArray()
            for (i in 0 until source.length()) {
                val rule = source.optJSONObject(i) ?: continue
                if (rule.optString("type") != type || rule.optString("value") != value) {
                    kept.put(rule)
                }
            }
            save(context, kept)
        } catch (_: Exception) {
            // A malformed cache is already treated as empty by load().
        }
    }

    /**
     * Push local-only rules, then mirror the server set (server wins).
     *
     * Without the prune step a rule deleted on another device would survive
     * in this device's local Room rows and get re-pushed on the next sync —
     * the "deletion resurrects everywhere" bug reported in issue #1.
     */
    suspend fun sync(context: Context, api: RelayApi): Boolean =
        operationMutex.withLock { syncLocked(context, api) }

    private suspend fun syncLocked(
        context: Context,
        api: RelayApi,
        locallyAddedKeys: Set<String> = emptySet(),
    ): Boolean {
        val previouslyShared = load(context).ids.keys
        var resp = api.listBlockRules()
        if (!resp.optBoolean("ok")) return false
        val present = mutableSetOf<String>()
        collectKeys(resp, present)

        val db = AppDatabase.get(context)
        val failedKeywordPushes = mutableSetOf<String>()
        val failedSenderPushes = mutableSetOf<String>()
        val keywordRows = db.blocklistDao().getAll()
        val senderRows = db.blockedSenderDao().getAll()
        for (value in pushCandidates(
            keywordRows.map { it.keyword },
            "keyword",
            present,
            previouslyShared,
            locallyAddedKeys,
        )) {
            val key = "keyword|$value"
            val r = api.addBlockRule("keyword", value)
            if (r.optBoolean("ok")) present.add(key) else failedKeywordPushes += value
        }
        for (value in pushCandidates(
            senderRows.map { it.phoneNumber },
            "sender",
            present,
            previouslyShared,
            locallyAddedKeys,
        )) {
            val key = "sender|$value"
            val r = api.addBlockRule("sender", value)
            if (r.optBoolean("ok")) present.add(key) else failedSenderPushes += value
        }

        // Re-pull so the cache also contains rules pushed from other devices.
        resp = api.listBlockRules()
        if (!resp.optBoolean("ok")) return false
        val arr = resp.optJSONArray("rules") ?: JSONArray()

        // Server wins: drop local rules that were deleted on another device.
        // Preserve only rules whose own push failed. A failure for new rule B
        // must not keep remotely deleted rule A alive and erase A's baseline.
        val serverKeywords = mutableSetOf<String>()
        val serverSenders = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            when (r.optString("type")) {
                "keyword" -> serverKeywords += r.optString("value")
                "sender" -> serverSenders += r.optString("value")
            }
        }
        val keywordsToPrune = pruneCandidates(
            keywordRows.map { it.keyword },
            serverKeywords,
            failedKeywordPushes,
        ).toSet()
        for (row in keywordRows) {
            if (row.keyword in keywordsToPrune) db.blocklistDao().delete(row)
        }
        val sendersToPrune = pruneCandidates(
            senderRows.map { it.phoneNumber },
            serverSenders,
            failedSenderPushes,
        ).toSet()
        for (sender in senderRows) {
            if (sender.phoneNumber in sendersToPrune) db.blockedSenderDao().delete(sender)
        }

        // Save only after pruning. If Room reconciliation throws, retain the
        // old baseline so a remotely deleted local row cannot be resurrected.
        save(context, arr)
        Log.i(TAG, "shared block rules synced: ${arr.length()} rules")
        return true
    }

    /** Pure: local values missing from the authoritative server set. */
    fun pruneCandidates(
        local: List<String>,
        serverValues: Set<String>,
        failedPushValues: Set<String> = emptySet(),
    ): List<String> = local.filter { it !in serverValues && it !in failedPushValues }

    /**
     * Local values safe to push. A value in the previous shared cache but no
     * longer on the server was deleted remotely and must not be resurrected.
     */
    fun pushCandidates(
        local: List<String>,
        type: String,
        serverKeys: Set<String>,
        previouslySharedKeys: Set<String>,
        locallyAddedKeys: Set<String> = emptySet(),
    ): List<String> = local.filter { value ->
        val key = "$type|$value"
        key !in serverKeys && (key !in previouslySharedKeys || key in locallyAddedKeys)
    }.distinct()

    /** Canonicalize only newly added sender rules. Existing Room/server rows
     * remain untouched and continue to match through SenderMatcher. */
    fun canonicalRuleValue(type: String, value: String): String =
        if (type == "sender") PhoneNumberNormalizer.normalize(value) else value

    /** Insert a local rule and complete its sync as one serialized operation. */
    suspend fun addShared(context: Context, api: RelayApi, type: String, value: String): Boolean =
        operationMutex.withLock {
            val canonicalValue = canonicalRuleValue(type, value)
            val key = "$type|$canonicalValue"
            if (!insertLocal(context, type, canonicalValue)) return@withLock false
            forgetCachedRule(context, type, canonicalValue)
            syncLocked(context, api, locallyAddedKeys = setOf(key))
        }

    /** Serialize an offline-only insert against any sync already in flight. */
    suspend fun addLocal(context: Context, type: String, value: String): Boolean =
        operationMutex.withLock {
            val canonicalValue = canonicalRuleValue(type, value)
            insertLocal(context, type, canonicalValue).also { inserted ->
                if (inserted) forgetCachedRule(context, type, canonicalValue)
            }
        }

    /** Remove local/server copies and refresh as one serialized operation. */
    suspend fun removeShared(context: Context, api: RelayApi, type: String, value: String) =
        operationMutex.withLock {
            deleteLocal(context, type, value)
            load(context).ids["$type|$value"]?.let { api.removeBlockRule(it) }
            syncLocked(context, api)
        }

    /** Serialize an offline-only deletion against any sync already in flight. */
    suspend fun removeLocal(context: Context, type: String, value: String) =
        operationMutex.withLock { deleteLocal(context, type, value) }

    private suspend fun insertLocal(context: Context, type: String, value: String): Boolean {
        val db = AppDatabase.get(context)
        return when (type) {
            "keyword" -> {
                db.blocklistDao().insert(BlockKeyword(keyword = value))
                true
            }
            "sender" -> {
                db.blockedSenderDao().insert(BlockedSender(value))
                true
            }
            else -> false
        }
    }

    private suspend fun deleteLocal(context: Context, type: String, value: String) {
        val db = AppDatabase.get(context)
        when (type) {
            "keyword" -> db.blocklistDao().getAll()
                .filter { it.keyword == value }
                .forEach { db.blocklistDao().delete(it) }
            "sender" -> db.blockedSenderDao().getAll()
                .filter { it.phoneNumber == value }
                .forEach { db.blockedSenderDao().delete(it) }
        }
    }

    private fun collectKeys(resp: JSONObject, into: MutableSet<String>) {
        val arr = resp.optJSONArray("rules") ?: return
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            into += "${r.optString("type")}|${r.optString("value")}"
        }
    }
}
