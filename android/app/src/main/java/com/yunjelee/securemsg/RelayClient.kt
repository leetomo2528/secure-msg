package com.yunjelee.securemsg

import android.os.Process
import android.os.SystemClock
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class RelayClient(val baseUrl: String) {

    /** Process-wide creation order, sent as `n=` so the relay can tell a replaced client from a reconnect. */
    val ordinal: Int = CLIENTS.incrementAndGet()
    /** [SystemClock.elapsedRealtime] at construction; the bridge leaves a young client alone. */
    val createdAtElapsed: Long = SystemClock.elapsedRealtime()
    /** Whether any connect attempt of this client was refused or failed. */
    @Volatile var hadConnectError: Boolean = false
        private set
    /**
     * [SystemClock.elapsedRealtime] since which this client has been without a
     * connection (creation, until the first connect), or null while connected.
     */
    @Volatile var disconnectedSinceElapsed: Long? = createdAtElapsed
        private set

    var socket: Socket? = null
    var onMessageNew: ((env: JSONObject) -> Unit)? = null
    var onConnect: (() -> Unit)? = null
    /** Socket.IO's reason, e.g. 'io server disconnect', 'transport close', 'ping timeout'. */
    var onDisconnect: ((reason: String) -> Unit)? = null
    /** Manager-level reconnect/close events, for logs and [Diagnostics]. */
    var onTrace: ((String) -> Unit)? = null
    var onConnectError: ((message: String) -> Unit)? = null
    var onBlocklistUpdated: (() -> Unit)? = null
    var onConvUpdated: ((data: JSONObject) -> Unit)? = null
    var onContactsUpdated: ((data: JSONObject) -> Unit)? = null
    var onDevicePending: ((data: JSONObject) -> Unit)? = null
    var onDeviceApproved: ((data: JSONObject) -> Unit)? = null

    fun connect(token: String) {
        disconnect()
        // A live map rather than mapOf(): socket.io-client 2.1.0 keeps the
        // Options.auth reference and serialises it on every (re)open, so the
        // reconnect-attempt listener below can refresh `diag` in place.
        // Servers that only read auth['token'] ignore the extra key.
        val auth = ConcurrentHashMap<String, String>()
        auth["token"] = token
        auth["diag"] = diag()
        val created = try {
            val opts = IO.Options()
            opts.auth = auth
            opts.forceNew = true
            opts.reconnection = true
            // Keep Socket.IO's polling fallback. Some reverse-proxy chains do
            // not pass WebSocket upgrades even though polling is available.
            IO.socket(baseUrl, opts)
        } catch (e: URISyntaxException) {
            throw RuntimeException(e)
        }
        socket = created

        socket?.on(Socket.EVENT_CONNECT, Emitter.Listener {
            disconnectedSinceElapsed = null
            onConnect?.invoke()
        })
        socket?.on(Socket.EVENT_DISCONNECT, Emitter.Listener { args ->
            val reason = args.firstOrNull()?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
            val now = SystemClock.elapsedRealtime()
            disconnectedSinceElapsed = now
            noteClose(reason, now)
            onDisconnect?.invoke(reason)
        })
        socket?.on(Socket.EVENT_CONNECT_ERROR, Emitter.Listener { args ->
            hadConnectError = true
            val message = args.joinToString(" ") { value ->
                when (value) {
                    is JSONObject -> value.optString("message", value.toString())
                    is Throwable -> value.message ?: value.javaClass.simpleName
                    else -> value?.toString().orEmpty()
                }
            }.ifBlank { "relay connection failed" }
            onConnectError?.invoke(message)
        })
        socket?.on("message_new", Emitter.Listener { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                onMessageNew?.invoke(args[0] as JSONObject)
            }
        })
        socket?.on("blocklist_updated", Emitter.Listener {
            onBlocklistUpdated?.invoke()
        })
        socket?.on("conv_updated", Emitter.Listener { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                onConvUpdated?.invoke(args[0] as JSONObject)
            }
        })
        socket?.on("contacts_updated", Emitter.Listener { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                onContactsUpdated?.invoke(args[0] as JSONObject)
            }
        })
        socket?.on("device_pending", Emitter.Listener { args ->
            onDevicePending?.invoke(
                if (args.isNotEmpty() && args[0] is JSONObject) args[0] as JSONObject else JSONObject(),
            )
        })
        // A device approved elsewhere is invisible to a gateway whose socket
        // never drops: nothing else re-reads the key directory on the receive
        // path, so every envelope from that device was refused as unpinned and
        // the conversation stalled behind it.
        socket?.on("device_approved", Emitter.Listener { args ->
            onDeviceApproved?.invoke(
                if (args.isNotEmpty() && args[0] is JSONObject) args[0] as JSONObject else JSONObject(),
            )
        })
        // forceNew gives every client its own Manager. Its listeners outlive
        // socket.off(), so each checks that this socket is still the current
        // one before reporting.
        val manager = created.io()
        manager.on(Manager.EVENT_RECONNECT_ATTEMPT, Emitter.Listener { args ->
            if (socket !== created) return@Listener
            auth["diag"] = diag()
            val attempt = (args.firstOrNull() as? Number)?.toInt() ?: 0
            if (DiagnosticsFormat.isLoggedAttempt(attempt)) onTrace?.invoke("reconnect_attempt n=$attempt")
        })
        manager.on(Manager.EVENT_RECONNECT, Emitter.Listener { args ->
            if (socket !== created) return@Listener
            onTrace?.invoke("reconnect after=${args.firstOrNull() ?: "?"}")
        })
        manager.on(Manager.EVENT_CLOSE, Emitter.Listener { args ->
            if (socket !== created) return@Listener
            onTrace?.invoke("manager_close reason=${DiagnosticsFormat.token(args.firstOrNull()?.toString())}")
        })
        socket?.connect()
    }

    private fun diag(): String {
        val now = SystemClock.elapsedRealtime()
        val uptimeSeconds = try {
            (now - Process.getStartElapsedRealtime()) / 1000
        } catch (_: Throwable) {
            0L
        }
        val close = lastClose
        return DiagnosticsFormat.relayDiag(
            pid = Process.myPid(),
            uptimeSeconds = uptimeSeconds,
            ordinal = ordinal,
            lastCloseReason = close?.reason,
            lastCloseAgeSeconds = close?.let { (now - it.atElapsed) / 1000 },
            versionCode = BuildConfig.VERSION_CODE,
            visible = Diagnostics.visible,
        )
    }

    private fun sendMessage(
        cid: String,
        payload: JSONObject,
        messageId: String,
        callback: (ack: JSONObject) -> Unit,
    ) {
        val s = socket ?: return callback(JSONObject().put("ok", false).put("error", "no socket"))
        s.emit("message_send",
            JSONObject().put("cid", cid).put("mid", messageId).put("payload", payload),
            io.socket.client.Ack { args ->
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    callback(args[0] as JSONObject)
                } else {
                    callback(JSONObject().put("ok", false).put("error", "no ack"))
                }
            })
    }

    suspend fun sendMessageAwait(
        cid: String,
        payload: JSONObject,
        messageId: String,
        timeoutMillis: Long = 10_000,
    ): JSONObject {
        var last = JSONObject().put("ok", false).put("error", "relay acknowledgement timeout")
        repeat(2) {
            last = withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    sendMessage(cid, payload, messageId) { ack ->
                        if (continuation.isActive) continuation.resume(ack)
                    }
                }
            } ?: JSONObject().put("ok", false).put("error", "relay acknowledgement timeout")
            if (last.optBoolean("ok") || last.optString("error") != "relay acknowledgement timeout") {
                return last
            }
        }
        return last
    }

    fun emitDelivered(cid: String, seq: Int) {
        socket?.emit("message_delivered", JSONObject().put("cid", cid).put("seq", seq))
    }

    suspend fun emitCarrierStatusAwait(
        cid: String,
        seq: Int,
        status: String,
        error: String? = null,
        timeoutMillis: Long = 10_000,
    ): JSONObject {
        val body = JSONObject().put("cid", cid).put("seq", seq).put("status", status)
        if (!error.isNullOrBlank()) body.put("error", error.take(300))
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val current = socket
                if (current == null || !current.connected()) {
                    continuation.resume(
                        JSONObject().put("ok", false).put("error", "relay disconnected"),
                    )
                    return@suspendCancellableCoroutine
                }
                current.emit("carrier_status", body, io.socket.client.Ack { args ->
                    val ack = if (args.isNotEmpty() && args[0] is JSONObject) {
                        args[0] as JSONObject
                    } else {
                        JSONObject().put("ok", false).put("error", "no carrier status ack")
                    }
                    if (continuation.isActive) continuation.resume(ack)
                })
            }
        } ?: JSONObject().put("ok", false).put("error", "carrier status acknowledgement timeout")
    }

    fun disconnect() {
        val current = socket ?: return
        socket = null
        current.off()
        if (current.connected()) {
            val now = SystemClock.elapsedRealtime()
            disconnectedSinceElapsed = now
            // Socket.IO's own name for a close this side asked for.
            noteClose("io client disconnect", now)
        }
        current.disconnect()
    }

    suspend fun awaitConnected(timeoutMillis: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!isConnected && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        return isConnected
    }

    val isConnected: Boolean get() = socket?.connected() == true

    private class Close(val reason: String, val atElapsed: Long)

    companion object {
        private val CLIENTS = AtomicInteger(0)

        /** The last close of any client in this process: the `x=` of the next diag. */
        @Volatile private var lastClose: Close? = null

        private fun noteClose(reason: String, atElapsed: Long) {
            lastClose = Close(reason, atElapsed)
        }
    }
}
