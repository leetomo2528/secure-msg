package com.yunjelee.securemsg

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Pure tick-gating decision for the unattended updater, kept free of Android
 * types so the pending-state × visibility × network matrix is JVM-testable.
 */
object AutoUpdatePolicy {

    enum class Action {
        /** No pending entry: hit GitHub, download, verify, persist READY. */
        CHECK_AND_DOWNLOAD,

        /** A READY entry waits and the UI cannot be seen: commit it silently. */
        COMMIT_READY,

        /**
         * A wedged terminal entry (FAILED, or a SESSION_SUBMITTED that never
         * resolved) has sat unattended past the recovery age: drop it so the
         * next due tick can retry from scratch. Without this an unattended
         * device — the feature's stated purpose — never updates again after
         * one Play Protect block or untapped confirm.
         */
        CLEAR_WEDGED,

        SKIP,
    }

    fun decide(
        autoCheckEnabled: Boolean,
        autoInstallEnabled: Boolean,
        dueForCheck: Boolean,
        unmeteredValidatedNetwork: Boolean,
        pendingState: PendingInstallState?,
        appVisible: Boolean,
        pendingStale: Boolean = false,
        submittedInThisProcess: Boolean = false,
    ): Action {
        if (!autoCheckEnabled || !autoInstallEnabled) return Action.SKIP
        return when (pendingState) {
            // Network gate sits before check(): a metered/offline tick must not
            // consume the 12h stamp that check() writes.
            null -> if (dueForCheck && unmeteredValidatedNetwork) {
                Action.CHECK_AND_DOWNLOAD
            } else {
                Action.SKIP
            }
            // A silent commit kills the process; doing that while the user can
            // see the UI at all — including started-but-paused split-screen —
            // is hostile. The READY entry keeps the work: a later tick commits
            // it once the app leaves the screen, and no network or stamp
            // condition applies to a purely local commit.
            PendingInstallState.READY -> if (appVisible) Action.SKIP else Action.COMMIT_READY
            // FAILED needs the user's banner decision — but only while a user
            // is plausibly around to make one. Left alone it wedges every
            // future tick forever.
            PendingInstallState.FAILED ->
                if (pendingStale && !appVisible) Action.CLEAR_WEDGED else Action.SKIP
            // A SESSION_SUBMITTED still here after the recovery age is a
            // confirm nobody tapped. submittedInThisProcess deliberately does
            // not gate this: the submitting process is the START_STICKY
            // bridge that lives for days, so keying the reclaim on it wedged
            // the updater on exactly the unattended device this recovery
            // exists for. installViaSession abandons every prior session
            // before re-committing, so the retry cannot collide with the
            // entry dropped here.
            PendingInstallState.SESSION_SUBMITTED ->
                if (pendingStale && !appVisible) Action.CLEAR_WEDGED else Action.SKIP
            // AWAITING_PERMISSION/FALLBACK_LAUNCHED: manual-flow states the
            // user created while present; only the user resolves them.
            else -> Action.SKIP
        }
    }
}

/**
 * Fully unattended self-update: check → download → silent install, driven by
 * the bridge heartbeat. Eligibility for a truly silent commit (installer of
 * record + UPDATE_PACKAGES_WITHOUT_USER_ACTION) is the system's call; an
 * ineligible commit degrades to the existing confirm-notification flow.
 */
object AutoUpdate {

    private const val TAG = "AutoUpdate"

    /**
     * How long a wedged terminal entry must sit untouched before the tick may
     * reclaim it. A day: long enough that a user who is around sees the banner
     * or the confirm notification first, short enough that an unattended
     * device is not dark for more than one extra check window.
     */
    private const val WEDGE_RECOVERY_AGE_MS = 24 * 60 * 60 * 1000L

    /** Single-flight: heartbeats keep ticking while a 55MB download runs. */
    private val running = AtomicBoolean(false)

    /** One client for the life of the process: a 30s heartbeat must not build
     * a connection pool per tick just to be told to skip. */
    private val http by lazy { AppUpdater.buildHttp() }

    suspend fun maybeRun(context: Context) {
        if (!running.compareAndSet(false, true)) return
        try {
            runTick(context.applicationContext)
        } catch (e: CancellationException) {
            // The service scope is being torn down; the persisted entry (or
            // the 12h stamp) carries the work to the next process.
            throw e
        } catch (e: Exception) {
            // The heartbeat loop must survive any tick; the 12h stamp (or the
            // persisted entry) governs when work is attempted again.
            Log.e(TAG, "auto-update tick failed", e)
        } finally {
            running.set(false)
        }
    }

    private suspend fun runTick(app: Context) {
        val updater = AppUpdater(app, http)
        // Toggles first: with either off, the lifetime cost of a tick is two
        // pref reads, not a connectivity query.
        if (!updater.autoCheckEnabled() || !updater.autoInstallEnabled()) return
        val due = updater.shouldAutoCheck()
        val pending = updater.pendingUpdate()
        val action = AutoUpdatePolicy.decide(
            autoCheckEnabled = true,
            autoInstallEnabled = true,
            dueForCheck = due,
            // Only the CHECK_AND_DOWNLOAD branch consults the network; skip
            // the binder query on the ~2880 daily ticks that cannot take it.
            unmeteredValidatedNetwork = pending == null && due &&
                hasUnmeteredValidatedNetwork(app),
            pendingState = pending?.state,
            appVisible = SmsNotifier.isAppVisible(),
            pendingStale = pending != null &&
                System.currentTimeMillis() - pending.updatedAtMs > WEDGE_RECOVERY_AGE_MS,
            submittedInThisProcess = AppUpdater.installSubmittedInThisProcess,
        )
        when (action) {
            AutoUpdatePolicy.Action.SKIP -> {}
            AutoUpdatePolicy.Action.COMMIT_READY ->
                commitSilently(updater, checkNotNull(pending).info, pending.file)
            AutoUpdatePolicy.Action.CLEAR_WEDGED -> {
                val wedged = checkNotNull(pending)
                Log.w(
                    TAG,
                    "reclaiming wedged ${wedged.state} entry for " +
                        "v${wedged.info.versionName}; next due tick retries from scratch",
                )
                wedged.file.delete()
                updater.clearPendingUpdate()
                // A confirm notification for a flow that no longer exists
                // would linger in the shade indefinitely.
                InstallResultReceiver.cancelConfirmNotification(app)
            }
            AutoUpdatePolicy.Action.CHECK_AND_DOWNLOAD -> checkAndDownload(updater)
        }
    }

    private suspend fun checkAndDownload(updater: AppUpdater) {
        // dismissedTag is deliberately not consulted: dismiss governs the
        // banner only, and the settings caption says a deferred version still
        // installs unattended.
        val info = (updater.check() as? UpdateCheckResult.Available)?.info ?: return
        val file = try {
            updater.download(info) { }
        } catch (e: Exception) {
            // No FAILED entry for a background miss: a red banner the user
            // never acted on would also wedge every future tick. The 12h
            // stamp check() just wrote rate-limits the retry.
            Log.w(TAG, "background download failed; retrying next check window", e)
            return
        }
        try {
            updater.verifyApkSigningCertificate(file)
        } catch (e: Exception) {
            Log.w(TAG, "downloaded APK failed verification; discarded", e)
            file.delete()
            return
        }
        // If-absent: the download ran for minutes after the tick's gate saw no
        // entry, and a manual flow started in that window owns the update now —
        // its committed session's token and confirm must not be clobbered.
        val persisted = updater.persistPendingUpdateIfAbsent(
            info,
            file,
            PendingInstallState.READY,
            UUID.randomUUID().toString(),
        )
        if (!persisted) {
            Log.i(TAG, "manual flow claimed v${info.versionName} mid-download; discarding")
            file.delete()
            return
        }
        if (SmsNotifier.isAppVisible()) {
            // Install (not download) defers while the UI is on screen. The
            // READY entry short-circuits later ticks into COMMIT_READY — and
            // renders as the ordinary "지금 설치" banner if the user opens the
            // app first.
            Log.i(TAG, "v${info.versionName} downloaded; commit deferred while app is on screen")
            return
        }
        commitSilently(updater, info, file)
    }

    private suspend fun commitSilently(
        updater: AppUpdater,
        info: UpdateInfo,
        file: java.io.File,
    ) {
        // Fresh token per attempt, mirroring MainActivity.startInstall: a
        // stale session's late callbacks must keep failing
        // isAuthorizedInstallCallback against the newly persisted token.
        val token = UUID.randomUUID().toString()
        // Flag before persist: MainActivity launching in this same process
        // between the two would otherwise read SESSION_SUBMITTED with the flag
        // still unset and convert a live submission to FAILED.
        AppUpdater.installSubmittedInThisProcess = true
        updater.persistPendingUpdate(info, file, PendingInstallState.SESSION_SUBMITTED, token)
        val submitted = updater.installViaSession(file, token, userActionNotRequired = true)
        if (!submitted) {
            // No UI to hand the legacy VIEW-intent fallback to. Dropping the
            // entry (not FAILED) keeps the banner quiet and lets the next 12h
            // window retry from scratch instead of looping every heartbeat.
            Log.w(TAG, "background session submit failed; entry dropped")
            file.delete()
            updater.clearPendingUpdate()
        }
    }

    private fun hasUnmeteredValidatedNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
