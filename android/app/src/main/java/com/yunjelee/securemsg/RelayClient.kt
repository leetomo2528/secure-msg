package com.yunjelee.securemsg

import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.net.URISyntaxException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class RelayClient(val baseUrl: String) {

    var socket: Socket? = null
    var onMessageNew: ((env: JSONObject) -> Unit)? = null
    var onConnect: (() -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var onConnectError: ((message: String) -> Unit)? = null
    var onBlocklistUpdated: (() -> Unit)? = null
    var onConvUpdated: ((data: JSONObject) -> Unit)? = null
    var token: String? = null

    fun connect(token: String) {
        this.token = token
        disconnect()
        try {
            val opts = IO.Options()
            opts.auth = mapOf("token" to token)
            opts.forceNew = true
            opts.reconnection = true
            // Keep Socket.IO's polling fallback. Some reverse-proxy chains do
            // not pass WebSocket upgrades even though polling is available.
            socket = IO.socket(baseUrl, opts)
        } catch (e: URISyntaxException) {
            throw RuntimeException(e)
        }

        socket?.on(Socket.EVENT_CONNECT, Emitter.Listener {
            onConnect?.invoke()
        })
        socket?.on(Socket.EVENT_DISCONNECT, Emitter.Listener {
            onDisconnect?.invoke()
        })
        socket?.on(Socket.EVENT_CONNECT_ERROR, Emitter.Listener { args ->
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
        socket?.connect()
    }

    fun sendMessage(
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
        messageId: String = UUID.randomUUID().toString(),
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

    fun emitCarrierStatus(cid: String, seq: Int, status: String, error: String? = null) {
        val body = JSONObject().put("cid", cid).put("seq", seq).put("status", status)
        if (!error.isNullOrBlank()) body.put("error", error.take(300))
        socket?.emit("carrier_status", body)
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
        socket?.off()
        socket?.disconnect()
        socket = null
    }

    suspend fun awaitConnected(timeoutMillis: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!isConnected && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        return isConnected
    }

    val isConnected: Boolean get() = socket?.connected() == true
}
