package com.yunjelee.securemsg

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The conversation walk behind history sharing. missing-keys is capped by the
 * server, so these fix the shape of the two ways that cap used to end a
 * conversation early: a whole page this device cannot open, and a relay error
 * reported to the user as "excluded". The relay's per-minute sync budget is a
 * third: a 429 says nothing about this conversation, so it is waited out.
 */
class HistoryShareTest {

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
    fun `a fully unreadable missing-keys page does not end the conversation`() {
        // Every sequence the server names is sealed for devices this one is not,
        // while the readable message sits past the end of that capped page.
        server.enqueue(jsonResponse(200, missingKeys(1..500)))
        server.enqueue(jsonResponse(200, messages(1..500)))
        server.enqueue(jsonResponse(200, messages(501..501)))
        server.enqueue(jsonResponse(200, """{"ok":true,"added":1,"skipped":0}"""))

        val summary = runBlocking {
            HistoryShare.shareConversation(api(), "cid-1", "sid-target") { row ->
                CryptoUtil.EnvelopeKey("ek", "n", by = "sid-me")
                    .takeIf { row.optInt("seq") > 500 }
            }
        }

        assertNull(summary.error)
        assertEquals(1, summary.shared)
        assertEquals(500, summary.skipped)
        val shared = JSONObject(shareKeysRequestBody())
        assertEquals(1, shared.getJSONArray("entries").length())
        assertEquals(501, shared.getJSONArray("entries").getJSONObject(0).getInt("seq"))
    }

    @Test
    fun `a relay failure is reported instead of counted as unreadable`() {
        server.enqueue(jsonResponse(200, missingKeys(1..3)))
        server.enqueue(jsonResponse(500, """{"ok":false,"error":"boom"}"""))

        val summary = runBlocking {
            HistoryShare.shareConversation(api(), "cid-1", "sid-target") {
                CryptoUtil.EnvelopeKey("ek", "n", by = "sid-me")
            }
        }

        assertNotNull(summary.error)
        assertEquals(0, summary.shared)
        assertEquals(0, summary.skipped)
    }

    @Test
    fun `a conversation the target can already read costs one request`() {
        server.enqueue(jsonResponse(200, """{"ok":true,"seqs":[]}"""))

        val summary = runBlocking {
            HistoryShare.shareConversation(api(), "cid-1", "sid-target") {
                throw AssertionError("nothing may be re-wrapped")
            }
        }

        assertNull(summary.error)
        assertEquals(0, summary.shared)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `keys the target already holds are not re-uploaded`() {
        server.enqueue(jsonResponse(200, missingKeys(2..2)))
        // The pull starts at the first gap and still covers rows the target has.
        server.enqueue(jsonResponse(200, messages(2..3, alsoSealedFor = listOf(3))))
        server.enqueue(jsonResponse(200, """{"ok":true,"added":1,"skipped":0}"""))

        val summary = runBlocking {
            HistoryShare.shareConversation(api(), "cid-1", "sid-target") {
                CryptoUtil.EnvelopeKey("ek", "n", by = "sid-me")
            }
        }

        assertNull(summary.error)
        assertEquals(1, summary.shared)
        val entries = JSONObject(shareKeysRequestBody()).getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals(2, entries.getJSONObject(0).getInt("seq"))
    }

    @Test
    fun `a rate-limited probe is waited out instead of failing the conversation`() {
        server.enqueue(rateLimited())
        server.enqueue(jsonResponse(200, missingKeys(1..1)))
        server.enqueue(jsonResponse(200, messages(1..1)))
        server.enqueue(jsonResponse(200, """{"ok":true,"added":1,"skipped":0}"""))

        val summary = runBlocking {
            HistoryShare.shareConversation(api(), "cid-1", "sid-target", backoffMs = 1L) {
                CryptoUtil.EnvelopeKey("ek", "n", by = "sid-me")
            }
        }

        assertNull(summary.error)
        assertEquals(1, summary.shared)
    }

    @Test
    fun `a rate-limited key upload is retried with the same entries`() {
        server.enqueue(jsonResponse(200, missingKeys(1..1)))
        server.enqueue(jsonResponse(200, messages(1..1)))
        server.enqueue(rateLimited())
        server.enqueue(jsonResponse(200, """{"ok":true,"added":1,"skipped":0}"""))

        val summary = runBlocking {
            HistoryShare.shareConversation(api(), "cid-1", "sid-target", backoffMs = 1L) {
                CryptoUtil.EnvelopeKey("ek", "n", by = "sid-me")
            }
        }

        assertNull(summary.error)
        assertEquals(1, summary.shared)
        val denied = JSONObject(shareKeysRequestBody()).getJSONArray("entries")
        val accepted = JSONObject(shareKeysRequestBody()).getJSONArray("entries")
        assertEquals(1, accepted.length())
        assertEquals(1, accepted.getJSONObject(0).getInt("seq"))
        assertEquals(denied.toString(), accepted.toString())
    }

    @Test
    fun `a relay that never lets up ends the conversation instead of looping`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = rateLimited()
        }

        val summary = runBlocking {
            HistoryShare.shareConversation(api(), "cid-1", "sid-target", backoffMs = 1L) {
                throw AssertionError("nothing may be re-wrapped")
            }
        }

        assertNotNull(summary.error)
        assertEquals(0, summary.shared)
    }

    private fun api() = RelayApi(server.url("/").toString(), OkHttpClient())

    /** Body of the next share-keys POST, skipping the GETs before it. */
    private fun shareKeysRequestBody(): String {
        while (true) {
            val request = server.takeRequest()
            if (request.method == "POST") return request.body.readUtf8()
        }
    }

    private fun missingKeys(seqs: IntRange) =
        """{"ok":true,"seqs":[${seqs.joinToString(",")}]}"""

    /** Relay rows whose envelope names `sid-me`, plus the target where asked. */
    private fun messages(seqs: IntRange, alsoSealedFor: List<Int> = emptyList()): String {
        val rows = seqs.joinToString(",") { seq ->
            val keys = if (seq in alsoSealedFor) {
                """{"sid-me":{"ek":"a","n":"b"},"sid-target":{"ek":"c","n":"d"}}"""
            } else {
                """{"sid-me":{"ek":"a","n":"b"}}"""
            }
            """{"seq":$seq,"sender_sid":"sid-web","payload":{"ct":"x","nonce":"y","keys":$keys}}"""
        }
        return """{"ok":true,"messages":[$rows]}"""
    }

    /** The relay's answer once the per-minute sync budget is spent. */
    private fun rateLimited() = jsonResponse(429, """{"ok":false,"error":"too many requests"}""")
        .setHeader("Retry-After", "3")

    private fun jsonResponse(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
