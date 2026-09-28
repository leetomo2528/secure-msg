package com.yunjelee.securemsg

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * startBridge's REST preflight against a relay that is down or restarting.
 * The watchdog calls startBridge after every 90s of disconnection; a transient
 * failure there must keep the existing client (and the service) instead of
 * stopping the bridge, while a real refusal still stops it.
 */
class BridgePreflightFailureTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RelayApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        api = RelayApi(server.url("/").toString(), http).also { it.token = "t" }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun html(status: Int) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "text/html").setBody("<html>Bad Gateway</html>")

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `auth check answered 502 or 503 by the proxy keeps the existing client`() {
        for (status in listOf(502, 503)) {
            server.enqueue(html(status))
            val response = api.listConversations()
            assertEquals(status, response.optInt("_http_status"))
            assertTrue("$status", BridgeLifecyclePolicy.isTransientAuthCheck(response))
            assertTrue(BridgeLifecyclePolicy.keepClientAfterFailedPreflight(hasClient = true, transient = true))
        }
    }

    @Test
    fun `auth check that answered ok, 401 or 403 is not transient`() {
        server.enqueue(json(200, "{\"ok\":true,\"conversations\":[]}"))
        assertFalse(BridgeLifecyclePolicy.isTransientAuthCheck(api.listConversations()))
        server.enqueue(json(401, "{\"ok\":false,\"error\":\"invalid token\"}"))
        assertFalse(BridgeLifecyclePolicy.isTransientAuthCheck(api.listConversations()))
        server.enqueue(json(403, "{\"ok\":false,\"error\":\"forbidden\"}"))
        assertFalse(BridgeLifecyclePolicy.isTransientAuthCheck(api.listConversations()))
    }

    @Test
    fun `auth check that threw (unreachable) is transient`() {
        assertTrue(BridgeLifecyclePolicy.isTransientAuthCheck(null))
    }

    @Test
    fun `key directory 503 after a successful auth check is transient`() {
        server.enqueue(json(200, "{\"ok\":true,\"conversations\":[]}"))
        server.enqueue(html(503))
        assertFalse(BridgeLifecyclePolicy.isTransientAuthCheck(api.listConversations()))
        val directory = RelayTrustedDeviceApi(api).loadDirectoryResponse()
        assertFalse(directory.optBoolean("ok"))
        assertTrue(BridgeLifecyclePolicy.isTransientHttpStatus(directory.optInt("_http_status")))
    }

    @Test
    fun `network dropping between the auth check and the key directory is transient`() {
        server.enqueue(json(200, "{\"ok\":true,\"conversations\":[]}"))
        assertFalse(BridgeLifecyclePolicy.isTransientAuthCheck(api.listConversations()))
        // The relay goes away between the two calls: the directory fetch
        // meets a refused connection.
        server.shutdown()
        val thrown = try {
            RelayTrustedDeviceApi(api).loadDirectoryResponse()
            null
        } catch (e: Exception) {
            e
        }
        assertTrue("$thrown", thrown != null && BridgeLifecyclePolicy.isTransientFailure(thrown))
    }

    @Test
    fun `key directory refusals are not transient`() {
        for (status in listOf(400, 401, 403, 404)) {
            assertFalse("$status", BridgeLifecyclePolicy.isTransientHttpStatus(status))
        }
        assertFalse(BridgeLifecyclePolicy.isTransientHttpStatus(0))
        assertFalse(BridgeLifecyclePolicy.isTransientHttpStatus(200))
        for (status in listOf(408, 425, 429, 500, 502, 503, 504, 599)) {
            assertTrue("$status", BridgeLifecyclePolicy.isTransientHttpStatus(status))
        }
    }

    @Test
    fun `only IO failures are transient, parse and verification errors are not`() {
        assertTrue(BridgeLifecyclePolicy.isTransientFailure(IOException()))
        assertTrue(BridgeLifecyclePolicy.isTransientFailure(SocketTimeoutException()))
        assertFalse(BridgeLifecyclePolicy.isTransientFailure(JSONException("x")))
        assertFalse(BridgeLifecyclePolicy.isTransientFailure(IllegalStateException()))
    }

    @Test
    fun `a transient failure keeps the service only when a client exists`() {
        assertTrue(BridgeLifecyclePolicy.keepClientAfterFailedPreflight(hasClient = true, transient = true))
        assertFalse(BridgeLifecyclePolicy.keepClientAfterFailedPreflight(hasClient = false, transient = true))
        assertFalse(BridgeLifecyclePolicy.keepClientAfterFailedPreflight(hasClient = true, transient = false))
    }

    @Test
    fun `a view with only a transient error is the one the trust gate may skip`() {
        val transientView = DeviceSecurityView(error = "HTTP 503", transient = true)
        assertTrue(BridgeLifecyclePolicy.keepClientAfterFailedPreflight(true, transientView.transient))
        // Default for every other blocking view (warnings, pending, refusals).
        assertFalse(DeviceSecurityView(trustWarning = "x").transient)
        assertFalse(DeviceSecurityView(error = "HTTP 403").transient)
    }
}
