package com.yunjelee.securemsg

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class RelayApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun logoutPostsWithBearerToken() {
        server.enqueue(jsonResponse(200, "{\"ok\":true}"))
        val api = RelayApi(server.url("/").toString(), OkHttpClient()).also {
            it.token = "android-token"
        }

        val result = api.logout()
        val request = server.takeRequest()

        assertTrue(result.getBoolean("ok"))
        assertEquals("POST", request.method)
        assertEquals("/api/logout", request.path)
        assertEquals("Bearer android-token", request.getHeader("Authorization"))
        assertEquals("{}", request.body.readUtf8())
    }

    @Test
    fun logoutReturnsStructured401ForAlreadyInvalidToken() {
        server.enqueue(jsonResponse(401, "{\"ok\":false,\"error\":\"invalid token\"}"))
        val api = RelayApi(server.url("/").toString(), OkHttpClient()).also {
            it.token = "expired-token"
        }

        val result = api.logout()

        assertFalse(result.getBoolean("ok"))
        assertEquals(401, result.getInt("_http_status"))
        assertEquals("invalid token", result.getString("error"))
    }

    @Test
    fun deviceLoginProofPostsAllChallengeBoundFields() {
        server.enqueue(jsonResponse(200, "{\"ok\":true,\"token\":\"jwt\"}"))
        val api = RelayApi(server.url("/").toString(), OkHttpClient())

        val result = api.deviceLoginProof("alice", "hash", "device01", "challenge-id", "nonce", "signature")
        val body = JSONObject(server.takeRequest().body.readUtf8())

        assertTrue(result.getBoolean("ok"))
        assertEquals("challenge-id", body.getString("challenge_id"))
        assertEquals("nonce", body.getString("challenge"))
        assertEquals("signature", body.getString("proof"))
    }

    @Test
    fun passwordResetRequestAndConfirmUsePublicEndpoints() {
        server.enqueue(jsonResponse(200, "{\"ok\":true,\"challenge_id\":\"c1\"}"))
        server.enqueue(jsonResponse(200, "{\"ok\":true}"))
        val api = RelayApi(server.url("/").toString(), OkHttpClient())

        assertTrue(api.requestPasswordReset("alice", "alice@example.com").getBoolean("ok"))
        val request = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("alice", request.getString("username"))
        assertEquals("alice@example.com", request.getString("email"))

        assertTrue(api.confirmPasswordReset("alice", "alice@example.com", "c1", "123456", "hash").getBoolean("ok"))
        val confirm = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("c1", confirm.getString("challenge_id"))
        assertEquals("123456", confirm.getString("code"))
        assertEquals("hash", confirm.getString("pw_hash"))
    }

    @Test
    fun emailRegistrationUsesRequestAndVerifyEndpoints() {
        server.enqueue(jsonResponse(200, "{\"ok\":true,\"challenge_id\":\"c1\"}"))
        server.enqueue(jsonResponse(200, "{\"ok\":true,\"username\":\"alice\"}"))
        val api = RelayApi(server.url("/").toString(), OkHttpClient())

        assertTrue(api.registerEmailRequest("alice", "alice@example.com", "hash").getBoolean("ok"))
        val requestCall = server.takeRequest()
        val request = JSONObject(requestCall.body.readUtf8())
        assertEquals("/api/register/email/request", requestCall.path)
        assertEquals("alice@example.com", request.getString("email"))

        assertTrue(api.registerEmailVerify("c1", "123456").getBoolean("ok"))
        val verifyCall = server.takeRequest()
        val verify = JSONObject(verifyCall.body.readUtf8())
        assertEquals("/api/register/email/verify", verifyCall.path)
        assertEquals("c1", verify.getString("challenge_id"))
        assertEquals("123456", verify.getString("code"))
    }

    @Test
    fun canonicalDeviceLoginStatementBindsSessionAndChallenge() {
        val canonical = DeviceLoginStatement(7, "device01", "challenge-id", "A".repeat(43), 4).canonical()
        assertEquals(
            "securemsg-device-login-v1\nuid=7\nsid=device01\nchallenge_id=challenge-id\n" +
                "challenge=${"A".repeat(43)}\nsession_version=4\n",
            canonical,
        )
    }

    @Test
    fun contactNameSnapshotPostsNullClearsInOneAuthenticatedRequest() {
        server.enqueue(jsonResponse(200, "{\"ok\":true,\"synced\":2}"))
        val api = RelayApi(server.url("/").toString(), OkHttpClient()).also {
            it.token = "android-token"
        }
        val entries = JSONArray()
            .put(JSONObject().put("cid", "cid-1").put("contact_name", "윤제"))
            .put(JSONObject().put("cid", "cid-2").put("contact_name", JSONObject.NULL))

        val result = api.syncContactNames(entries)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertTrue(result.getBoolean("ok"))
        assertEquals("POST", request.method)
        assertEquals("/api/contact-names/sync", request.path)
        assertEquals("Bearer android-token", request.getHeader("Authorization"))
        assertEquals("윤제", body.getJSONArray("entries").getJSONObject(0).getString("contact_name"))
        assertTrue(body.getJSONArray("entries").getJSONObject(1).isNull("contact_name"))
    }

    private fun jsonResponse(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
