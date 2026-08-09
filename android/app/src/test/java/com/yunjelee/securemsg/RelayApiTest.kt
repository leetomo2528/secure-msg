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
