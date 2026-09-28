package com.yunjelee.securemsg

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local, user-shareable record of why the bridge's process and relay socket
 * came and went.
 *
 * The relay's logs showed the phone's socket going silent (no FIN) every time
 * the app was opened, but nothing on the phone said whether that was a new
 * process, a replaced client or a server-side drop. This keeps a small ring
 * of lifecycle events in `filesDir/diag/events.log` — outside `update/`, which
 * the self-updater owns and prunes — plus the platform's own process-exit
 * records, and hands them to the user only when they press 설정 › 진단 정보 공유.
 *
 * Privacy: nothing here writes a message body, a phone number, a token or an
 * exception message (a message can quote a number or a URL with a token).
 * Throwables are reduced to their class chain and top frame; free text from
 * callers and from the platform goes through [DiagnosticsFormat.clean].
 *
 * Every entry point is failure-tolerant: diagnostics must never be the reason
 * the default SMS app falls over.
 */
object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val PREFS = "diagnostics"
    private const val KEY_EXIT_LAST_SEEN = "exit_last_seen_ms"
    private const val EXIT_REASONS_MAX = 16

    /** The Application itself (not an Activity), so holding it statically leaks nothing. */
    @Volatile private var app: Application? = null
    private val initialized = AtomicBoolean(false)
    private val fileLock = Any()
    private val stateLock = Any()

    /**
     * Ordinary records are written off the caller's thread (onStartCommand and
     * the socket's event thread must not block on disk). Only the crash
     * handler writes synchronously, because the process is about to die.
     */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diag-log").apply { isDaemon = true }
    }

    /** Whether an activity of this app is resumed; read by [RelayClient]'s diag string. */
    @Volatile var visible: Boolean = false
        private set
    @Volatile private var foregroundService: Boolean = false
    @Volatile private var relayState: String = "none"

    fun init(application: Application) {
        if (!initialized.compareAndSet(false, true)) return
        app = application
        try {
            installCrashHandler()
        } catch (t: Throwable) {
            Log.w(TAG, "crash handler not installed", t)
        }
        // Exit records describe the previous process, so they go in first; the
        // single-thread writer keeps this order ahead of process_start.
        submit { recordExitReasons(application) }
        record("process_start", "v=${BuildConfig.VERSION_CODE}")
        updateStateSummary()
    }

    /** Appends one event. [detail] is free text and is cleaned before it is stored. */
    fun record(event: String, detail: String = "") {
        val context = app ?: return
        val line = try {
            DiagnosticsFormat.line(
                System.currentTimeMillis(), ZoneId.systemDefault(), Process.myPid(),
                event, DiagnosticsFormat.clean(detail),
            )
        } catch (t: Throwable) {
            return
        }
        Log.i(TAG, line)
        submit { appendNow(context, line) }
    }

    /** Appends one event for [error]: its class chain and top frame, never its message. */
    fun record(event: String, error: Throwable) {
        record(event, safeSummary(error))
    }

    /**
     * Publishes the process state the platform attaches to this process's
     * eventual [ApplicationExitInfo], so the next process can read what the
     * dead one was doing (visible? foreground service? relay up?). Null keeps
     * a field as it is.
     */
    fun updateStateSummary(vis: Boolean? = null, fgs: Boolean? = null, relay: String? = null) {
        val context = app ?: return
        val summary = synchronized(stateLock) {
            vis?.let { visible = it }
            fgs?.let { foregroundService = it }
            relay?.let { relayState = it }
            DiagnosticsFormat.stateSummary(visible, foregroundService, relayState)
        }
        try {
            context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(summary)
        } catch (t: Throwable) {
            Log.w(TAG, "process state summary not set", t)
        }
    }

    /**
     * The text 설정 › 진단 정보 공유 hands to the share sheet. Blocking I/O:
     * call it off the main thread.
     */
    fun buildShareText(context: Context): String {
        val zone = ZoneId.systemDefault()
        val out = StringBuilder()
        out.append("SecureMsg 진단 정보\n")
        out.append("메시지 내용·전화번호·인증 토큰은 포함되지 않습니다.\n")
        out.append("생성: ").append(DiagnosticsFormat.time(System.currentTimeMillis(), zone)).append('\n')
        out.append("앱: v").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(") · ")
            .append(DiagnosticsFormat.clean("${Build.MANUFACTURER} ${Build.MODEL}")).append('\n')
        val uptime = try {
            (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        } catch (_: Throwable) {
            -1L
        }
        val state = synchronized(stateLock) {
            String(DiagnosticsFormat.stateSummary(visible, foregroundService, relayState), Charsets.US_ASCII)
        }
        out.append("프로세스: p=").append(Process.myPid()).append(" up=").append(uptime).append("s ")
            .append(state).append('\n')

        out.append("\n== 프로세스 종료 기록 (시스템) ==\n")
        try {
            val infos = context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_REASONS_MAX)
                .orEmpty()
            if (infos.isEmpty()) out.append("(없음)\n")
            infos.map(::toRecord).sortedBy { it.timestamp }.forEach { record ->
                out.append(DiagnosticsFormat.time(record.timestamp, zone)).append(' ')
                    .append(DiagnosticsFormat.exitDetail(record)).append('\n')
            }
        } catch (t: Throwable) {
            out.append("(조회 실패: ").append(t.javaClass.name).append(")\n")
        }

        out.append("\n== events.log ==\n")
        val log = try {
            synchronized(fileLock) {
                logFile(context).takeIf { it.exists() }?.readText(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            "(읽기 실패: ${t.javaClass.name})\n"
        }
        out.append(if (log.isNullOrEmpty()) "(비어 있음)\n" else log)
        return out.toString()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val context = app
                if (context != null) {
                    val line = DiagnosticsFormat.line(
                        System.currentTimeMillis(), ZoneId.systemDefault(), Process.myPid(),
                        "crash",
                        "thread=${DiagnosticsFormat.token(thread.name, 48)} ${safeSummary(error)}",
                    )
                    appendNow(context, line)
                }
            } catch (_: Throwable) {
                // Recording is best effort; the original handler must still run.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun recordExitReasons(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(KEY_EXIT_LAST_SEEN, 0L)
            val infos = context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_REASONS_MAX)
                ?: return
            val fresh = DiagnosticsFormat.newExitRecords(infos.map(::toRecord), lastSeen)
            if (fresh.isEmpty()) return
            val zone = ZoneId.systemDefault()
            val pid = Process.myPid()
            fresh.forEach { record ->
                // Stamped with the exit's own time, not now: the log reads in
                // the order things happened.
                appendNow(
                    context,
                    DiagnosticsFormat.line(record.timestamp, zone, pid, "exit", DiagnosticsFormat.exitDetail(record)),
                )
            }
            prefs.edit()
                .putLong(KEY_EXIT_LAST_SEEN, DiagnosticsFormat.nextExitLastSeen(fresh, lastSeen))
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "exit reasons not recorded", t)
        }
    }

    private fun toRecord(info: ApplicationExitInfo): DiagnosticsFormat.ExitRecord =
        DiagnosticsFormat.ExitRecord(
            timestamp = info.timestamp,
            pid = info.pid,
            reason = info.reason,
            importance = info.importance,
            description = info.description,
            stateSummary = info.processStateSummary?.let { String(it, Charsets.UTF_8) },
        )

    private fun safeSummary(error: Throwable): String = try {
        DiagnosticsFormat.throwableSummary(error)
    } catch (_: Throwable) {
        "chain=unavailable"
    }

    private fun submit(task: () -> Unit) {
        try {
            writer.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    Log.w(TAG, "diagnostics task failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "diagnostics task rejected", t)
        }
    }

    private fun logFile(context: Context): File = File(File(context.filesDir, "diag"), "events.log")

    private fun appendNow(context: Context, line: String) {
        synchronized(fileLock) {
            try {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                val existing = if (file.exists()) file.length() else 0L
                if (existing + bytes.size <= DiagnosticsFormat.LOG_MAX_BYTES) {
                    FileOutputStream(file, true).use { it.write(bytes) }
                } else {
                    val old = try {
                        file.readBytes()
                    } catch (_: Throwable) {
                        ByteArray(0)
                    }
                    val kept = DiagnosticsFormat.trimLog(
                        old, bytes, DiagnosticsFormat.LOG_MAX_BYTES, DiagnosticsFormat.LOG_TRIM_TARGET_BYTES,
                    )
                    val tmp = File(file.parentFile, "events.log.tmp")
                    tmp.writeBytes(kept)
                    if (!tmp.renameTo(file)) {
                        file.writeBytes(kept)
                        tmp.delete()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "diagnostics write failed", t)
            }
        }
    }
}

/**
 * The pure half of [Diagnostics] and of [RelayClient]'s `diag` auth field:
 * formatting, sanitizing and the log ring's trim, with no Android calls so the
 * JVM tests can pin them.
 */
object DiagnosticsFormat {
    /** Hard cap of `events.log`. */
    const val LOG_MAX_BYTES = 64 * 1024

    /**
     * What a trim keeps. Below [LOG_MAX_BYTES] so a full log is rewritten once
     * per ~16 KiB of new lines, not on every append.
     */
    const val LOG_TRIM_TARGET_BYTES = 48 * 1024

    /** One log line, in chars; the worst-case UTF-8 line stays far below the trim target. */
    const val LINE_MAX_CHARS = 400

    /** `ActivityManager.setProcessStateSummary` rejects more than this. */
    const val STATE_SUMMARY_MAX_BYTES = 128

    const val DESCRIPTION_MAX_CHARS = 120

    /** The relay server logs `auth.diag` only when it fully matches `[A-Za-z0-9=;._:-]{1,120}`. */
    const val RELAY_DIAG_MAX_CHARS = 120

    /** `ApplicationExitInfo.REASON_PACKAGE_UPDATED`: the unattended self-update replaced the APK. */
    const val REASON_PACKAGE_UPDATED = 16

    private val RELAY_DIAG_DISALLOWED = Regex("[^A-Za-z0-9=;._:-]")
    private val TOKEN_DISALLOWED = Regex("[^A-Za-z0-9._-]")
    private val CONTROL = Regex("[\\p{Cntrl}\\u2028\\u2029]")

    /**
     * A run that could be a phone number: 7+ digits, optionally split by the
     * separators people and carriers write numbers with.
     */
    private val NUMBER_LIKE = Regex("[+(]?\\d[\\d\\- ().]{5,}\\d")

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    data class ExitRecord(
        val timestamp: Long,
        val pid: Int,
        val reason: Int,
        val importance: Int,
        val description: String?,
        val stateSummary: String?,
    )

    /**
     * An identifier-safe token: anything outside `[A-Za-z0-9._-]` (spaces
     * included, so 'transport close' becomes 'transport_close') turns into
     * '_'. Never empty.
     */
    fun token(raw: String?, maxChars: Int = 32): String {
        val cleaned = raw.orEmpty().trim().replace(TOKEN_DISALLOWED, "_").take(maxChars)
        return cleaned.ifEmpty { "unknown" }
    }

    /**
     * Free text made safe for the log: one line, number-like runs masked,
     * capped. Used for caller details and the platform's exit description.
     */
    fun clean(raw: String?, maxChars: Int = LINE_MAX_CHARS): String {
        val oneLine = raw.orEmpty().replace(CONTROL, " ")
        val masked = NUMBER_LIKE.replace(oneLine) { match ->
            if (match.value.count(Char::isDigit) >= 7) "#" else match.value
        }
        return masked.trim().take(maxChars)
    }

    fun time(epochMillis: Long, zone: ZoneId): String =
        TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** `<local time> p=<pid> <event> <detail>`; [detail] must already be clean. */
    fun line(epochMillis: Long, zone: ZoneId, pid: Int, event: String, detail: String): String {
        val head = "${time(epochMillis, zone)} p=$pid ${token(event, 40)}"
        val body = detail.replace(CONTROL, " ").trim()
        return (if (body.isEmpty()) head else "$head $body").take(LINE_MAX_CHARS)
    }

    /**
     * Class names of [error] and its causes (outermost first) plus the top
     * frame of [error]. Deliberately no message: exception messages can carry
     * phone numbers, bodies or URLs with tokens.
     */
    fun throwableSummary(error: Throwable, maxDepth: Int = 6): String {
        val names = mutableListOf<String>()
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && names.size < maxDepth && seen.add(current)) {
            names += current.javaClass.name
            current = current.cause
        }
        val frame = error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
        return buildString {
            append("chain=").append(names.joinToString(">"))
            if (frame != null) append(" at=").append(frame)
        }
    }

    fun exitReasonName(reason: Int): String = when (reason) {
        0 -> "unknown"
        1 -> "exit_self"
        2 -> "signaled"
        3 -> "low_memory"
        4 -> "crash"
        5 -> "crash_native"
        6 -> "anr"
        7 -> "initialization_failure"
        8 -> "permission_change"
        9 -> "excessive_resource_usage"
        10 -> "user_requested"
        11 -> "user_stopped"
        12 -> "dependency_died"
        13 -> "other"
        14 -> "freezer"
        15 -> "package_state_change"
        REASON_PACKAGE_UPDATED -> "package_updated"
        else -> "reason_$reason"
    }

    /** Records newer than [lastSeen], oldest first (the platform lists newest first). */
    fun newExitRecords(records: List<ExitRecord>, lastSeen: Long): List<ExitRecord> =
        records.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }

    fun nextExitLastSeen(recorded: List<ExitRecord>, lastSeen: Long): Long =
        maxOf(lastSeen, recorded.maxOfOrNull { it.timestamp } ?: lastSeen)

    fun exitDetail(record: ExitRecord): String = buildString {
        append("pid=").append(record.pid)
        append(" reason=").append(record.reason).append(':').append(exitReasonName(record.reason))
        if (record.reason == REASON_PACKAGE_UPDATED) append(" self-update")
        append(" imp=").append(record.importance)
        val description = clean(record.description, DESCRIPTION_MAX_CHARS)
        if (description.isNotEmpty()) append(" desc=\"").append(description.replace('"', '\'')).append('"')
        val state = clean(record.stateSummary, STATE_SUMMARY_MAX_BYTES)
        if (state.isNotEmpty()) append(" state=").append(state.replace(' ', '_'))
    }

    /**
     * `vis=<0|1>;fgs=<0|1>;relay=<state>` as ASCII, at most
     * [STATE_SUMMARY_MAX_BYTES] bytes.
     */
    fun stateSummary(visible: Boolean, foregroundService: Boolean, relay: String): ByteArray {
        val text = "vis=${bit(visible)};fgs=${bit(foregroundService)};relay=${token(relay, 24)}"
        return text.toByteArray(Charsets.US_ASCII).let {
            if (it.size <= STATE_SUMMARY_MAX_BYTES) it else it.copyOf(STATE_SUMMARY_MAX_BYTES)
        }
    }

    /**
     * The `diag` value [RelayClient] sends in the Socket.IO auth next to the
     * token: `p=<pid>;up=<process uptime s>;n=<client ordinal>;x=<last close
     * reason>:<seconds since>;v=<versionCode>;vis=<0|1>`, `x=none` before
     * the first close.
     *
     * ':' separates the reason from its age because '@' is outside the
     * server's allowed set and would get the whole field dropped.
     */
    fun relayDiag(
        pid: Int,
        uptimeSeconds: Long,
        ordinal: Int,
        lastCloseReason: String?,
        lastCloseAgeSeconds: Long?,
        versionCode: Int,
        visible: Boolean,
    ): String {
        val close = if (lastCloseReason == null) {
            "none"
        } else {
            "${token(lastCloseReason, 24)}:${(lastCloseAgeSeconds ?: 0L).coerceAtLeast(0L)}"
        }
        val raw = "p=$pid;up=${uptimeSeconds.coerceAtLeast(0L)};n=$ordinal;x=$close;v=$versionCode;vis=${bit(visible)}"
        return raw.replace(RELAY_DIAG_DISALLOWED, "_").take(RELAY_DIAG_MAX_CHARS)
    }

    /**
     * Which reconnect attempts reach the event log: 1, 2, 4, 8, … A relay
     * that stays down retries every few seconds and would otherwise flush the
     * whole ring in an hour.
     */
    fun isLoggedAttempt(attempt: Int): Boolean = attempt > 0 && (attempt and (attempt - 1)) == 0

    /** Short name of a service intent action for the log; `none` for a sticky restart's null. */
    fun actionLabel(action: String?): String =
        if (action == null) "none" else token(action.substringAfterLast('.'), 40)

    /**
     * The log after appending [appended] to [existing]. Within [maxBytes]
     * that is a plain append; past it the result is the newest whole lines
     * that fit in [targetBytes] (never a torn first line).
     */
    fun trimLog(existing: ByteArray, appended: ByteArray, maxBytes: Int, targetBytes: Int): ByteArray {
        val combined = existing + appended
        if (combined.size <= maxBytes) return combined
        val keep = minOf(targetBytes, maxBytes)
        var start = combined.size - keep
        if (combined[start - 1] != NEWLINE) {
            while (start < combined.size && combined[start] != NEWLINE) start++
            start++
        }
        return if (start >= combined.size) ByteArray(0) else combined.copyOfRange(start, combined.size)
    }

    private const val NEWLINE = '\n'.code.toByte()

    private fun bit(value: Boolean) = if (value) "1" else "0"
}
