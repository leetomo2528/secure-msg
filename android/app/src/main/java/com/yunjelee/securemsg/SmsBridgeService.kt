package com.yunjelee.securemsg

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

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
    private var outboxLoop: Job? = null
    private val outboxLoopStarted = AtomicBoolean(false)
    private val sessionInvalidated = AtomicBoolean(false)

    companion object {
        const val ACTION_INCOMING_SMS = "com.yunjelee.securemsg.INCOMING_SMS"
        const val ACTION_INCOMING_MMS = "com.yunjelee.securemsg.INCOMING_MMS"
        const val ACTION_START_BRIDGE = "com.yunjelee.securemsg.START_BRIDGE"
        const val ACTION_CARRIER_STATUS = "com.yunjelee.securemsg.CARRIER_STATUS"
        const val ACTION_SEND_LOCAL_SMS = "com.yunjelee.securemsg.SEND_LOCAL_SMS"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_BODY = "body"
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_RECEIVED_AT = "received_at"
        private const val TAG = "SmsBridgeService"
        private const val CLAIM_RETRY_GRACE_MS = 30_000L
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)
        incomingRepository = IncomingMessageRepository(db)
        BridgeNotifications.createChannel(this)
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

        when (intent?.action) {
            ACTION_INCOMING_SMS -> {
                val phone = intent.getStringExtra(EXTRA_PHONE) ?: return START_STICKY
                val body = intent.getStringExtra(EXTRA_BODY) ?: return START_STICKY
                val providerId = intent.getLongExtra(EXTRA_PROVIDER_ID, -1L).takeIf { it > 0 }
                val receivedAt = intent.getLongExtra(
                    EXTRA_RECEIVED_AT,
                    System.currentTimeMillis(),
                )
                scope.launch {
                    try {
                        incomingMutex.withLock {
                            handleIncomingSms(phone, body, providerId, receivedAt)
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
                            if (id != null) processIncomingMms(id) else processRecentMms()
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
                            processRecentMms()
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
        outboxLoop = scope.launch {
            while (true) {
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
        val trustView = DeviceSecurityController(
            RelayTrustedDeviceApi(relayApi, loaded.uid.toLong()),
            loaded,
            DeviceTrustRepository(db),
        ).refresh()
        if (trustView.serverUnsupported || trustView.selfPending || trustView.error != null ||
            trustView.trustWarning != null
        ) {
            Log.e(TAG, "Bridge blocked by device trust: $trustView")
            stopSelf()
            return
        }
        val authCheck = try {
            relayApi.listConversations()
        } catch (_: Exception) {
            null
        }
        if (authCheck?.optInt("_http_status") == 401) {
            invalidateSession("REST authentication rejected")
            return
        }
        relay?.disconnect()
        relay = RelayClient(serverUrl).also { client ->
            client.onConnect = {
                Log.i(TAG, "Relay connected")
                startOutboxLoop()
                scope.launch {
                    try {
                        BlocklistSync.sync(this@SmsBridgeService, relayApi)
                        syncFromServer()
                        incomingMutex.withLock {
                            importRecentInbox()
                            processRecentMms()
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
                if (message.contains("invalid token", ignoreCase = true) ||
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
        }
        relay!!.connect(loaded.token)
        Log.i(TAG, "Bridge started for ${loaded.username}")
    }

    private fun invalidateSession(reason: String) {
        if (!sessionInvalidated.compareAndSet(false, true)) return
        Log.w(TAG, "Clearing rejected relay session: $reason")
        scope.launch {
            Credentials.clearSession(this@SmsBridgeService)
            relay?.disconnect()
            stopSelf()
        }
    }

    private fun getServerUrl(): String {
        return ServerConfig.url(this)
    }

    /** Carrier SMS -> encrypted multi-device relay. */
    private suspend fun handleIncomingSms(
        phone: String,
        body: String,
        providerId: Long? = null,
        receivedAt: Long = System.currentTimeMillis(),
    ) {
        if (phone.isBlank() || body.isBlank()) {
            // A malformed provider row can never be relayed; mark it processed
            // so startup imports stop retrying it forever.
            providerId?.let { db.processedSmsDao().insert(ProcessedSms(it)) }
            return
        }
        if (providerId != null && db.processedSmsDao().contains(providerId)) return
        if (providerId != null && db.relayOutboxDao()
                .getByProviderId(providerId, "incoming_sms") != null
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
            providerId?.let { db.processedSmsDao().insert(ProcessedSms(it)) }
            Log.i(TAG, "SMS not relayed: ${decision.reason}")
            return
        }

        val content = RelayContentCodec.text(body)
        incomingRepository.persist(
            direction = "incoming_sms",
            phoneNumber = phone,
            content = content,
            providerId = providerId,
            receivedAt = receivedAt,
        )
        flushOutbox()
    }

    private suspend fun processRecentMms() {
        for (id in MmsProvider.recentInbox(this)) {
            if (!db.processedMmsDao().contains(id)) processIncomingMms(id)
        }
    }

    private suspend fun processIncomingMms(id: Long) {
        if (db.processedMmsDao().contains(id)) return
        if (db.relayOutboxDao().getByProviderId(id, "incoming_mms") != null) {
            flushOutbox()
            return
        }
        val mms = MmsProvider.read(this, id)
        if (mms == null) {
            // Read failure (deleted row/provider error) is permanent for this id;
            // mark it processed so startup imports stop retrying it forever.
            db.processedMmsDao().insert(ProcessedMms(id))
            return
        }
        val phone = PhoneNumberNormalizer.normalize(mms.address)
        if (!isSmsAddress(phone)) {
            Log.w(TAG, "Ignoring MMS with invalid sender id=$id")
            db.processedMmsDao().insert(ProcessedMms(id))
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
            db.processedMmsDao().insert(ProcessedMms(id))
            MmsProvider.delete(this, id)
            Log.i(TAG, "MMS quarantined id=$id: ${decision.reason}")
            return
        }
        val attachments = mms.parts.map {
            RelayAttachment(
                name = it.name,
                contentType = it.contentType,
                data = RelayContentCodec.encodeBytes(it.bytes),
                size = it.bytes.size,
            )
        }
        val content = RelayContent(
            type = RelayContentCodec.TYPE_MMS,
            text = mms.body,
            subject = mms.subject,
            attachments = attachments,
        )
        incomingRepository.persist(
            direction = "incoming_mms",
            phoneNumber = phone,
            content = content,
            providerId = id,
            receivedAt = mms.date,
        )
        flushOutbox()
    }

    private suspend fun flushOutbox() {
        outboxMutex.withLock {
            val client = relay ?: return@withLock
            if (!client.isConnected) return@withLock
            val rows = db.relayOutboxDao().pending(System.currentTimeMillis() - 30_000L)
            for (queuedRow in rows) {
                val content = RelayContentCodec.decode(queuedRow.plaintext)
                var row = queuedRow
                if (row.payload.isBlank() || row.cid.startsWith("local_")) {
                    val prepared = try {
                        prepareRelayOutbox(row)
                    } catch (e: Exception) {
                        Log.w(TAG, "Outgoing relay preparation deferred mid=${row.mid}", e)
                        null
                    }
                    if (prepared == null) {
                        db.relayOutboxDao().recordAttempt(
                            row.id,
                            "relay preparation deferred",
                        )
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
                        val local = db.messageDao().getById(localId)
                        if (local == null || CarrierState.canAdvance(
                                local.carrierStatus,
                                row.carrierState,
                            )
                        ) {
                            db.messageDao().setCarrierStatusById(
                                localId,
                                row.carrierState,
                                row.lastError,
                            )
                        }
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
                            attachmentsJson(content),
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
                                attachmentsJson = attachmentsJson(content),
                                serverKey = "${row.cid}:$seq",
                                carrierStatus = if (!isIncoming) row.carrierState else "none",
                                carrierError = row.lastError,
                            ),
                        )
                    }

                    db.threadDao().advanceLastSeq(row.cid, seq)
                    db.relayOutboxDao().markRelaySent(row.id, seq)
                    if (isIncoming) {
                        if (row.direction == "incoming_mms") {
                            row.providerId?.let { db.processedMmsDao().insert(ProcessedMms(it)) }
                        } else {
                            row.providerId?.let { db.processedSmsDao().insert(ProcessedSms(it)) }
                        }
                        db.relayOutboxDao().delete(row.id)
                    }
                }

                client.emitDelivered(row.cid, seq)
                val current = if (isIncoming) null else db.relayOutboxDao().getByMid(row.mid)
                if (current != null && current.carrierState !in setOf("unknown", "not_applicable")) {
                    syncOutboxCarrierStatus(current, client)
                }
                Log.i(TAG, "Relay outbox delivered mid=${row.mid} seq=$seq")
            }
        }
    }

    /** Resolve/create the server SMS conversation and encrypt queued carrier content. */
    private suspend fun prepareRelayOutbox(row: RelayOutbox): RelayOutbox? {
        val c = creds ?: Credentials.load(this) ?: return null
        val a = api ?: RelayApi(getServerUrl()).also { it.token = c.token }.also { api = it }
        val phone = PhoneNumberNormalizer.normalize(row.phoneNumber)
        if (!isSmsAddress(phone)) return null

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

        val members = a.convMembers(resolvedThread.cid)
        if (!members.optBoolean("ok")) return null
        if (!validateTrustedRecipients(a, c, members)) return null
        val recipients = mutableListOf<CryptoUtil.Recipient>()
        val membersArr = members.optJSONArray("members") ?: JSONArray()
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
                Log.e(TAG, "Blocked send: public key changed for pinned sid=$sid")
                return null
            }
            recipients += CryptoUtil.Recipient(sid, pubKey)
        }
        if (recipients.isEmpty()) return null
        val payload = CryptoUtil.envelopeToJson(
            CryptoUtil.encryptMessage(row.plaintext, recipients, c.keypair),
        )
        db.relayOutboxDao().markPrepared(row.id, resolvedThread.cid, payload.toString())
        return db.relayOutboxDao().getByMid(row.mid)
    }

    private suspend fun handleCarrierStatus(intent: Intent) {
        val mid = intent.getStringExtra(CarrierStatusReceiver.EXTRA_MID).orEmpty()
        val cid = intent.getStringExtra(CarrierStatusReceiver.EXTRA_CID).orEmpty()
        val seq = intent.getIntExtra(CarrierStatusReceiver.EXTRA_SEQ, 0)
        val status = intent.getStringExtra(CarrierStatusReceiver.EXTRA_STATUS).orEmpty()
        val error = intent.getStringExtra(CarrierStatusReceiver.EXTRA_ERROR)
        val row = db.relayOutboxDao().getByMid(mid)
        val actualCid = row?.cid?.takeIf { it.isNotBlank() } ?: cid
        val actualSeq = row?.serverSeq?.takeIf { it > 0 } ?: seq
        val client = relay
        if (row != null && client?.isConnected == true) {
            syncOutboxCarrierStatus(row, client)
            return
        }
        if (actualCid.isNotBlank() && actualSeq > 0 && status.isNotBlank() && client?.isConnected == true) {
            val ack = client.emitCarrierStatusAwait(actualCid, actualSeq, status, error)
            if (ack.optBoolean("ok")) {
                db.relayReceiptDao().markStatusSynced(actualCid, actualSeq, status)
            } else {
                Log.w(TAG, "Carrier status relay deferred for $actualCid/$actualSeq")
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

    private fun attachmentsJson(content: RelayContent): String? {
        if (content.attachments.isEmpty()) return null
        val rows = JSONArray()
        content.attachments.forEach {
            rows.put(
                JSONObject()
                    .put("name", it.name)
                    .put("content_type", it.contentType)
                    .put("data", it.data)
                    .put("size", it.size),
            )
        }
        return rows.toString()
    }

    /** Relay message from web/another device -> carrier SMS. */
    private fun handleRelayMessage(env: JSONObject) {
        val cid = env.optString("cid")
        if (cid.isBlank()) return
        scope.launch {
            syncMutex.withLock {
                try {
                    val c = creds ?: Credentials.load(this@SmsBridgeService) ?: return@withLock
                    val a = api ?: RelayApi(getServerUrl()).also { it.token = c.token }.also { api = it }
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
            val rows = response.optJSONArray("messages") ?: return
            if (rows.length() == 0) return
            var consumed = 0
            for (i in 0 until rows.length()) {
                val message = rows.optJSONObject(i) ?: continue
                val seq = message.optInt("seq")
                if (seq <= cursor) continue
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
        val cid = env.optString("cid").ifBlank { thread.cid }
        val senderSid = env.optString("sender_sid")
        val seq = env.optInt("seq")
        if (seq <= 0) return true
        if (senderSid == c.sid) {
            val status = env.optString("carrier_status", "none")
            if (status.isNotBlank() && status != "none") {
                db.messageDao().setCarrierStatus(
                    cid,
                    seq,
                    status,
                    env.optString("carrier_error").takeIf { it.isNotBlank() },
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
                for (i in 0 until membersArr.length()) {
                    val m = membersArr.optJSONObject(i) ?: continue
                    val sid = m.optString("sid")
                    val pubKey = m.optString("pub_key")
                    if (sid.isBlank() || pubKey.isBlank()) continue
                    val pinned = db.deviceCacheDao().pinOrReject(
                        DeviceCache(
                            sid = sid,
                            userId = m.optInt("user_id"),
                            name = m.optString("name"),
                            pubKey = pubKey,
                        ),
                    )
                    if (!pinned) {
                        Log.e(TAG, "Blocked receive: public key changed for pinned sid=$sid")
                        return false
                    }
                }
                senderDev = db.deviceCacheDao().get(senderSid)
            }
        }
        if (senderPubKey == null) senderPubKey = senderDev?.pubKey
        val trustedSender = db.deviceTrustDao().getPin(senderSid)
        if (senderPubKey != null && (trustedSender == null || trustedSender.pubKey != senderPubKey)) {
            Log.e(TAG, "Blocked envelope: sender is missing or differs from trusted sid=$senderSid")
            return false
        }
        if (senderPubKey == null) {
            if (!memberLookupSucceeded) {
                // A transient member lookup failure is retryable. Do not advance
                // the durable sequence cursor and permanently lose this row.
                return false
            }
            // This can happen for history created before sender-key snapshots
            // and after the sending device was revoked.
            Log.w(TAG, "Cannot resolve sender pubkey for $senderSid; skipping seq=$seq")
            db.threadDao().advanceLastSeq(cid, seq)
            relay?.emitDelivered(cid, seq)
            return true
        }

        val plaintext = try {
            CryptoUtil.decryptMessage(
                CryptoUtil.envelopeFromJson(env.getJSONObject("payload")),
                c.sid,
                c.keypair,
                senderPubKey,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Malformed envelope for seq=$seq", e)
            null
        }
        if (plaintext == null) {
            Log.w(TAG, "Message is not decryptable by this device; skipping seq=$seq")
            db.threadDao().advanceLastSeq(cid, seq)
            relay?.emitDelivered(cid, seq)
            return true
        }

        val content = RelayContentCodec.decode(plaintext)

        // Claim before the irreversible carrier side effect. Repeated socket
        // events, reconnect pulls, and concurrent syncs cannot send twice.
        val claim = db.relayReceiptDao().claim(RelayReceipt(cid, seq))
        if (claim == -1L) {
            val receipt = db.relayReceiptDao().get(cid, seq) ?: return false
            if (receipt.status == "claimed") {
                val reclaimed = db.relayReceiptDao().reclaimStale(
                    cid,
                    seq,
                    System.currentTimeMillis() - CLAIM_RETRY_GRACE_MS,
                )
                if (reclaimed == 0) return false
                Log.w(TAG, "Retrying stale carrier claim for $cid/$seq (at-least-once)")
            } else {
                relay?.let { client ->
                    if (client.isConnected) {
                        client.emitCarrierStatusAwait(
                            cid,
                            seq,
                            receipt.status,
                            receipt.lastError,
                        ).takeIf { it.optBoolean("ok") }?.let {
                            db.relayReceiptDao().markStatusSynced(cid, seq, receipt.status)
                        }
                    }
                }
                db.threadDao().advanceLastSeq(cid, seq)
                relay?.emitDelivered(cid, seq)
                return true
            }
        }

        val dispatchId = "relay-${cid.take(32)}-$seq"
        val dispatched = if (content.type == RelayContentCodec.TYPE_MMS) {
            MmsSender.send(this@SmsBridgeService, thread.phoneNumber, content, dispatchId, cid, seq)
        } else {
            SmsSender.send(this@SmsBridgeService, thread.phoneNumber, content.text, dispatchId, cid, seq)
        }
        if (!dispatched) {
            db.relayReceiptDao().release(cid, seq)
            return false
        }

        db.relayReceiptDao().markStatus(cid, seq, "dispatched", null)
        relay?.let { client ->
            if (client.isConnected) {
                val ack = client.emitCarrierStatusAwait(cid, seq, "dispatched")
                if (ack.optBoolean("ok")) {
                    db.relayReceiptDao().markStatusSynced(cid, seq, "dispatched")
                }
            }
        }

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
                    attachmentsJson = attachmentsJson(content),
                    serverKey = "$cid:$seq",
                    carrierStatus = "dispatched",
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

    /** Fail-closed validation for the self-only SMS relay recipient directory. */
    private suspend fun validateTrustedRecipients(
        relayApi: RelayApi,
        credentials: SavedCredentials,
        response: JSONObject,
    ): Boolean {
        val refreshed = DeviceSecurityController(
            RelayTrustedDeviceApi(relayApi, credentials.uid.toLong()),
            credentials,
            DeviceTrustRepository(db),
        ).refresh()
        if (refreshed.serverUnsupported || refreshed.selfPending || refreshed.error != null ||
            refreshed.trustWarning != null
        ) {
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
            val existing = db.threadDao().get(cid)
            return (existing?.copy(
                phoneNumber = phone,
                serverName = serverConversationName(row),
                syncedContactName = nullableContactName(row, "synced_contact_name"),
            ) ?: SmsThread(
                cid = cid,
                phoneNumber = phone,
                serverName = serverConversationName(row),
                syncedContactName = nullableContactName(row, "synced_contact_name"),
            ))
                .also { db.threadDao().upsert(it) }
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
            val existing = db.threadDao().get(cid)
            return (existing?.copy(
                phoneNumber = phone,
                serverName = serverConversationName(row),
                syncedContactName = nullableContactName(row, "synced_contact_name"),
            ) ?: SmsThread(
                cid = cid,
                phoneNumber = phone,
                serverName = serverConversationName(row),
                syncedContactName = nullableContactName(row, "synced_contact_name"),
            ))
                .also { db.threadDao().upsert(it) }
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

    private fun ownedPhone(row: JSONObject, username: String): String? {
        val memberRows = row.optJSONArray("members") ?: return null
        val members = buildList {
            for (index in 0 until memberRows.length()) {
                val member = memberRows.optString(index)
                if (member.isNotBlank()) add(member)
            }
        }
        return SmsConversationPolicy.ownedPhone(row.optString("name"), members, username)
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
                val a = api ?: RelayApi(getServerUrl()).also { it.token = c.token }.also { api = it }
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
            if (!ack.optBoolean("ok")) return
            db.relayReceiptDao().markStatusSynced(
                receipt.cid,
                receipt.seq,
                receipt.status,
            )
        }
    }

    private suspend fun syncSmsThreads(): Set<String> {
        val c = creds ?: return emptySet()
        val a = api ?: RelayApi(getServerUrl()).also { it.token = c.token }.also { api = it }
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
                val existing = db.threadDao().get(cid)
                db.threadDao().upsert(
                    existing?.copy(
                        phoneNumber = phone,
                        serverName = serverConversationName(row),
                        syncedContactName = nullableContactName(row, "synced_contact_name"),
                    ) ?: SmsThread(
                        cid = cid,
                        phoneNumber = phone,
                        serverName = serverConversationName(row),
                        syncedContactName = nullableContactName(row, "synced_contact_name"),
                    ),
                )
                ownedCids += cid
            }
        }
        return ownedCids
    }

    private suspend fun importRecentInbox() {
        for (sms in SmsProvider.recentInbox(this)) {
            if (!db.processedSmsDao().contains(sms.id)) {
                handleIncomingSms(sms.address, sms.body, sms.id, sms.date)
            }
        }
    }

    private fun isSmsAddress(value: String): Boolean =
        Regex("^\\+?[0-9*#]{3,24}$").matches(value)

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
