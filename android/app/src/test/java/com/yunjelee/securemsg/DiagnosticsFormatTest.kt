package com.yunjelee.securemsg

import java.io.IOException
import java.time.ZoneId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFormatTest {
    /** The server's acceptance test for auth['diag'], verbatim. */
    private val serverDiag = Regex("^[A-Za-z0-9=;._:-]{1,120}$")

    private val utc = ZoneId.of("UTC")

    @Test
    fun `relay diag has the documented fields and passes the server filter`() {
        val diag = DiagnosticsFormat.relayDiag(
            pid = 12345, uptimeSeconds = 3600, ordinal = 3,
            lastCloseReason = "transport close", lastCloseAgeSeconds = 42,
            versionCode = 42, visible = true,
        )
        assertEquals("p=12345;up=3600;n=3;x=transport_close:42;v=42;vis=1", diag)
        assertTrue(serverDiag.matches(diag))
    }

    @Test
    fun `relay diag before any close says none`() {
        val diag = DiagnosticsFormat.relayDiag(1, 0, 1, null, null, 42, false)
        assertEquals("p=1;up=0;n=1;x=none;v=42;vis=0", diag)
        assertTrue(serverDiag.matches(diag))
    }

    @Test
    fun `socket io reasons map to safe tokens`() {
        assertEquals("io_server_disconnect", DiagnosticsFormat.token("io server disconnect"))
        assertEquals("ping_timeout", DiagnosticsFormat.token("ping timeout"))
        assertEquals("transport_error", DiagnosticsFormat.token("transport error"))
        assertEquals("unknown", DiagnosticsFormat.token(""))
        assertEquals("unknown", DiagnosticsFormat.token(null))
        // '@', '=', ';' and non-ASCII would break the field structure or the filter.
        assertEquals("a_b_c_d___", DiagnosticsFormat.token("a@b=c;d 한글"))
    }

    @Test
    fun `hostile or huge inputs still yield a server-acceptable diag of at most 120 chars`() {
        val diag = DiagnosticsFormat.relayDiag(
            pid = Int.MAX_VALUE, uptimeSeconds = Long.MAX_VALUE, ordinal = Int.MAX_VALUE,
            lastCloseReason = "x".repeat(500) + " @@@ ;;; === \n\u0000 한글", lastCloseAgeSeconds = Long.MAX_VALUE,
            versionCode = Int.MAX_VALUE, visible = true,
        )
        assertTrue(diag.length <= DiagnosticsFormat.RELAY_DIAG_MAX_CHARS)
        assertTrue(diag, serverDiag.matches(diag))
        // The reason is capped, so the trailing fields still fit.
        assertTrue(diag, diag.endsWith(";vis=1"))
    }

    @Test
    fun `negative ages and uptimes are clamped`() {
        val diag = DiagnosticsFormat.relayDiag(7, -5, 2, "ping timeout", -3, 42, false)
        assertEquals("p=7;up=0;n=2;x=ping_timeout:0;v=42;vis=0", diag)
    }

    @Test
    fun `only power-of-two reconnect attempts are logged`() {
        val logged = (0..20).filter(DiagnosticsFormat::isLoggedAttempt)
        assertEquals(listOf(1, 2, 4, 8, 16), logged)
    }

    @Test
    fun `clean masks phone-number-like runs and flattens lines`() {
        assertEquals("to # ok", DiagnosticsFormat.clean("to 010-1234-5678 ok"))
        assertEquals("to # ok", DiagnosticsFormat.clean("to +821012345678 ok"))
        assertEquals("to # ok", DiagnosticsFormat.clean("to (02) 555 1234 ok"))
        assertEquals("a b c", DiagnosticsFormat.clean("a\nb\r\nc".replace("\r\n", "\n")))
        // Short numbers (pids, counts, versions) survive.
        assertEquals("pid=12345 flags=1 v=42", DiagnosticsFormat.clean("pid=12345 flags=1 v=42"))
        assertEquals(10, DiagnosticsFormat.clean("y".repeat(50), maxChars = 10).length)
    }

    @Test
    fun `throwable summary carries class names and a frame but never the message`() {
        val cause = IOException("sms to 010-1234-5678 body=secret token=abc")
        val error = IllegalStateException("wrapped 01012345678", cause)
        val summary = DiagnosticsFormat.throwableSummary(error)
        assertTrue(summary, summary.startsWith("chain=java.lang.IllegalStateException>java.io.IOException"))
        assertTrue(summary, summary.contains(" at="))
        assertFalse(summary, summary.contains("secret"))
        assertFalse(summary, summary.contains("token"))
        assertFalse(summary, summary.contains("1234"))
        assertFalse(summary, summary.contains("wrapped"))
    }

    @Test
    fun `throwable summary survives a cause cycle`() {
        val a = RuntimeException()
        val b = IllegalArgumentException(a)
        a.initCause(b)
        val summary = DiagnosticsFormat.throwableSummary(a)
        assertTrue(summary, summary.startsWith("chain=java.lang.RuntimeException>java.lang.IllegalArgumentException"))
        assertFalse(summary, summary.contains(">java.lang.RuntimeException"))
    }

    @Test
    fun `log line is a single line with time, pid and a token event`() {
        val line = DiagnosticsFormat.line(0L, utc, 99, "svc start", "a\nb")
        assertEquals("1970-01-01 00:00:00.000 p=99 svc_start a b", line)
        assertTrue(DiagnosticsFormat.line(0L, utc, 1, "e", "z".repeat(5000)).length <= DiagnosticsFormat.LINE_MAX_CHARS)
    }

    @Test
    fun `action label strips the package and names a sticky restart`() {
        assertEquals("START_BRIDGE", DiagnosticsFormat.actionLabel(SmsBridgeService.ACTION_START_BRIDGE))
        assertEquals("none", DiagnosticsFormat.actionLabel(null))
    }

    // ---- exit reasons ----------------------------------------------------

    private fun exit(ts: Long, reason: Int, description: String? = null, state: String? = null) =
        DiagnosticsFormat.ExitRecord(
            timestamp = ts, pid = 4000 + reason, reason = reason, importance = 125,
            description = description, stateSummary = state,
        )

    @Test
    fun `only exits newer than last seen are recorded, oldest first`() {
        // The platform lists newest first.
        val records = listOf(exit(500, 16), exit(400, 4), exit(300, 3), exit(200, 10))
        val fresh = DiagnosticsFormat.newExitRecords(records, lastSeen = 300)
        assertEquals(listOf(400L, 500L), fresh.map { it.timestamp })
        assertEquals(500L, DiagnosticsFormat.nextExitLastSeen(fresh, 300))
        // Nothing new: last seen does not move (and never goes back).
        assertEquals(emptyList<DiagnosticsFormat.ExitRecord>(), DiagnosticsFormat.newExitRecords(records, 500))
        assertEquals(500L, DiagnosticsFormat.nextExitLastSeen(emptyList(), 500))
    }

    @Test
    fun `a first run with no last seen takes every record`() {
        val records = listOf(exit(2, 6), exit(1, 1))
        assertEquals(2, DiagnosticsFormat.newExitRecords(records, 0).size)
    }

    @Test
    fun `package update exit is marked as self-update, others are not`() {
        val update = DiagnosticsFormat.exitDetail(exit(1, 16, state = "vis=0;fgs=1;relay=connected"))
        assertTrue(update, update.contains("reason=16:package_updated self-update"))
        assertTrue(update, update.contains("state=vis=0;fgs=1;relay=connected"))
        val crash = DiagnosticsFormat.exitDetail(exit(1, 4))
        assertTrue(crash, crash.contains("reason=4:crash"))
        assertFalse(crash, crash.contains("self-update"))
    }

    @Test
    fun `exit description is cleaned and capped at 120 chars`() {
        val detail = DiagnosticsFormat.exitDetail(
            exit(1, 6, description = "Input dispatching timed out 010-9876-5432 " + "q".repeat(300) + "\n\"x"),
        )
        val desc = detail.substringAfter("desc=\"").substringBeforeLast('"')
        assertTrue(desc.length <= DiagnosticsFormat.DESCRIPTION_MAX_CHARS)
        assertFalse(detail, detail.contains("9876"))
        assertFalse(detail, detail.contains("\n"))
    }

    // ---- state summary ---------------------------------------------------

    @Test
    fun `state summary encodes the three fields in at most 128 bytes`() {
        val summary = DiagnosticsFormat.stateSummary(visible = true, foregroundService = false, relay = "connected")
        assertArrayEquals("vis=1;fgs=0;relay=connected".toByteArray(), summary)
        val huge = DiagnosticsFormat.stateSummary(true, true, "r".repeat(1000) + "한글")
        assertTrue(huge.size <= DiagnosticsFormat.STATE_SUMMARY_MAX_BYTES)
        assertTrue(huge.all { it >= 0 })
    }

    // ---- ring trim -------------------------------------------------------

    private fun lines(prefix: String, count: Int, width: Int = 90): ByteArray = buildString {
        repeat(count) { i -> append("$prefix$i ").append("x".repeat(width)).append('\n') }
    }.toByteArray()

    @Test
    fun `under the cap trim is a plain append`() {
        val existing = lines("a", 3)
        val appended = "new\n".toByteArray()
        assertArrayEquals(existing + appended, DiagnosticsFormat.trimLog(existing, appended, 1024 * 1024, 1024))
    }

    @Test
    fun `over the cap trim keeps the newest whole lines within the target`() {
        val max = DiagnosticsFormat.LOG_MAX_BYTES
        val target = DiagnosticsFormat.LOG_TRIM_TARGET_BYTES
        var log = ByteArray(0)
        // Simulate thousands of appends through the same code path the writer uses.
        repeat(5000) { i ->
            val line = "2026-09-28 07:00:00.000 p=1 event n=$i ${"y".repeat(i % 170)}\n".toByteArray()
            log = if (log.size + line.size <= max) log + line else DiagnosticsFormat.trimLog(log, line, max, target)
            assertTrue("size ${log.size} after $i", log.size <= max)
        }
        val text = String(log)
        assertTrue(text.startsWith("2026-09-28 "))
        assertTrue(text.endsWith("\n"))
        // Every kept line is whole, and the newest one is last.
        text.trimEnd('\n').split('\n').forEach { assertTrue(it, it.startsWith("2026-09-28 07:00:00.000 p=1 event n=")) }
        assertTrue(text.trimEnd('\n').substringAfterLast('\n').startsWith("2026-09-28 07:00:00.000 p=1 event n=4999 "))
    }

    @Test
    fun `trim result starts exactly on a line boundary`() {
        val existing = lines("old", 200) // ~19 KB
        val appended = "last\n".toByteArray()
        val out = DiagnosticsFormat.trimLog(existing, appended, maxBytes = 4096, targetBytes = 3000)
        assertTrue(out.size <= 3000)
        val text = String(out)
        assertTrue(text, text.startsWith("old"))
        assertTrue(text.endsWith("last\n"))
        // The first kept line is intact (full width).
        assertEquals(text.substringBefore('\n').substringAfter(' '), "x".repeat(90))
    }

    @Test
    fun `a boundary that already falls on a newline keeps that line`() {
        val existing = "aaaa\nbbbb\n".toByteArray()
        val appended = "cccc\n".toByteArray()
        // Tail of 10 bytes starts right after "aaaa\n".
        val out = DiagnosticsFormat.trimLog(existing, appended, maxBytes = 12, targetBytes = 10)
        assertEquals("bbbb\ncccc\n", String(out))
    }
}
