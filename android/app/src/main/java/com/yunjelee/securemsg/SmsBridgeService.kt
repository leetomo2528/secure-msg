package com.yunjelee.securemsg

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.room.withTransaction
import com.yunjelee.securemsg.ui.LastOpened
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground remote-messaging service.
 *
 * Carrier -> device: SmsReceiver has already classified/persisted the SMS, then
 * this service encrypts it and relays it to the user's other devices.
 *
 * Web/PWA -> device: decrypt an envelope from the relay, send it over the carrier
 * network, and persist the sent SMS locally because the default SMS app owns the
 * system SMS Provider.
 */
class SmsBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridgeMutex = Mutex()
    private val incomingMutex = Mutex()
    private val syncMutex = Mutex()
    private val outboxMutex = Mutex()
    private var relay: RelayClient? = null
    private var api: RelayApi? = null
    private var creds: SavedCredentials? = null
    private lateinit var db: AppDatabase
    private lateinit var incomingRepository: IncomingMessageRepository
    private val outboxLoopStarted = AtomicBoolean(false)
    private val sessionInvalidated = AtomicBoolean(false)
    private val lastTrustRefreshAt = AtomicLong(0L)
    /**
     * MMS id -> deferred retries already spent on it. Entries are never removed:
     * the count *is* the "how often has this row been nudged" marker, and
     * clearing an id would let a permanently malformed row reschedule itself
     * forever.
     */
    private val mmsRetryAttempts = ConcurrentHashMap<Long, Int>()

    companion object {
        const val ACTION_INCOMING_SMS = "com.yunjelee.securemsg.INCOMING_SMS"
        const val ACTION_INCOMING_MMS = "com.yunjelee.securemsg.INCOMING_MMS"
        const val ACTION_START_BRIDGE = "com.yunjelee.securemsg.START_BRIDGE"
        const val ACTION_CARRIER_STATUS = "com.yunjelee.securemsg.CARRIER_STATUS"
        const val ACTION_SEND_LOCAL_SMS = "com.yunjelee.securemsg.SEND_LOCAL_SMS"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_BODY = "body"
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_PROVIDER_EPOCH = "provider_epoch"
        const val EXTRA_RECEIVED_AT = "received_at"
        private const val TAG = "SmsBridgeService"
        private const val CLAIM_RETRY_GRACE_MS = 30_000L
        /** Floor between unforced key-directory re-reads; see [refreshDeviceTrust]. */
        private const val TRUST_REFRESH_MIN_INTERVAL_MS = 60_000L
        /**
         * Backoff for a downloaded-but-not-ready MMS. Three tries spanning ~5
         * minutes: long enough to outlast a slow part download on a weak data
         * link, short enough that a row nobody can parse stops costing wakeups.
         */
        private val MMS_DEFER_RETRY_DELAYS_MS = longArrayOf(15_000L, 60_000L, 240_000L)
        /** Ids tracked for deferred retry; a bound the provider cannot exceed in practice. */
        private const val MMS_DEFER_TRACKED_MAX = 512
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)
        incomingRepository = IncomingMessageRepository(db)
        BridgeNotifications.createChannel(this)
        // Create the message channel up front so it is configurable in system
        // settings before the first SMS, not only after one has arrived. The
        // legacy-channel cleanup is a one-shot migration and belongs here rather
        // than on the per-message path.
        SmsNotifier.ensureChannel(this)
        SmsNotifier.retireLegacyChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground()
        } catch (e: SecurityException) {
            // Do not take down the process when the user has not completed the
            // default-SMS role or runtime permission flow yet. MainActivity
            // will retry after the permission/role result callback.
            Log.e(TAG, "Cannot start bridge foreground service; permissions/role incomplete", e)
            stopSelfResult(startId)
            return START_NOT_STICKY
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot start bridge foreground service", e)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Every started service instance gets the heartbeat, not only one whose
        // relay connects: a deferred auto-update commit must still happen in a
        // process whose self-hosted relay is down (reboot during an outage).
        // The loop itself guards all relay-dependent work.
        startOutboxLoop()

        when (intent?.action) {
            ACTION_INCOMING_SMS -> {
                val phone = intent.getStringExtra(EXTRA_PHONE) ?: return START_STICKY
                val body = intent.getStringExtra(EXTRA_BODY) ?: return START_STICKY
                val providerId = intent.getLongExtra(EXTRA_PROVIDER_ID, -1L).takeIf { it > 0 }
                val providerEpoch = intent.getLongExtra(EXTRA_PROVIDER_EPOCH, 0L)
                val receivedAt = intent.getLongExtra(
                    EXTRA_RECEIVED_AT,
                    System.currentTimeMillis(),
                )
                scope.launch {
                    try {
                        incomingMutex.withLock {
                            handleIncomingSms(
                                phone, body, providerId, receivedAt, providerEpoch,
                                // A broadcast delivered this one: live, whatever
                                // timestamp the SMSC put on it.
                                rescan = false,
                            )
                        }
                        ensureBridgeReady()
                        flushOutbox()
                    } catch (e: Exception) {
                        Log.e(TAG, "Incoming SMS relay failed", e)
                    }
                }
            }

            ACTION_CARRIER_STATUS -> {
                scope.launch {
                    try {
                        ensureBridgeReady()
                        handleCarrierStatus(intent)
                        flushOutbox()
                    } catch (e: Exception) {
                        Log.e(TAG, "Carrier status handling failed", e)
                    }
                }
            }

            ACTION_SEND_LOCAL_SMS -> {
                val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
                val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
                scope.launch {
                    try {
                        val loaded = Credentials.load(this@SmsBridgeService)
                        if (loaded != null) {
                            OutgoingSmsDispatcher.queueAndSend(
                                this@SmsBridgeService,
                                loaded,
                                phone,
                                body,
                            )
                            ensureBridgeReady()
                        } else {
                            SmsSender.send(this@SmsBridgeService, phone, body)
                            stopSelfResult(startId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "System quick-reply send failed", e)
                        stopSelfResult(startId)
                    }
                }
            }

            ACTION_INCOMING_MMS -> {
                val id = intent.getLongExtra(MmsReceiver.EXTRA_MMS_ID, -1L).takeIf { it > 0 }
                scope.launch {
                    try {
                        incomingMutex.withLock {
                            // Both branches are live: MmsReceiver also routes a
                            // WAP push with no content-location, and a failed
                            // download, through the id-less sweep.
                            if (id != null) {
                                processIncomingMms(id, rescan = false)
                            } else {
                                processRecentMms(rescan = false)
                            }
                        }
                        ensureBridgeReady()
                        flushOutbox()
                    } catch (e: Exception) {
                        Log.e(TAG, "Incoming MMS processing failed", e)
                    }
                }
            }

            ACTION_START_BRIDGE, null -> {
                scope.launch {
                    try {
                        ensureBridgeReady()
                        incomingMutex.withLock {
                            importRecentInbox()
                            processRecentMms(rescan = true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Bridge startup sync failed", e)
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun ensureBridgeReady() {
        bridgeMutex.withLock { startBridge() }
        if (relay?.awaitConnected() == true) {
            startOutboxLoop()
            syncFromServer()
            flushOutbox()
            flushReceiptStatuses()
        }
    }

    /**
     * Retry durable work even when no new SMS or Socket.IO event wakes the
     * service. The 30-second unknown-state grace period prevents a startup
     * race from immediately repeating a carrier call that may still be in
     * progress when the previous process disappeared.
     */
    private fun startOutboxLoop() {
        if (!outboxLoopStarted.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                // Unattended self-update rides the same heartbeat, relay or
                // not — launched, never awaited: a multi-minute APK download
                // must not stall the 30s durable retries this loop exists
                // for, and maybeRun's single-flight guard keeps successive
                // ticks from stacking downloads. Ticked before the first
                // delay so a deferred commit gets its chance even in a
                // process whose bridge start is about to fail and stop the
                // service.
                launch { AutoUpdate.maybeRun(applicationContext) }
                delay(30_000L)
                if (relay?.isConnected == true) {
                    try {
                        syncFromServer()
                        flushOutbox()
                        flushReceiptStatuses()
                    } catch (e: Exception) {
                        // One malformed row or transient provider failure must not
                        // permanently terminate the durable retry loop.
                        Log.e(TAG, "Periodic bridge recovery failed", e)
                    }
                }
            }
        }
    }

    private suspend fun startBridge() {
        if (relay?.isConnected == true) return
        // RoleManager is the source of truth on API 29+; the legacy
        // Telephony.Sms.getDefaultSmsPackage can disagree on some images.
        val roleHeld = getSystemService(android.app.role.RoleManager::class.java)
            .isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
        if (!roleHeld) {
            Log.w(
                TAG,
                "SecureMsg is not the default SMS app; bridge remains idle " +
                    "(legacy default=${Telephony.Sms.getDefaultSmsPackage(this)})",
            )
            stopSelf()
            return
        }
        val loaded = Credentials.load(this) ?: run {
            Log.w(TAG, "No credentials — bridge idle")
            stopSelf()
            return
        }
        creds = loaded

        val serverUrl = getServerUrl()
        val relayApi = RelayApi(serverUrl).also { it.token = loaded.token }
        api = relayApi
        // Auth is checked BEFORE device trust, and the order is load-bearing.
        // DeviceSecurityController.refresh() calls GET /key-directory, which is
        // @auth_required; it special-cases only 404 (serverUnsupported) and 403
        // (selfPending), so a 401 falls through to a generic `error` and the
        // trust gate below answers it with a silent stopSelf(). That skips
        // notifySessionExpired and Credentials.clearSession, leaves the dead
        // token on disk, and every later start repeats it — the bridge just
        // goes dark. The server's absolute session cap
        // (SESSION_MAX_AGE_SECONDS) makes token death a scheduled certainty
        // rather than a rare accident, so this can no longer be a rare path.
        val authCheck = try {
            relayApi.listConversations()
        } catch (_: Exception) {
            // Offline: say nothing and let the next start retry. Only a
            // server that actually answered 401 is a dead session.
            null
        }
        if (authCheck?.optInt("_http_status") == 401) {
            invalidateSession("REST authentication rejected")
            return
        }
        val trustView = DeviceSecurityController(
            RelayTrustedDeviceApi(relayApi),
            loaded,
            DeviceTrustRepository(db),
        ).refresh()
        if (trustView.blocksDirectoryUse) {
            Log.e(TAG, "Bridge blocked by device trust: $trustView")
            stopSelf()
            return
        }
        relay?.disconnect()
        relay = RelayClient(serverUrl).also { client ->
            client.onConnect = {
                Log.i(TAG, "Relay connected")
                startOutboxLoop()
                scope.launch {
                    try {
                        refreshAuthToken(relayApi)
                        BlocklistSync.sync(this@SmsBridgeService, relayApi)
                        syncFromServer()
                        incomingMutex.withLock {
                            importRecentInbox()
                            processRecentMms(rescan = true)
                        }
                        flushOutbox()
                        flushReceiptStatuses()
                    } catch (e: Exception) {
                        Log.e(TAG, "Reconnect recovery failed", e)
                    }
                }
            }
            client.onDisconnect = { Log.w(TAG, "Relay disconnected — will auto-reconnect") }
            client.onConnectError = { message ->
                Log.w(TAG, "Relay connection error: $message")
                // Servers >= v0.10.8 prefix refusals with a stable
                // "auth_rejected:" code. Fall back to the legacy prose match
                // for older servers instead of re-connecting forever with a
                // dead token.
                if (message.startsWith("auth_rejected", ignoreCase = true) ||
                    message.contains("invalid token", ignoreCase = true) ||
                    message.contains("device unknown", ignoreCase = true) ||
                    message.contains("auth required", ignoreCase = true) ||
                    message.contains("unauthenticated", ignoreCase = true)
                ) {
                    invalidateSession(message)
                }
            }
            client.onMessageNew = { env -> handleRelayMessage(env) }
            client.onBlocklistUpdated = {
                // Another device changed the shared block rules.
                scope.launch {
                    try {
                        BlocklistSync.sync(this@SmsBridgeService, relayApi)
                    } catch (e: Exception) {
                        Log.e(TAG, "blocklist sync failed", e)
                    }
                }
            }
            client.onConvUpdated = { data ->
                val cid = data.optString("cid")
                val name = data.optString("name")
                if (cid.isNotBlank()) {
                    scope.launch {
                        try {
                            AppDatabase.get(this@SmsBridgeService)
                                .threadDao().updateServerNameByCid(cid, name)
                        } catch (e: Exception) {
                            Log.e(TAG, "conv rename apply failed", e)
                        }
                    }
                }
            }
            client.onContactsUpdated = {
                // The event is an invalidation signal. Re-listing is resilient
                // to missed events and guarantees a complete server snapshot.
                scope.launch { syncFromServer() }
            }
            client.onDevicePending = {
                // Existing traffic remains on the last verified directory. The settings
                // screen exposes the approval request; every new fan-out refreshes and
                // verifies the directory before accepting recipient keys.
                Log.w(TAG, "New device approval is pending")
            }
            client.onDeviceApproved = {
                // The receive path pins nothing on its own, so until this
                // lands every envelope from the newly approved device is
                // refused as unpinned — and syncConversation stops at the
                // first refusal, holding the rest of that conversation too.
                scope.launch {
                    try {
                        if (refreshDeviceTrust("device approval", force = true)) syncFromServer()
                    } catch (e: Exception) {
                        Log.e(TAG, "Trust refresh after device approval failed", e)
                    }
                }
            }
        }
        relay!!.connect(loaded.token)
        // Account usernames are identifiers; keep them out of logcat like the
        // redacted phone numbers used everywhere else in this app.
        Log.i(TAG, "Bridge started")
    }

    private fun invalidateSession(reason: String) {
        if (!sessionInvalidated.compareAndSet(false, true)) return
        Log.w(TAG, "Clearing rejected relay session: $reason")
        // This used to be the ONLY record that sync had stopped: the token hit
        // its 7-day TTL, the bridge cleared the session and quietly stopped,
        // and the phone went dark for a day while the web kept working. The
        // user must be told, or the next symptom is "messages stopped syncing".
        SmsNotifier.notifySessionExpired(this)
        scope.launch {
            try {
                Credentials.clearSession(this@SmsBridgeService)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // clearSession re-encrypts through the Keystore and can throw.
                // Uncaught here it takes down the default SMS app, and the
                // one-shot flag above would then refuse every later attempt,
                // so the bridge would reconnect with the dead token until the
                // process died. Let the next rejection try again.
                Log.e(TAG, "Clearing the rejected relay session failed", e)
                sessionInvalidated.set(false)
            } finally {
                relay?.disconnect()
                stopSelf()
            }
        }
    }

    /**
     * Sliding token renewal, at most once per 6 hours. The bridge reconnects
     * far more often than weekly (every incoming SMS and app open), so any
     * phone in normal use stays ahead of the 7-day TTL forever. The current
     * socket keeps its already-accepted auth; the renewed token is what the
     * NEXT service start loads from Credentials.
     */
    private suspend fun refreshAuthToken(relayApi: RelayApi) {
        val prefs = getSharedPreferences("relay_session", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_token_refresh", 0L) < 6 * 60 * 60 * 1000L) return
        val response = try {
            relayApi.refreshToken()
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh request failed", e)
            return
        }
        val fresh = response.optString("token")
        if (!response.optBoolean("ok") || fresh.isEmpty()) {
            // The relay distinguishes "this session hit its absolute age cap"
            // from an ordinary refusal. The key on this device is still good
            // and one /device-login gets the bridge back, but nothing here can
            // do that unattended — so tell the user now instead of letting the
            // token die and the bridge go quiet at the next start.
            if (response.optString("code") == "session_expired") {
                invalidateSession("session reached its absolute age limit")
                return
            }
            Log.w(TAG, "Token refresh refused: ${response.optString("error", "unknown")}")
            return
        }
        if (Credentials.updateToken(this, fresh)) {
            relayApi.token = fresh
            prefs.edit().putLong("last_token_refresh", now).apply()
            Log.i(TAG, "Relay token renewed")
        }
    }

    private fun getServerUrl(): String {
        return ServerConfig.url(this)
    }

    /**
     * The process-wide RelayApi, created on first use.
     *
     * Cached rather than rebuilt per call because [refreshAuthToken] renews the
     * token on the instance this returns; a fresh RelayApi per caller would keep
     * handing the relay the token that was on disk at service start.
     */
    private fun apiFor(c: SavedCredentials): RelayApi =
        api ?: RelayApi(getServerUrl()).also { it.token = c.token }.also { api = it }

    /**
     * Carrier SMS -> encrypted multi-device relay.
     *
     * @param rescan true when the row came from sweeping the provider rather than
     *   from an SMS_DELIVER broadcast; see [IncomingNotificationPolicy.shouldNotify].
     */
    private suspend fun handleIncomingSms(
        phone: String,
        body: String,
        providerId: Long? = null,
        receivedAt: Long = System.currentTimeMillis(),
        providerEpoch: Long = 0,
        rescan: Boolean = true,
    ) {
        val content = RelayContentCodec.text(body)
        val identity = ProviderIdentity.snapshot(
            ProviderIdentity.SMS, providerEpoch, providerId, phone, receivedAt,
            RelayContentCodec.encode(content),
        )
        if (phone.isBlank() || body.isBlank()) {
            // A malformed provider row can never be relayed; mark it processed
            // so startup imports stop retrying it forever.
            providerId?.let { db.processedSmsDao().insert(ProcessedSms(providerEpoch, it, identity.fingerprint)) }
            return
        }
        if (providerId != null && db.processedSmsDao().contains(providerEpoch, providerId)) return
        if (providerId != null && db.relayOutboxDao()
                .getByProviderId(providerEpoch, providerId, "incoming_sms") != null
        ) {
            flushOutbox()
            return
        }

        // Defence in depth: SmsReceiver performs the same check before provider
        // insertion, while this also covers history imported after login.
        val decision = BlocklistManager.evaluate(phone, body, db)
        if (decision.blocked) {
            db.blockedSmsDao().insert(
                BlockedSms(
                    phoneNumber = phone,
                    body = body,
                    reason = decision.reason,
                    receivedAt = receivedAt,
                ),
            )
            providerId?.let { db.processedSmsDao().insert(ProcessedSms(providerEpoch, it, identity.fingerprint)) }
            Log.i(TAG, "SMS not relayed: ${decision.reason}")
            return
        }

        val persisted = incomingRepository.persistCarrier(
            kind = ProviderIdentity.SMS,
            direction = "incoming_sms",
            phoneNumber = phone,
            content = content,
            providerId = providerId,
            receivedAt = receivedAt,
        )
        // Recovery notification. SmsReceiver normally notifies first and this call
        // then sees newlyCreated = false, but when the broadcast coroutine dies
        // before its Room transaction commits, this import is the only chance the
        // message ever gets to reach the shade — and on that live path the age
        // gate must not apply, or an SMS the carrier queued overnight lands in
        // the thread with nothing announcing it.
        notifyIfLive(persisted, rescan, body, receivedAt)
        flushOutbox()
    }

    /**
     * The single notify gate for both carrier paths. [date] is the message's own
     * timestamp, not now: the age gate in [IncomingNotificationPolicy] is what
     * keeps a logout-cleared ledger from re-announcing the whole inbox.
     */
    private fun notifyIfLive(
        persisted: IncomingMessageRepository.Persisted?,
        rescan: Boolean,
        body: String,
        date: Long,
    ) {
        val fresh = persisted?.takeIf {
            IncomingNotificationPolicy.shouldNotify(
                rescan, it.newlyCreated, date, System.currentTimeMillis(),
            )
        } ?: return
        SmsNotifier.notifyIncoming(
            context = this,
            phoneNumber = fresh.conversation.normalizedPhone,
            body = body,
            date = date,
            cid = fresh.conversation.cid,
            messageIdentity = fresh.outbox.mid,
            displayName = fresh.conversation.displayName,
            // A sweep import must not banner: dozens at once is what gets
            // the HIGH channel demoted to silent by adaptive notifications.
            liveAlert = !rescan,
        )
    }

    private suspend fun processRecentMms(rescan: Boolean) {
        MmsRowProcessor.process(
            MmsProvider.recentInbox(this),
            { id -> processIncomingMms(id, rescan) },
        ) { id, error ->
            Log.e(TAG, "failed to process recent MMS id=$id; continuing", error)
        }
    }

    /**
     * @param rescan true when the row was found by a startup/reconnect sweep
     *   rather than by a receiver broadcast, which makes the message eligible for
     *   the age gate in [IncomingNotificationPolicy.shouldNotifyRescan]. A
     *   deferred retry keeps the flag of the call that scheduled it, so an MMS
     *   whose parts take minutes to land still notifies as the live message it is.
     */
    private suspend fun processIncomingMms(id: Long, rescan: Boolean) {
        val mms = MmsProvider.read(this, id)
        if (!IncomingMmsPolicy.isReady(mms)) {
            // The platform may expose the inbox row before its address and
            // parts finish downloading. Leave it unprocessed so a later
            // receiver event or startup scan can retry without losing it.
            Log.i(TAG, "MMS not ready; deferring id=$id")
            scheduleDeferredMmsRetry(id, rescan)
            return
        }
        checkNotNull(mms)
        val phone = PhoneNumberNormalizer.normalize(mms.address)
        val content = RelayContent(
            type = RelayContentCodec.TYPE_MMS,
            text = mms.body,
            subject = mms.subject,
            attachments = mms.parts.map {
                RelayAttachment(it.name, it.contentType, RelayContentCodec.encodeBytes(it.bytes), it.bytes.size)
            },
        )
        val encodedContent = RelayContentCodec.encode(content)
        val identity = ProviderIdentityResolver.resolve(
            db, ProviderIdentity.MMS, id, phone, mms.date, encodedContent,
        )
        if (db.processedMmsDao().contains(identity.epoch, id)) return
        if (db.relayOutboxDao().getByProviderId(identity.epoch, id, "incoming_mms") != null) {
            flushOutbox()
            return
        }
        if (phone.isBlank()) {
            // Only a provider row with no usable address is dropped here. A
            // sender that is not a phone number — an alphanumeric id, an
            // email gateway — is stored and notified like the SMS path does;
            // this app is the default SMS app and reads nothing but Room, so
            // refusing it used to make the message unreachable everywhere.
            db.processedMmsDao().insert(ProcessedMms(identity.epoch, id, identity.fingerprint))
            return
        }
        val filterText = listOfNotNull(mms.subject, mms.body).joinToString("\n")
        val decision = BlocklistManager.evaluate(phone, filterText, db)
        if (decision.blocked) {
            db.blockedSmsDao().insert(
                BlockedSms(
                    phoneNumber = phone,
                    body = "[MMS] ${filterText.take(400)}",
                    reason = "MMS: ${decision.reason}",
                    receivedAt = mms.date,
                ),
            )
            db.processedMmsDao().insert(ProcessedMms(identity.epoch, id, identity.fingerprint))
            MmsProvider.delete(this, id)
            Log.i(TAG, "MMS quarantined id=$id: ${decision.reason}")
            return
        }
        val persisted = incomingRepository.persistCarrier(
            kind = ProviderIdentity.MMS,
            direction = "incoming_mms",
            phoneNumber = phone,
            content = content,
            providerId = id,
            receivedAt = mms.date,
        )
        // A logout clears the processed-MMS ledger, so without the age gate the
        // next startup sweep would re-notify every inbox row it can still see.
        notifyIfLive(persisted, rescan, IncomingNotificationPolicy.preview(content), mms.date)
        flushOutbox()
    }

    /**
     * Nudges a deferred MMS a few times with backoff.
     *
     * A row that is not ready yet otherwise waits for the next receiver event or
     * app start — potentially forever, and the sweep that finally finds it is a
     * rescan, so an MMS deferred at 23:30 and rediscovered at 09:00 would be
     * filed into the thread with no notification at all. Retrying keeps it on the
     * live path until the parts land, while the attempt cap keeps a permanently
     * malformed row from spinning the service. [processRecentMms] remains the
     * backstop for anything this misses.
     */
    private fun scheduleDeferredMmsRetry(id: Long, rescan: Boolean) {
        // Callers hold incomingMutex, so read-modify-write here needs no CAS.
        val spent = mmsRetryAttempts[id] ?: 0
        if (spent >= MMS_DEFER_RETRY_DELAYS_MS.size) return
        if (spent == 0 && mmsRetryAttempts.size >= MMS_DEFER_TRACKED_MAX) {
            Log.w(TAG, "deferred MMS retry table full; leaving id=$id to the next sweep")
            return
        }
        mmsRetryAttempts[id] = spent + 1
        scope.launch {
            delay(MMS_DEFER_RETRY_DELAYS_MS[spent])
            try {
                // processIncomingMms flushes the outbox itself on success.
                incomingMutex.withLock { processIncomingMms(id, rescan) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "deferred MMS retry failed id=$id", e)
            }
        }
    }

    private suspend fun flushOutbox() {
        outboxMutex.withLock {
            val client = relay ?: return@withLock
            if (!client.isConnected) return@withLock
            val rows = db.relayOutboxDao().pending(System.currentTimeMillis() - 30_000L)
            for (queuedRow in rows) {
                val content = RelayContentCodec.decode(queuedRow.plaintext)
                var row = queuedRow
                if (row.payload.isBlank() || row.cid.startsWith(SmsThread.LOCAL_CID_PREFIX)) {
                    val prepared = try {
                        prepareRelayOutbox(row)
                    } catch (e: Exception) {
                        Log.w(TAG, "Outgoing relay preparation deferred mid=${row.mid}", e)
                        null
                    }
                    if (prepared == null) {
                        val terminal = terminalOutboxRejection(row)
                        if (terminal != null) {
                            Log.w(TAG, "Outbox row can never be relayed mid=${row.mid}: $terminal")
                            db.relayOutboxDao().markUnsendable(row.id, terminal)
                        } else {
                            db.relayOutboxDao().recordAttempt(
                                row.id,
                                "relay preparation deferred",
                            )
                        }
                        continue
                    }
                    row = prepared
                }
                if (row.direction.startsWith("outgoing_") && row.carrierState == "unknown" &&
                    row.createdAt <= System.currentTimeMillis() - 30_000L
                ) {
                    // MainActivity normally performs this call immediately. If
                    // the process died between durable insert and the carrier
                    // API, retry it here so the outbox cannot become a relay-only
                    // phantom. A crash in that narrow window is inherently
                    // at-least-once at the carrier boundary.
                    val dispatched = if (content.type == RelayContentCodec.TYPE_MMS) {
                        MmsSender.send(this@SmsBridgeService, row.phoneNumber, content, row.mid, row.cid, 0)
                    } else {
                        SmsSender.send(this@SmsBridgeService, row.phoneNumber, content.text, row.mid, row.cid, 0)
                    }
                    if (!dispatched) {
                        db.relayOutboxDao().markCarrierState(row.id, "failed", "carrier dispatch rejected")
                        row.localMessageId?.let { localId ->
                            db.messageDao().setCarrierStatusById(
                                localId,
                                "failed",
                                "carrier dispatch rejected",
                            )
                        }
                        continue
                    }
                    db.relayOutboxDao().markCarrierDispatchedIfUnknown(row.id)
                    row = db.relayOutboxDao().getByMid(row.mid) ?: row.copy(
                        carrierState = "dispatched",
                    )
                    row.localMessageId?.let { localId ->
                        db.messageDao().advanceCarrierStatus(
                            localId,
                            row.carrierState,
                            row.lastError,
                        )
                    }
                }
                if (row.relayState == "sent") {
                    syncOutboxCarrierStatus(row, client)
                    continue
                }
                var ack = try {
                    client.sendMessageAwait(
                        row.cid,
                        JSONObject(row.payload),
                        messageId = row.mid,
                    )
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: "relay error")
                }
                if (!ack.optBoolean("ok") &&
                    ack.optString("error") == "payload keys do not match conversation devices"
                ) {
                    // A browser may have been added/revoked while this durable
                    // row was offline. Re-wrap the same plaintext for the current
                    // device set; the server explicitly rejected the old envelope.
                    val refreshed = try {
                        prepareRelayOutbox(row)
                    } catch (e: Exception) {
                        Log.w(TAG, "Recipient-key refresh failed mid=${row.mid}", e)
                        null
                    }
                    if (refreshed != null) {
                        row = refreshed
                        ack = try {
                            client.sendMessageAwait(
                                row.cid,
                                JSONObject(row.payload),
                                messageId = row.mid,
                            )
                        } catch (e: Exception) {
                            JSONObject().put("ok", false).put("error", e.message ?: "relay error")
                        }
                    }
                }
                if (!ack.optBoolean("ok")) {
                    db.relayOutboxDao().recordAttempt(row.id, ack.optString("error"))
                    Log.w(TAG, "Relay outbox retry mid=${row.mid}: ${ack.optString("error")}")
                    continue
                }
                val seq = ack.optInt("seq")
                if (seq <= 0) {
                    db.relayOutboxDao().recordAttempt(row.id, "invalid relay sequence")
                    continue
                }
                val isIncoming = row.direction.startsWith("incoming_")
                var mergedStaleCid: String? = null
                db.withTransaction {
                    if (row.localMessageId != null) {
                        val local = db.messageDao().getById(row.localMessageId)
                        if (local != null && local.cid != row.cid) {
                            // Recovery for an outbox that was already prepared
                            // before a process update/crash but whose rendered
                            // local row still belongs to the stale SMS thread.
                            // Merge the full local history first so serverKey is
                            // generated from the authoritative cid below.
                            db.messageDao().moveConversation(local.cid, row.cid)
                            db.relayOutboxDao().moveConversation(local.cid, row.cid)
                            db.threadDao().deleteByCid(local.cid)
                            mergedStaleCid = local.cid
                        }
                        // A socket echo can be synchronized before this ACK is
                        // handled. Preserve the locally rendered row and remove
                        // that acknowledged duplicate before assigning serverKey.
                        db.messageDao().deleteServerDuplicate(
                            "${row.cid}:$seq",
                            row.localMessageId,
                        )
                        db.messageDao().updateRelayResult(
                            row.localMessageId,
                            seq,
                            content.type,
                            content.subject,
                            RelayContentCodec.attachmentsJson(content),
                        )
                    } else {
                        db.messageDao().insert(
                            MessageRow(
                                cid = row.cid,
                                seq = seq,
                                senderSid = creds?.sid.orEmpty(),
                                plaintext = content.text,
                                createdAt = row.createdAt,
                                mine = !isIncoming,
                                contentType = content.type,
                                subject = content.subject,
                                attachmentsJson = RelayContentCodec.attachmentsJson(content),
                                serverKey = "${row.cid}:$seq",
                                carrierStatus = if (!isIncoming) row.carrierState else "none",
                                carrierError = row.lastError,
                            ),
                        )
                    }

                    db.threadDao().advanceLastSeq(row.cid, seq)
                    db.relayOutboxDao().markRelaySent(row.id, seq)
                    if (isIncoming) {
                        // Tombstone + optional provider ledger + deletion are
                        // one transaction, so a crash cannot forget a
                        // provider-less event after its relay ACK.
                        incomingRepository.acknowledgeIncoming(row)
                    }
                }
                // Outside the transaction like prepareRelayOutbox's rewrite: a
                // rollback must not strand the read stamp under a dead cid.
                mergedStaleCid?.let { LastOpened.move(this, it, row.cid) }

                client.emitDelivered(row.cid, seq)
                val current = if (isIncoming) null else db.relayOutboxDao().getByMid(row.mid)
                if (current != null && current.carrierState !in setOf("unknown", "not_applicable")) {
                    syncOutboxCarrierStatus(current, client)
                }
                Log.i(TAG, "Relay outbox delivered mid=${row.mid} seq=$seq")
            }
        }
    }

    /**
     * Why a queued row can never be prepared, however often it is retried.
     *
     * Only a deterministic rejection belongs here: an offline relay or a trust
     * refresh error must stay in the pending set. An SMS whose sender is an
     * alphanumeric id ("Google") or an email address has no carrier address to
     * relay to and never will, and pending() is a fixed oldest-first window —
     * a hundred such rows hide every newer one and stop the outbox entirely.
     */
    private fun terminalOutboxRejection(row: RelayOutbox): String? =
        "sender is not a carrier address".takeIf {
            !PhoneNumberNormalizer.isSmsAddress(PhoneNumberNormalizer.normalize(row.phoneNumber))
        }

    /** Resolve/create the server SMS conversation and encrypt queued carrier content. */
    private suspend fun prepareRelayOutbox(row: RelayOutbox): RelayOutbox? {
        val c = creds ?: Credentials.load(this) ?: return null
        val a = apiFor(c)
        val phone = PhoneNumberNormalizer.normalize(row.phoneNumber)
        if (!PhoneNumberNormalizer.isSmsAddress(phone)) return null

        // Always re-resolve through the server-owned membership list. A stale
        // local cache must never turn a group conversation into a carrier send.
        val resolvedThread = getOrCreateOwnedSmsThread(a, c, phone) ?: return null

        val oldCid = row.cid
        db.withTransaction {
            val oldThread = db.threadDao().get(oldCid)
            val currentTarget = db.threadDao().get(resolvedThread.cid)
            val mergedThread = resolvedThread.copy(
                serverName = resolvedThread.serverName
                    ?: currentTarget?.serverName
                    ?: oldThread?.serverName,
                localContactName = currentTarget?.localContactName
                    ?: oldThread?.localContactName,
                lastSeq = maxOf(
                    resolvedThread.lastSeq,
                    currentTarget?.lastSeq ?: 0,
                    oldThread?.lastSeq ?: 0,
                ),
                lastActivityAt = maxOf(
                    resolvedThread.lastActivityAt,
                    currentTarget?.lastActivityAt ?: 0L,
                    oldThread?.lastActivityAt ?: 0L,
                    row.createdAt,
                ),
            )
            db.threadDao().upsert(mergedThread)
            if (oldCid != resolvedThread.cid) {
                // The relay may have discarded/recreated a self-only SMS
                // conversation while this device still has its former real
                // cid cached. Merge both provisional and stale acknowledged
                // history so a 010 reply does not open a second +82 thread and
                // ACK promotion uses the authoritative cid/serverKey.
                db.messageDao().moveConversation(oldCid, resolvedThread.cid)
                db.relayOutboxDao().moveConversation(oldCid, resolvedThread.cid)
                db.threadDao().deleteByCid(oldCid)
            }
        }
        // After the transaction, so a rollback cannot orphan the read stamp
        // under a cid no thread carries anymore.
        if (oldCid != resolvedThread.cid) {
            LastOpened.move(this, oldCid, resolvedThread.cid)
        }

        val members = a.convMembers(resolvedThread.cid)
        if (!members.optBoolean("ok")) return null
        if (!validateTrustedRecipients(a, c, members)) return null
        val recipients = pinMembers(members.optJSONArray("members") ?: JSONArray(), "send")
            ?: return null
        if (recipients.isEmpty()) return null
        val payload = CryptoUtil.envelopeToJson(
            CryptoUtil.encryptMessage(row.plaintext, recipients, c.keypair),
        )
        db.relayOutboxDao().markPrepared(row.id, resolvedThread.cid, payload.toString())
        return db.relayOutboxDao().getByMid(row.mid)
    }

    /**
     * TOFU-pin every device a conversation's member list names.
     *
     * One implementation for the send and receive paths, which held verbatim
     * copies of this loop — same fields, same skip rule, same rejection — so
     * neither copy was the stricter one and the merge changes no decision.
     * Pinning is the app's only defence against a relay that swaps a recipient
     * key, and the copy that drifts is the one that stops defending.
     *
     * Null means a listed sid's key no longer matches its pin: the caller must
     * abandon the whole message, never proceed with the members that did pin.
     */
    private suspend fun pinMembers(
        membersArr: JSONArray,
        logContext: String,
    ): List<CryptoUtil.Recipient>? {
        val recipients = mutableListOf<CryptoUtil.Recipient>()
        for (index in 0 until membersArr.length()) {
            val member = membersArr.optJSONObject(index) ?: continue
            val sid = member.optString("sid")
            val pubKey = member.optString("pub_key")
            if (sid.isBlank() || pubKey.isBlank()) continue
            val pinned = db.deviceCacheDao().pinOrReject(
                DeviceCache(
                    sid = sid,
                    userId = member.optInt("user_id"),
                    name = member.optString("name"),
                    pubKey = pubKey,
                ),
            )
            if (!pinned) {
                Log.e(TAG, "Blocked $logContext: public key changed for pinned sid=$sid")
                return null
            }
            recipients += CryptoUtil.Recipient(sid, pubKey)
        }
        return recipients
    }

    private suspend fun handleCarrierStatus(intent: Intent) {
        val mid = intent.getStringExtra(CarrierStatusReceiver.EXTRA_MID).orEmpty()
        val cid = intent.getStringExtra(CarrierStatusReceiver.EXTRA_CID).orEmpty()
        val seq = intent.getIntExtra(CarrierStatusReceiver.EXTRA_SEQ, 0)
        val status = intent.getStringExtra(CarrierStatusReceiver.EXTRA_STATUS).orEmpty()
        val error = intent.getStringExtra(CarrierStatusReceiver.EXTRA_ERROR)
        // Offline, the durable retries in flushOutbox/flushReceiptStatuses are
        // what report this later; nothing here is worth doing without a socket.
        val client = relay?.takeIf { it.isConnected } ?: return
        val row = db.relayOutboxDao().getByMid(mid)
        if (row != null) {
            syncOutboxCarrierStatus(row, client)
            return
        }
        if (cid.isNotBlank() && seq > 0 && status.isNotBlank()) {
            val ack = client.emitCarrierStatusAwait(cid, seq, status, error)
            if (ack.optBoolean("ok")) {
                db.relayReceiptDao().markStatusSynced(cid, seq, status)
            } else {
                Log.w(TAG, "Carrier status relay deferred for $cid/$seq")
            }
        }
    }

    private suspend fun syncOutboxCarrierStatus(row: RelayOutbox, client: RelayClient) {
        val seq = row.serverSeq ?: return
        if (!row.carrierStatusPending || seq <= 0 || row.carrierState in setOf("unknown", "not_applicable")) {
            return
        }
        val ack = client.emitCarrierStatusAwait(
            row.cid,
            seq,
            row.carrierState,
            row.lastError,
        )
        if (ack.optBoolean("ok")) {
            db.relayOutboxDao().markCarrierStatusSynced(row.id, row.carrierState)
        } else {
            Log.w(TAG, "Carrier status ACK pending mid=${row.mid}: ${ack.optString("error")}")
        }
    }

    /** Relay message from web/another device -> carrier SMS. */
    private fun handleRelayMessage(env: JSONObject) {
        val cid = env.optString("cid")
        if (cid.isBlank()) return
        scope.launch {
            syncMutex.withLock {
                try {
                    val c = creds ?: Credentials.load(this@SmsBridgeService) ?: return@withLock
                    val a = apiFor(c)
                    syncConversation(cid, a, c)
                } catch (e: Exception) {
                    Log.e(TAG, "Relay event sync failed for cid=$cid", e)
                }
            }
        }
    }

    /** Pull one SMS conversation in sequence so a failed row cannot be skipped. */
    private suspend fun syncConversation(
        cid: String,
        a: RelayApi,
        c: SavedCredentials,
        ownershipAlreadyVerified: Boolean = false,
    ) {
        val thread = if (ownershipAlreadyVerified) {
            db.threadDao().get(cid)
        } else {
            resolveThreadFromServer(cid, a, c.username)
        } ?: run {
            Log.w(TAG, "No SMS thread for cid=$cid — ignoring")
            return
        }
        var cursor = thread.lastSeq
        while (true) {
            val response = a.fetchMessages(cid, cursor)
            if (!response.optBoolean("ok")) {
                Log.e(TAG, "History fetch failed: ${response.optString("error")}")
                return
            }
            // The response carries the conversation id; its rows do not.
            if (response.optString("cid").let { it.isNotEmpty() && it != cid }) {
                Log.e(TAG, "History page belongs to another conversation than $cid; retrying")
                return
            }
            val rows = response.optJSONArray("messages") ?: return
            if (rows.length() == 0) return
            var consumed = 0
            for (i in 0 until rows.length()) {
                val message = rows.optJSONObject(i) ?: run {
                    Log.e(TAG, "Malformed relay history row at index=$i; retrying batch")
                    return
                }
                val seq = message.optInt("seq", -1)
                when (
                    RelaySyncPolicy.rowAction(
                        expectedCid = cid,
                        rowCid = message.optString("cid"),
                        seq = seq,
                        cursor = cursor,
                        senderSid = message.optString("sender_sid"),
                        payloadIsObject = message.optJSONObject("payload") != null,
                    )
                ) {
                    RelaySyncPolicy.RowAction.RETRY_BATCH -> {
                        Log.e(TAG, "Invalid relay history row at index=$i seq=$seq; retrying batch")
                        return
                    }
                    RelaySyncPolicy.RowAction.SKIP_ALREADY_CONSUMED -> continue
                    RelaySyncPolicy.RowAction.PROCESS -> Unit
                }
                if (!processRelayEnvelope(message, thread, a, c)) return
                cursor = seq
                consumed += 1
            }
            if (rows.length() < 500 || consumed == 0) return
        }
    }

    /** Returns true when the sequence can be advanced, false when it must retry. */
    private suspend fun processRelayEnvelope(
        env: JSONObject,
        thread: SmsThread,
        a: RelayApi,
        c: SavedCredentials,
    ): Boolean {
        val cid = env.optString("cid")
        val senderSid = env.optString("sender_sid")
        val seq = env.optInt("seq", -1)
        if (senderSid == c.sid) {
            val hasLocalServerKey = db.messageDao().hasServerKey("$cid:$seq")
            val hasAcknowledgedOutbox = db.relayOutboxDao().hasAcknowledgedSequence(cid, seq)
            if (!RelaySyncPolicy.canConsumeSelfEcho(hasLocalServerKey, hasAcknowledgedOutbox)) {
                Log.w(TAG, "Unacknowledged self echo for $cid/$seq; retrying batch")
                return false
            }
            val status = env.optString("carrier_status", "none")
            val local = db.messageDao().getByServerKey("$cid:$seq")
            // The history page is fetched from a cursor captured before
            // flushOutbox ACKed this row, so it can carry a carrier status
            // older than the SENT/DELIVERED callback already applied locally,
            // and nothing repairs a regression afterwards.
            if (status.isNotBlank() && status != "none" && local != null &&
                CarrierState.canAdvance(local.carrierStatus, status)
            ) {
                db.messageDao().setCarrierStatus(
                    cid,
                    seq,
                    status,
                    // optString over a JSON null returns "null" on the
                    // device's org.json, and carrier_error is null for every
                    // successfully sent row.
                    env.takeUnless { it.isNull("carrier_error") }
                        ?.optString("carrier_error")?.takeIf { it.isNotBlank() },
                    env.optLong("carrier_updated_at").takeIf { it > 0 }?.times(1000)
                        ?: System.currentTimeMillis(),
                )
            }
            db.threadDao().advanceLastSeq(cid, seq)
            relay?.emitDelivered(cid, seq)
            return true
        }

        var senderPubKey = env.optString("sender_pub_key").takeIf { it.isNotBlank() }
        var senderDev = if (senderPubKey == null) db.deviceCacheDao().get(senderSid) else null
        var memberLookupSucceeded = false
        if (senderPubKey == null && senderDev == null) {
            val membersResp = a.convMembers(cid)
            if (membersResp.optBoolean("ok")) {
                memberLookupSucceeded = true
                val membersArr = membersResp.optJSONArray("members") ?: JSONArray()
                // The pinned list itself is unused here; only the rejection matters.
                if (pinMembers(membersArr, "receive") == null) return false
                senderDev = db.deviceCacheDao().get(senderSid)
            }
        }
        if (senderPubKey == null) senderPubKey = senderDev?.pubKey
        var trustedSender = db.deviceTrustDao().getPin(senderSid)
        if (trustedSender == null && refreshDeviceTrust("unpinned sender")) {
            // Nothing else re-reads the key directory on the receive path:
            // startBridge skips it while the socket is up and the send-side
            // refresh only runs when this phone has something to send. A web
            // device approved from another device would otherwise be refused
            // on every message, holding the whole conversation behind it.
            trustedSender = db.deviceTrustDao().getPin(senderSid)
        }
        if (senderPubKey != null && (trustedSender == null || trustedSender.pubKey != senderPubKey)) {
            Log.e(TAG, "Blocked envelope: sender is missing or differs from trusted sid=$senderSid")
            return false
        }
        if (senderPubKey == null) {
            Log.w(
                TAG,
                "Cannot resolve trusted sender pubkey for $senderSid " +
                    "(memberLookupSucceeded=$memberLookupSucceeded); retrying seq=$seq",
            )
            return false
        }

        val carrierStatus = env.optString("carrier_status", "none")
        if (!RelaySyncPolicy.isCarrierSendRequest(trustedSender?.kind, carrierStatus)) {
            // History, not a send request. It is not rendered locally either:
            // the envelope does not say which direction a gateway uploaded it
            // in, and guessing would show an incoming SMS as one this account
            // sent.
            Log.i(
                TAG,
                "Consuming history row $cid/$seq without carrier dispatch " +
                    "(senderKind=${trustedSender?.kind}, carrierStatus=$carrierStatus)",
            )
            db.threadDao().advanceLastSeq(cid, seq)
            relay?.emitDelivered(cid, seq)
            return true
        }

        val plaintext = try {
            val payload = CryptoUtil.envelopeFromJson(env.getJSONObject("payload"))
            // A key re-wrapped by another device of this account (history
            // sharing) was sealed by that device, so it opens with that
            // device's key. Resolve it here — a suspend DAO read cannot happen
            // inside the resolver — and only from the pinned trust store: a
            // pub_key echoed by the relay would let a hostile server name
            // itself as the wrapper and hand over a key it controls.
            val wrapperSid = payload.keys[c.sid]?.by
            val wrapperPubKey = wrapperSid?.let { db.deviceTrustDao().getPin(it)?.pubKey }
            CryptoUtil.decryptMessage(payload, c.sid, c.keypair, senderPubKey) { sid ->
                wrapperPubKey.takeIf { sid == wrapperSid }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Malformed envelope for seq=$seq", e)
            null
        }
        if (plaintext == null) {
            Log.w(TAG, "Message is not decryptable by this device; retrying seq=$seq")
            return false
        }

        val content = try {
            RelayContentCodec.decode(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "Relay content decode failed for seq=$seq", e)
            return false
        }

        // Claim before the irreversible carrier side effect. Repeated socket
        // events, reconnect pulls, and concurrent syncs cannot send twice.
        val claim = db.relayReceiptDao().claim(RelayReceipt(cid, seq))
        if (claim == -1L) {
            val receipt = db.relayReceiptDao().get(cid, seq) ?: return false
            val cutoff = System.currentTimeMillis() - CLAIM_RETRY_GRACE_MS
            when (RelayReceiptRetryPolicy.action(receipt.status, receipt.claimedAt <= cutoff)) {
                RelayReceiptRetryPolicy.Action.WAIT_FOR_ACTIVE_CLAIM -> return false
                RelayReceiptRetryPolicy.Action.RETRY_STALE_CLAIM -> {
                    val reclaimed = db.relayReceiptDao().reclaimStale(cid, seq, cutoff)
                    if (reclaimed == 0) return false
                    Log.w(TAG, "Retrying stale pre-dispatch carrier claim for $cid/$seq")
                }
                RelayReceiptRetryPolicy.Action.REQUIRE_EXPLICIT_RETRY -> {
                    // Do not move the sequence cursor: this row may already be
                    // on the carrier network, and automatic redispatch could
                    // duplicate it. A later framework callback can still move
                    // the receipt to sent/failed and unblock normal recovery.
                    Log.e(TAG, "Ambiguous carrier dispatch for $cid/$seq; explicit retry required")
                    return false
                }
                RelayReceiptRetryPolicy.Action.CONSUME_RESOLVED -> {
                    syncReceiptStatus(cid, seq, receipt.status, receipt.lastError)
                    db.threadDao().advanceLastSeq(cid, seq)
                    relay?.emitDelivered(cid, seq)
                    return true
                }
            }
        }

        val rejection = predispatchRejection(content)
        if (rejection != null) {
            // The carrier API is never reached for these, so no callback can
            // ever resolve the receipt. Left 'attempting' — deliberately not
            // retryable — it would hold this conversation's cursor forever,
            // and every later message in it with the cursor.
            Log.e(TAG, "Carrier refused $cid/$seq before dispatch: $rejection")
            db.relayReceiptDao().markStatus(cid, seq, "failed", rejection)
            syncReceiptStatus(cid, seq, "failed", rejection)
            db.threadDao().advanceLastSeq(cid, seq)
            relay?.emitDelivered(cid, seq)
            return true
        }

        // Persist the ambiguous boundary immediately before entering the
        // irreversible Android carrier API. A crash after this write must
        // never turn into an automatic second send.
        db.relayReceiptDao().markStatus(
            cid,
            seq,
            "attempting",
            "Carrier dispatch outcome pending callback; explicit retry required if unresolved",
        )
        val dispatchId = "relay-${cid.take(32)}-$seq"
        val dispatched = if (content.type == RelayContentCodec.TYPE_MMS) {
            MmsSender.send(this@SmsBridgeService, thread.phoneNumber, content, dispatchId, cid, seq)
        } else {
            SmsSender.send(this@SmsBridgeService, thread.phoneNumber, content.text, dispatchId, cid, seq)
        }
        if (!dispatched) {
            // SmsManager/MmsManager may throw after accepting work. Keep the
            // attempting receipt so a reconnect cannot duplicate the message.
            return false
        }

        val afterDispatch = db.relayReceiptDao().get(cid, seq) ?: return false
        if (CarrierState.canAdvance(afterDispatch.status, "dispatched")) {
            db.relayReceiptDao().markStatus(cid, seq, "dispatched", null)
        }
        val effectiveReceipt = db.relayReceiptDao().get(cid, seq) ?: return false
        syncReceiptStatus(cid, seq, effectiveReceipt.status, effectiveReceipt.lastError)

        try {
            val serverTime = env.optLong("created_at")
                .takeIf { it > 0 }?.times(1000) ?: System.currentTimeMillis()
            db.messageDao().insert(
                MessageRow(
                    cid = cid,
                    seq = seq,
                    senderSid = senderSid,
                    plaintext = content.text,
                    createdAt = serverTime,
                    mine = true,
                    blocked = false,
                    contentType = content.type,
                    subject = content.subject,
                    attachmentsJson = RelayContentCodec.attachmentsJson(content),
                    serverKey = "$cid:$seq",
                    // A very fast framework callback can resolve the receipt
                    // before send() returns. Preserve that newer result.
                    carrierStatus = effectiveReceipt.status,
                    carrierError = effectiveReceipt.lastError,
                ),
            )
        } catch (e: Exception) {
            // The SMS has already left the device. Keep the idempotency receipt
            // even if local presentation storage fails.
            Log.e(TAG, "SMS sent but local message insert failed", e)
        }
        db.threadDao().advanceLastSeq(cid, seq)
        relay?.emitDelivered(cid, seq)
        Log.i(
            TAG,
            "${content.type.uppercase()} dispatched to " +
                PhoneNumberNormalizer.redact(thread.phoneNumber),
        )
        return true
    }

    /**
     * Why the carrier API will refuse this content before it is ever called.
     *
     * SmsSender reports such a rejection as a plain false, indistinguishable
     * from a throw after SmsManager already accepted the message — and that
     * ambiguity is exactly what makes an 'attempting' receipt non-retryable.
     * Naming the deterministic cases here keeps one over-long message (the web
     * composer accepts 20_000 characters, the carrier 20 segments) from
     * freezing the SMS thread it was sent to.
     */
    private fun predispatchRejection(content: RelayContent): String? {
        if (content.type == RelayContentCodec.TYPE_MMS) return null
        if (content.text.isBlank()) return "SMS body is empty"
        val segments = runCatching {
            getSystemService(SmsManager::class.java).divideMessage(content.text).size
        }.getOrNull() ?: return null
        return "SMS exceeds ${SmsSender.MAX_MULTIPART_SEGMENTS} carrier segments"
            .takeIf { segments > SmsSender.MAX_MULTIPART_SEGMENTS }
    }

    /** Report one receipt's carrier outcome; [flushReceiptStatuses] retries the rest. */
    private suspend fun syncReceiptStatus(cid: String, seq: Int, status: String, error: String?) {
        val client = relay ?: return
        if (!client.isConnected) return
        val ack = client.emitCarrierStatusAwait(cid, seq, status, error)
        if (ack.optBoolean("ok")) {
            db.relayReceiptDao().markStatusSynced(cid, seq, status)
        }
    }

    /**
     * Re-read and re-verify the key directory outside the send path.
     *
     * Rate-limited because the caller is a per-message miss: a conversation
     * full of envelopes from one unknown device must not become a GET
     * /key-directory per envelope. A device approval event forces it, being
     * both rare and the authoritative signal.
     */
    private suspend fun refreshDeviceTrust(reason: String, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (force) {
            lastTrustRefreshAt.set(now)
        } else {
            val previous = lastTrustRefreshAt.get()
            if (now - previous < TRUST_REFRESH_MIN_INTERVAL_MS) return false
            if (!lastTrustRefreshAt.compareAndSet(previous, now)) return false
        }
        val c = creds ?: Credentials.load(this) ?: return false
        val relayApi = apiFor(c)
        val view = DeviceSecurityController(
            RelayTrustedDeviceApi(relayApi),
            c,
            DeviceTrustRepository(db),
        ).refresh()
        if (view.blocksDirectoryUse) {
            Log.w(TAG, "Directory refresh after $reason rejected: $view")
            return false
        }
        return true
    }

    /** Fail-closed validation for the self-only SMS relay recipient directory. */
    private suspend fun validateTrustedRecipients(
        relayApi: RelayApi,
        credentials: SavedCredentials,
        response: JSONObject,
    ): Boolean {
        val refreshed = DeviceSecurityController(
            RelayTrustedDeviceApi(relayApi),
            credentials,
            DeviceTrustRepository(db),
        ).refresh()
        if (refreshed.blocksDirectoryUse) {
            Log.e(TAG, "Recipient directory refresh rejected: $refreshed")
            return false
        }
        val state = db.deviceTrustDao().getState(credentials.uid.toLong()) ?: return false
        val checkpoints = response.optJSONArray("directory_checkpoints") ?: return false
        val ownCheckpoint = (0 until checkpoints.length()).mapNotNull { checkpoints.optJSONObject(it) }
            .firstOrNull { it.optLong("user_id", -1) == credentials.uid.toLong() }
            ?: return false
        if (ownCheckpoint.optString("identity_sig_pub") != state.identityKey ||
            ownCheckpoint.optLong("security_epoch", -1) != state.epoch ||
            ownCheckpoint.optString("directory_hash") != state.directoryHash
        ) {
            Log.e(TAG, "Conversation directory checkpoint differs from locally verified state")
            return false
        }
        val members = response.optJSONArray("members") ?: return false
        val keys = mutableListOf<TrustedRecipientKey>()
        for (i in 0 until members.length()) {
            val member = members.optJSONObject(i) ?: return false
            val userId = member.optLong("user_id", -1)
            val sid = member.optString("sid")
            val pubKey = member.optString("pub_key")
            val sigPub = member.optString("sig_pub")
            val kind = member.optString("kind")
            // Android SMS conversations are owned by exactly one relay account. Peer
            // account directories need a separate verified identity exchange protocol.
            if (userId != credentials.uid.toLong()) return false
            val pin = db.deviceTrustDao().getPin(sid) ?: return false
            if (pin.pubKey != pubKey || pin.sigPub != sigPub || pin.kind != kind) return false
            keys += TrustedRecipientKey(userId, sid, pubKey, sigPub)
        }
        val expected = runCatching { DeviceTrustCrypto.recipientKeysetHash(keys) }.getOrNull()
            ?: return false
        return expected == response.optString("recipient_keyset_hash")
    }

    /**
     * Apply one server conversation row to the local thread.
     *
     * The copy() branch is what keeps lastSeq/lastActivityAt and the
     * address-book name this device resolved: rebuilding the row from the
     * server fields alone would reset a conversation's cursor on every sync.
     */
    private suspend fun upsertThreadFromRow(cid: String, phone: String, row: JSONObject): SmsThread {
        val existing = db.threadDao().get(cid)
        return (
            existing?.copy(
                phoneNumber = phone,
                serverName = serverConversationName(row),
                syncedContactName = nullableContactName(row, "synced_contact_name"),
            ) ?: SmsThread(
                cid = cid,
                phoneNumber = phone,
                serverName = serverConversationName(row),
                syncedContactName = nullableContactName(row, "synced_contact_name"),
            )
            ).also { db.threadDao().upsert(it) }
    }

    private suspend fun resolveThreadFromServer(
        cid: String,
        a: RelayApi,
        username: String,
    ): SmsThread? {
        val response = a.listConversations()
        if (!response.optBoolean("ok")) return null
        val rows = response.optJSONArray("conversations") ?: return null
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("cid") != cid) continue
            val phone = ownedPhone(row, username) ?: return null
            return upsertThreadFromRow(cid, phone, row)
        }
        return null
    }

    private suspend fun getOrCreateOwnedSmsThread(
        a: RelayApi,
        c: SavedCredentials,
        phone: String,
    ): SmsThread? {
        val listed = a.listConversations()
        if (!listed.optBoolean("ok")) {
            Log.e(TAG, "Conversation lookup failed: ${listed.optString("error")}")
            return null
        }
        val rows = listed.optJSONArray("conversations") ?: JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (ownedPhone(row, c.username) != phone) continue
            val cid = row.optString("cid")
            if (cid.isBlank()) continue
            return upsertThreadFromRow(cid, phone, row)
        }

        val created = a.createConversation(JSONArray().put(c.username), phone)
        if (!created.optBoolean("ok")) {
            Log.e(TAG, "createConversation failed: ${created.optString("error")}")
            return null
        }
        val cid = created.optString("cid")
        if (cid.isBlank()) return null
        return SmsThread(cid, phone, phone).also { db.threadDao().upsert(it) }
    }

    /**
     * The number this relay conversation is the carrier gateway for, or null.
     *
     * Ownership normally comes from the conversation name, but any member may
     * rename any conversation and the web offers rename with no SMS guard. A
     * rename to "Mom" used to drop the cid from the owned set: web messages in
     * it were discarded, and the number's next incoming SMS created a second
     * relay conversation, splitting the history while the web kept sending
     * into the dead one. A cid this device already pinned to a number stays
     * that number's thread. The self-only membership test is what actually
     * gates carrier authority and is still required on both paths — a group
     * conversation named like a phone number must never reach the carrier.
     */
    private suspend fun ownedPhone(row: JSONObject, username: String): String? {
        val memberRows = row.optJSONArray("members") ?: return null
        val members = buildList {
            for (index in 0 until memberRows.length()) {
                val member = memberRows.optString(index)
                if (member.isNotBlank()) add(member)
            }
        }
        SmsConversationPolicy.ownedPhone(row.optString("name"), members, username)
            ?.let { return it }
        if (members.size != 1 || members.single() != username) return null
        val cid = row.optString("cid").takeIf { it.isNotBlank() } ?: return null
        return db.threadDao().get(cid)?.phoneNumber?.takeIf(PhoneNumberNormalizer::isSmsAddress)
    }

    private fun serverConversationName(row: JSONObject): String? =
        row.optString("name").trim().takeIf { it.isNotEmpty() }

    private fun nullableContactName(row: JSONObject, key: String): String? {
        if (!row.has(key) || row.isNull(key)) return null
        return row.optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private suspend fun syncFromServer() {
        syncMutex.withLock {
            try {
                val c = creds ?: Credentials.load(this) ?: return@withLock
                val a = apiFor(c)
                val ownedCids = syncSmsThreads()
                for (cid in ownedCids) {
                    syncConversation(cid, a, c, ownershipAlreadyVerified = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Offline relay sync failed", e)
            }
        }
    }

    private suspend fun flushReceiptStatuses() {
        val client = relay ?: return
        if (!client.isConnected) return
        for (receipt in db.relayReceiptDao().pendingStatuses()) {
            val ack = client.emitCarrierStatusAwait(
                receipt.cid,
                receipt.seq,
                receipt.status,
                receipt.lastError,
            )
            if (!ack.optBoolean("ok")) {
                val error = ack.optString("error")
                if (RelayReceiptRetryPolicy.isRetryableAckError(error)) return
                // Retiring it costs this one status. Waiting costs every
                // status after it, in every conversation, forever: the queue
                // is oldest-first and the relay's refusal will not change.
                Log.w(
                    TAG,
                    "Carrier status permanently rejected for " +
                        "${receipt.cid}/${receipt.seq}: $error",
                )
            }
            db.relayReceiptDao().markStatusSynced(
                receipt.cid,
                receipt.seq,
                receipt.status,
            )
        }
    }

    private suspend fun syncSmsThreads(): Set<String> {
        val c = creds ?: return emptySet()
        val a = apiFor(c)
        val response = a.listConversations()
        if (!response.optBoolean("ok")) {
            Log.e(TAG, "Conversation sync failed: ${response.optString("error")}")
            return emptySet()
        }
        val rows = response.optJSONArray("conversations") ?: return emptySet()
        val ownedCids = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val cid = row.optString("cid")
            val phone = ownedPhone(row, c.username)
            if (cid.isNotBlank() && phone != null) {
                val absorbed = db.withTransaction {
                    upsertThreadFromRow(cid, phone, row)
                    // A provisional thread for this number that no outbox row
                    // will ever merge — HistoryRestore builds one, because it
                    // uploads nothing. Absorbing it here is the only path that
                    // reunites rebuilt history with the relay conversation the
                    // number's new traffic lands in.
                    db.threadDao().provisionalByPhone(phone, cid).onEach { stale ->
                        db.messageDao().moveConversation(stale.cid, cid)
                        db.relayOutboxDao().moveConversation(stale.cid, cid)
                        db.threadDao().touch(cid, stale.lastActivityAt)
                        db.threadDao().deleteByCid(stale.cid)
                    }
                }
                // Outside the transaction, like every other cid rewrite: a
                // rollback must not strand the read stamp under a dead cid.
                absorbed.forEach { LastOpened.move(this, it.cid, cid) }
                ownedCids += cid
            }
        }
        return ownedCids
    }

    private suspend fun importRecentInbox() {
        for (sms in SmsProvider.recentInbox(this)) {
            val content = RelayContentCodec.text(sms.body)
            val identity = ProviderIdentityResolver.resolve(
                db,
                ProviderIdentity.SMS,
                sms.id,
                sms.address,
                sms.date,
                RelayContentCodec.encode(content),
            )
            if (!db.processedSmsDao().contains(identity.epoch, sms.id)) {
                handleIncomingSms(
                    sms.address, sms.body, sms.id, sms.date, identity.epoch,
                    rescan = true,
                )
            }
        }
    }

    private fun startForeground() {
        val notif = BridgeNotifications.build(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                BridgeNotifications.NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(BridgeNotifications.NOTIF_ID, notif)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        relay?.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * The fail-closed answer to "may this device use the key directory at all?".
 *
 * startBridge, the per-message trust refresh and the recipient validation each
 * spelled these four fields out, so a fifth blocking field would have had to be
 * added to three predicates at once — and the one that was missed would keep
 * relaying against an unverified directory. Private to this file because
 * DeviceTrust.kt is where it belongs once HistoryShare's copy can move too.
 */
private val DeviceSecurityView.blocksDirectoryUse: Boolean
    get() = serverUnsupported || selfPending || error != null || trustWarning != null

internal object MmsRowProcessor {
    suspend fun process(
        ids: Iterable<Long>,
        processRow: suspend (Long) -> Unit,
        onFailure: (Long, Exception) -> Unit,
    ) {
        for (id in ids) {
            try {
                processRow(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(id, error)
            }
        }
    }
}
