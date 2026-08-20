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

    fun registerEmailRequest(username: String, email: String, pwHash: String): JSONObject =
        post("/api/register/email/request", JSONObject()
            .put("username", username).put("email", email).put("pw_hash", pwHash))

    fun registerEmailVerify(challengeId: String, code: String): JSONObject =
        post("/api/register/email/verify", JSONObject()
            .put("challenge_id", challengeId).put("code", code))

    fun requestPasswordReset(username: String, email: String): JSONObject =
        post("/api/password-reset/request", JSONObject()
            .put("username", username).put("email", email))

    fun confirmPasswordReset(
        username: String, email: String, challengeId: String, code: String, pwHash: String,
    ): JSONObject = post("/api/password-reset/confirm", JSONObject()
        .put("username", username)
        .put("email", email)
        .put("challenge_id", challengeId)
        .put("code", code)
        .put("pw_hash", pwHash))

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

    fun deviceLoginChallenge(username: String, pwHash: String, sid: String): JSONObject =
        post("/api/device-login", JSONObject()
            .put("username", username)
            .put("pw_hash", pwHash)
            .put("sid", sid))

    fun deviceLoginProof(
        username: String, pwHash: String, sid: String,
        challengeId: String, challenge: String, proof: String,
    ): JSONObject = post("/api/device-login", JSONObject()
        .put("username", username).put("pw_hash", pwHash).put("sid", sid)
        .put("challenge_id", challengeId).put("challenge", challenge).put("proof", proof))

    fun logout(): JSONObject = post("/api/logout", JSONObject())

    fun listDevices(): JSONObject = get("/api/devices")

    fun keyDirectory(): JSONObject = get("/api/key-directory")

    fun pendingDeviceStatus(): JSONObject = get("/api/device-pending-status")

    fun revokeOwnPendingDevice(): JSONObject = post("/api/device-pending-revoke", JSONObject())

    fun upgradeLegacySecurity(parentEpoch: Long, signature: String): JSONObject =
        post("/api/security-upgrade", JSONObject()
            .put("parent_epoch", parentEpoch).put("signature", signature))

    /**
     * Approve a pending device. Passing [pairing] signs and commits the v2
     * (QR) form, binding the approval to one scanned session; omitting it
     * keeps the v1 fingerprint-compare form.
     */
    fun approveDevice(
        subjectSid: String,
        parentEpoch: Long,
        signature: String,
        pairing: PairingBinding? = null,
    ): JSONObject = post("/api/device-approve", JSONObject()
        .put("subject_sid", subjectSid)
        .put("parent_epoch", parentEpoch)
        .put("signature", signature)
        .also { body ->
            if (pairing != null) {
                body.put("pairing_id", pairing.pairingId)
                body.put("nonce_new", pairing.nonceNew)
                body.put("nonce_approver", pairing.nonceApprover)
            }
        })

    /** Approver side of QR pairing: bind a scanned nonce to one pending device. */
    fun createPairingSession(sid: String, challenge: String, nonceNew: String): JSONObject =
        post("/api/pairing/session", JSONObject()
            .put("sid", sid)
            .put("challenge", challenge)
            .put("nonce_new", nonceNew))

    fun revokeDevice(sid: String, parentEpoch: Long, signature: String): JSONObject =
        post("/api/device-revoke", JSONObject()
            .put("sid", sid)
            .put("parent_epoch", parentEpoch)
            .put("signature", signature)
            .put("reason", "user_revoked"))

    fun rejectPendingDevice(sid: String, challenge: String, parentEpoch: Long): JSONObject =
        post("/api/device-reject-pending", JSONObject()
            .put("sid", sid).put("challenge", challenge).put("parent_epoch", parentEpoch))

    fun createConversation(members: JSONArray, name: String): JSONObject =
        post("/api/conversation", JSONObject()
            .put("members", members)
            .put("name", name))

    fun listConversations(): JSONObject = get("/api/conversations")

    fun syncContactNames(entries: JSONArray): JSONObject =
        post("/api/contact-names/sync", JSONObject().put("entries", entries))

    fun convMembers(cid: String): JSONObject =
        get("/api/conversation/${encodeSegment(cid)}/members")

    fun fetchMessages(cid: String, since: Int): JSONObject =
        get("/api/conversation/${encodeSegment(cid)}/messages?since=$since&limit=500")

    fun listBlockRules(): JSONObject = get("/api/blocklist")

    fun addBlockRule(type: String, value: String): JSONObject =
        post("/api/blocklist", JSONObject().put("type", type).put("value", value))

    fun removeBlockRule(id: Long): JSONObject =
        post("/api/blocklist/remove", JSONObject().put("id", id))

    companion object {
        /** cids come from server responses; keep them from escaping the path/query. */
        private fun encodeSegment(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")

        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
