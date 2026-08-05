package com.yunjelee.securemsg

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Latest release metadata parsed from the GitHub Releases API. */
data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String,
)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * Self-update flow: checks the GitHub release feed, downloads the APK over
 * HTTPS and hands it to the system package installer — the same UX as a game
 * update, no manual file handling required.
 */
class AppUpdater(private val ctx: Context, private val http: OkHttpClient) {

    private val prefs = ctx.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    fun autoCheckEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun dismissedTag(): String? = prefs.getString(KEY_DISMISSED, null)

    fun dismiss(tag: String) {
        prefs.edit().putString(KEY_DISMISSED, tag).apply()
    }

    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean =
        autoCheckEnabled() && now - prefs.getLong(KEY_LAST_CHECK, 0L) > CHECK_INTERVAL_MS

    /**
     * Contact GitHub and report whether a newer release exists.
     * [ignoreVersion] reports the latest release even when it is not newer —
     * used by the debug build to exercise the full download/install path.
     */
    suspend fun check(ignoreVersion: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "securemsg-android/${BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(req).execute().use { resp ->
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckResult.Failed("HTTP ${resp.code}")
                }
                val info = parseRelease(resp.body?.string().orEmpty())
                    ?: return@withContext UpdateCheckResult.Failed("릴리스 정보를 해석하지 못했습니다")
                if (ignoreVersion || isNewer(info.versionName, BuildConfig.VERSION_NAME)) {
                    UpdateCheckResult.Available(info)
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed(e.message ?: "네트워크 오류")
        }
    }

    /** Stream the APK into private storage, reporting whole-percent progress. */
    suspend fun download(info: UpdateInfo, onProgress: suspend (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.filesDir, "update").apply { mkdirs() }
            val target = File(dir, "securemsg-${info.versionName}.apk")
            val tmp = File(dir, target.name + ".part")
            val req = Request.Builder()
                .url(info.apkUrl)
                .header("User-Agent", "securemsg-android/${BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty response")
                val total = if (info.sizeBytes > 0) info.sizeBytes else body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        out.flush()
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        }

    /** Delete stale downloaded APKs so private storage does not accumulate them. */
    fun cleanupDownloads(olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        File(ctx.filesDir, "update").listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    fun canInstallPackages(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${ctx.packageName}"),
    )

    companion object {
        const val RELEASE_URL =
            "https://api.github.com/repos/leetomo2528/secure-msg/releases/latest"
        const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_DISMISSED = "dismissed_tag"

        fun buildHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /** Parse a GitHub `releases/latest` payload; null when unusable. */
        fun parseRelease(json: String): UpdateInfo? {
            return try {
                val obj = JSONObject(json)
                val tag = obj.optString("tag_name").trim()
                if (tag.isEmpty()) return null
                val assets = obj.optJSONArray("assets") ?: return null
                var apk: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a
                        break
                    }
                }
                apk ?: return null
                val url = apk.optString("browser_download_url")
                if (url.isEmpty()) return null
                UpdateInfo(
                    tag = tag,
                    versionName = tag.removePrefix("v").removePrefix("V"),
                    apkUrl = url,
                    sizeBytes = apk.optLong("size", 0L),
                    notes = obj.optString("body", "").take(500),
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Numeric semver-ish comparison ("0.10.0" > "0.9.9"). */
        fun isNewer(remote: String, current: String): Boolean {
            val r = parseVersion(remote) ?: return false
            val c = parseVersion(current) ?: return false
            for (i in 0 until maxOf(r.size, c.size)) {
                val ri = r.getOrElse(i) { 0 }
                val ci = c.getOrElse(i) { 0 }
                if (ri != ci) return ri > ci
            }
            return false
        }

        private fun parseVersion(s: String): List<Int>? {
            val t = s.trim().removePrefix("v").removePrefix("V")
            if (t.isEmpty()) return null
            return t.split('.').map { part ->
                part.takeWhile { it.isDigit() }.ifEmpty { return null }.toIntOrNull() ?: return null
            }
        }
    }
}
