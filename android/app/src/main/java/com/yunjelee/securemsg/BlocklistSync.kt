package com.yunjelee.securemsg

import android.content.Context
import android.util.Log
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

    data class SharedRules(
        val keywords: List<String>,
        val senders: List<String>,
        /** "type|value" -> server rule id (needed for removal). */
        val ids: Map<String, Long>,
    )

    fun load(context: Context = SecureMsgApp.instance): SharedRules {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return SharedRules(emptyList(), emptyList(), emptyMap())
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
            Log.w(TAG, "failed to parse cached shared rules", e)
            SharedRules(emptyList(), emptyList(), emptyMap())
        }
    }

    private fun save(context: Context, arr: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, JSONObject().put("rules", arr).toString()).apply()
    }

    /** Push local-only rules, then replace the cache with the server set. */
    suspend fun sync(context: Context, api: RelayApi): Boolean {
        var resp = api.listBlockRules()
        if (!resp.optBoolean("ok")) return false
        val present = mutableSetOf<String>()
        collectKeys(resp, present)

        val db = AppDatabase.get(context)
        for (row in db.blocklistDao().getAll()) {
            if ("keyword|${row.keyword}" in present) continue
            val r = api.addBlockRule("keyword", row.keyword)
            if (r.optBoolean("ok")) present.add("keyword|${row.keyword}")
        }
        for (sender in db.blockedSenderDao().getAll()) {
            if ("sender|${sender.phoneNumber}" in present) continue
            val r = api.addBlockRule("sender", sender.phoneNumber)
            if (r.optBoolean("ok")) present.add("sender|${sender.phoneNumber}")
        }

        // Re-pull so the cache also contains rules pushed from other devices.
        resp = api.listBlockRules()
        if (!resp.optBoolean("ok")) return false
        val arr = resp.optJSONArray("rules") ?: JSONArray()
        save(context, arr)
        Log.i(TAG, "shared block rules synced: ${arr.length()} rules")
        return true
    }

    /** Remove a rule server-side (by type+value) and refresh the cache. */
    suspend fun removeShared(context: Context, api: RelayApi, type: String, value: String) {
        val id = load(context).ids["$type|$value"] ?: return
        api.removeBlockRule(id)
        sync(context, api)
    }

    private fun collectKeys(resp: JSONObject, into: MutableSet<String>) {
        val arr = resp.optJSONArray("rules") ?: return
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            into += "${r.optString("type")}|${r.optString("value")}"
        }
    }
}
