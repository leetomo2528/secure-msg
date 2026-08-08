package com.yunjelee.securemsg

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
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

enum class PendingInstallState {
    READY,
    AWAITING_PERMISSION,
    SESSION_SUBMITTED,
    FALLBACK_LAUNCHED,
    FAILED,
}

data class PendingUpdate(
    val info: UpdateInfo,
    val file: File,
    val state: PendingInstallState,
    val callbackToken: String,
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
            tmp.delete()
            var promotionStarted = false
            try {
                val req = Request.Builder()
                    .url(info.apkUrl)
                    .header("User-Agent", "securemsg-android/${BuildConfig.VERSION_NAME}")
                    .build()
                var downloadedBytes = 0L
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty response")
                    val total = if (info.sizeBytes > 0) info.sizeBytes else body.contentLength()
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                out.write(buf, 0, n)
                                downloadedBytes += n
                                if (total > 0) {
                                    val pct = ((downloadedBytes * 100) / total)
                                        .toInt().coerceIn(0, 100)
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
                if (!UpdateValidation.hasExpectedSize(info.sizeBytes, downloadedBytes)) {
                    throw IOException(
                        "APK 크기 불일치: expected=${info.sizeBytes}, actual=$downloadedBytes",
                    )
                }
                promotionStarted = true
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target
            } catch (e: Exception) {
                // A short read and a failed copy must never leave an installable-looking APK.
                tmp.delete()
                if (promotionStarted) target.delete()
                throw e
            }
        }

    /** Delete stale downloaded APKs so private storage does not accumulate them. */
    fun cleanupDownloads(olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val pendingPath = pendingUpdate()?.file?.absolutePath
        File(ctx.filesDir, "update").listFiles()
            ?.filter { it.lastModified() < cutoff && it.absolutePath != pendingPath }
            ?.forEach { it.delete() }
    }

    fun canInstallPackages(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Reject APKs that do not belong to this application or use another signer. */
    @Suppress("DEPRECATION")
    @Throws(IOException::class)
    fun verifyApkSigningCertificate(file: File) {
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: throw IOException("APK 패키지 정보를 읽지 못했습니다")
        if (archive.packageName != ctx.packageName) {
            throw IOException("다른 앱의 APK입니다 (${archive.packageName})")
        }
        val installed = try {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            throw IOException("설치된 앱의 서명 정보를 읽지 못했습니다", e)
        }
        if (!UpdateValidation.signersMatch(signerBytes(installed), signerBytes(archive))) {
            throw IOException("APK 서명이 현재 설치된 SecureMsg와 일치하지 않습니다")
        }
    }

    private fun signerBytes(info: PackageInfo): List<ByteArray> =
        info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()

    @Synchronized
    fun persistPendingUpdate(
        info: UpdateInfo,
        file: File,
        state: PendingInstallState = PendingInstallState.READY,
        callbackToken: String = "",
    ) {
        val json = JSONObject()
            .put("tag", info.tag)
            .put("versionName", info.versionName)
            .put("apkUrl", info.apkUrl)
            .put("sizeBytes", info.sizeBytes)
            .put("notes", info.notes)
            .put("file", file.absolutePath)
            .put("state", state.name)
            .put("callbackToken", callbackToken)
        // commit() is intentional: PackageInstaller can replace/kill this process immediately.
        prefs.edit().putString(KEY_PENDING_UPDATE, json.toString()).commit()
    }

    @Synchronized
    fun setPendingInstallState(state: PendingInstallState): Boolean {
        val pending = pendingUpdate() ?: return false
        persistPendingUpdate(pending.info, pending.file, state, pending.callbackToken)
        return true
    }

    @Synchronized
    fun claimFallbackLaunch(): Boolean {
        val pending = pendingUpdate() ?: return false
        if (!UpdateValidation.shouldLaunchFallback(pending.state)) return false
        persistPendingUpdate(
            pending.info,
            pending.file,
            PendingInstallState.FALLBACK_LAUNCHED,
            pending.callbackToken,
        )
        return true
    }

    fun pendingUpdate(): PendingUpdate? {
        val raw = prefs.getString(KEY_PENDING_UPDATE, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val file = File(obj.getString("file"))
            val updateDir = File(ctx.filesDir, "update").canonicalFile
            val canonical = file.canonicalFile
            if (!canonical.isFile || canonical.parentFile != updateDir) return null
            PendingUpdate(
                info = UpdateInfo(
                    tag = obj.getString("tag"),
                    versionName = obj.getString("versionName"),
                    apkUrl = obj.getString("apkUrl"),
                    sizeBytes = obj.optLong("sizeBytes", 0L),
                    notes = obj.optString("notes", ""),
                ),
                file = canonical,
                state = PendingInstallState.valueOf(obj.optString("state", "READY")),
                callbackToken = obj.optString("callbackToken", ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearPendingUpdate() {
        prefs.edit().remove(KEY_PENDING_UPDATE).commit()
    }

    /**
     * Session-based install so the app observes the outcome (Play Protect
     * block, user cancel, conflict…). Returns false when sessions are
     * unavailable and the caller should fall back to [installIntent].
     */
    suspend fun installViaSession(file: File, resultIntent: Intent): Boolean =
        withContext(Dispatchers.IO) {
            val installer = ctx.packageManager.packageInstaller
            var sessionId = -1
            try {
                verifyApkSigningCertificate(file)
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                ).apply { setSize(file.length()) }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("securemsg-update", 0, file.length()).use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                        session.fsync(out)
                    }
                    val pending = PendingIntent.getActivity(
                        ctx,
                        sessionId,
                        resultIntent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    session.commit(pending.intentSender)
                }
                true
            } catch (e: Exception) {
                if (sessionId >= 0) {
                    try {
                        installer.abandonSession(sessionId)
                    } catch (abandonError: Exception) {
                        e.addSuppressed(abandonError)
                    }
                }
                Log.w("AppUpdater", "session install failed; falling back to VIEW", e)
                false
            }
        }

    fun installIntent(file: File): Intent {
        verifyApkSigningCertificate(file)
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
        private const val KEY_PENDING_UPDATE = "pending_update"

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

/** Pure validation helpers kept independent from Android APIs for local unit tests. */
object UpdateValidation {
    fun hasExpectedSize(expectedBytes: Long, actualBytes: Long): Boolean =
        expectedBytes <= 0 || expectedBytes == actualBytes

    fun signersMatch(installedCertificates: Collection<ByteArray>, apkCertificates: Collection<ByteArray>): Boolean {
        if (installedCertificates.isEmpty() || apkCertificates.isEmpty()) return false
        return installedCertificates.map(::sha256).toSet() == apkCertificates.map(::sha256).toSet()
    }

    fun shouldLaunchFallback(state: PendingInstallState): Boolean =
        state == PendingInstallState.SESSION_SUBMITTED

    fun shouldAutoCheck(hasPendingInstall: Boolean): Boolean = !hasPendingInstall

    fun shouldStartInstallSession(state: PendingInstallState?): Boolean =
        state != PendingInstallState.SESSION_SUBMITTED

    private fun sha256(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .joinToString("") { "%02x".format(it) }
}

/**
 * PackageInstaller result codes mirrored as plain constants (unit-testable
 * without an Android runtime) plus the user-facing guidance for failures.
 * Values match [PackageInstaller].STATUS_*.
 */
object InstallResults {
    const val PENDING_USER_ACTION = -1
    const val SUCCESS = 0
    const val FAILURE = 1
    const val FAILURE_BLOCKED = 2
    const val FAILURE_ABORTED = 3
    const val FAILURE_INVALID = 4
    const val FAILURE_CONFLICT = 5
    const val FAILURE_STORAGE = 6
    const val FAILURE_INCOMPATIBLE = 7

    /** Short, human-readable explanation shown in the update banner. */
    fun guidance(status: Int): String = when (status) {
        FAILURE_BLOCKED ->
            "시스템(Play Protect)이 설치를 차단했습니다. 이 APK는 GitHub 릴리스에서 받은 " +
                "같은 서명의 SecureMsg 업데이트입니다. 차단 화면의 시스템 세부정보에서 설치를 " +
                "허용하거나, GitHub 릴리스 페이지에서 최신 APK를 직접 설치하세요."
        FAILURE_ABORTED ->
            "설치가 취소되었습니다. 업데이트하려면 '재시도'를 누르거나 GitHub 릴리스 페이지에서 " +
                "최신 APK를 직접 설치하세요."
        FAILURE_CONFLICT, FAILURE_INCOMPATIBLE ->
            "설치 충돌: 기존 앱과 서명이 다르거나 버전이 낮아 덮어쓸 수 없습니다. " +
                "기존 앱을 삭제하면 allowBackup=false 설정 때문에 이 기기의 로컬 암호화 키와 " +
                "메시지가 모두 삭제되고 복구되지 않습니다. 데이터를 보존해야 한다면 삭제하지 말고, " +
                "그 위험을 감수할 경우에만 삭제 후 GitHub 릴리스의 최신 APK를 설치하세요."
        FAILURE_STORAGE -> "저장 공간이 부족해 설치하지 못했습니다."
        FAILURE_INVALID -> "APK 파일이 손상되었습니다. 다시 다운로드해 보세요."
        else -> "설치에 실패했습니다. 다시 시도해 보세요."
    }
}
