package com.yunjelee.securemsg

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RelayApi(
    val baseUrl: String,
    private val http: OkHttpClient = HTTP,
) {

    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')

    private val json = "application/json".toMediaType()

    var token: String? = null

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$normalizedBaseUrl$path")
            .post(body.toString().toRequestBody(json))
            .apply { token?.let { addHeader("Authorization", "Bearer $it") } }
            .build()
        http.newCall(req).execute().use { resp ->
            return parseResponse(resp.code, resp.isSuccessful, resp.body?.string().orEmpty())
        }
    }

    private fun get(path: String): JSONObject {
        val req = Request.Builder()
            .url("$normalizedBaseUrl$path")
            .apply { token?.let { addHeader("Authorization", "Bearer $it") } }
            .build()
        http.newCall(req).execute().use { resp ->
            return parseResponse(resp.code, resp.isSuccessful, resp.body?.string().orEmpty())
        }
    }

    private fun parseResponse(status: Int, successful: Boolean, text: String): JSONObject {
        val body = try {
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (_: Exception) {
            JSONObject().put("error", "invalid server response")
        }
        if (!successful || !body.optBoolean("ok")) {
            body.put("ok", false)
            body.put("_http_status", status)
            if (body.optString("error").isBlank()) {
                body.put("error", "HTTP $status")
            }
        }
        return body
    }

    fun register(username: String, pwHash: String): JSONObject =
        post("/api/register", JSONObject().put("username", username).put("pw_hash", pwHash))

    fun login(username: String, pwHash: String): JSONObject =
        post("/api/login", JSONObject().put("username", username).put("pw_hash", pwHash))

    fun deviceRegister(
        username: String, pwHash: String, deviceName: String,
        pubKey: String, sigPub: String,
    ): JSONObject = JSONObject()
        .put("username", username)
        .put("pw_hash", pwHash)
        .put("device_name", deviceName)
        .put("device_kind", "android_gateway")
        .put("pub_key", pubKey)
        .put("sig_pub", sigPub)
        .let { post("/api/device-register", it) }

    fun deviceLogin(username: String, pwHash: String, sid: String): JSONObject =
        post("/api/device-login", JSONObject()
            .put("username", username)
            .put("pw_hash", pwHash)
            .put("sid", sid))

    fun logout(): JSONObject = post("/api/logout", JSONObject())

    fun createConversation(members: JSONArray, name: String): JSONObject =
        post("/api/conversation", JSONObject()
            .put("members", members)
            .put("name", name))

    fun listConversations(): JSONObject = get("/api/conversations")

    fun syncContactNames(entries: JSONArray): JSONObject =
        post("/api/contact-names/sync", JSONObject().put("entries", entries))

    fun convMembers(cid: String): JSONObject = get("/api/conversation/$cid/members")

    fun fetchMessages(cid: String, since: Int): JSONObject =
        get("/api/conversation/$cid/messages?since=$since&limit=500")

    fun listBlockRules(): JSONObject = get("/api/blocklist")

    fun addBlockRule(type: String, value: String): JSONObject =
        post("/api/blocklist", JSONObject().put("type", type).put("value", value))

    fun removeBlockRule(id: Long): JSONObject =
        post("/api/blocklist/remove", JSONObject().put("id", id))

    companion object {
        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
