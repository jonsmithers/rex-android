package dev.rexclient.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** An error response from the Rex control API. Branch on [code], not the message. */
class RexError(val code: String, message: String, val status: Int) : Exception("$code: $message")

/**
 * One `rex.control.v1` connection: a WebSocket at /api/control that carries
 * JSON `request`/`response` messages and server `event`s.
 */
class RexControl private constructor(val baseUrl: HttpUrl, private val resumeToken: String?) {

    companion object {
        const val API_REVISION = 11
        const val DEFAULT_PORT = 7297

        val http: OkHttpClient = OkHttpClient.Builder()
            // The server pings every 120s; ping it too so we notice a dead server.
            .pingInterval(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // OkHttp only compresses messages >= this size, so the hello
            // (which can carry a reconnect token) is never compressed.
            .minWebSocketMessageToCompress(1024)
            .build()

        /**
         * Opens a control connection and waits for the server's welcome. Pass the
         * [reconnectToken] of a connection that dropped to continue that client.
         */
        suspend fun connect(serverAddress: String, reconnectToken: String? = null): RexControl {
            val c = RexControl(parseServerAddress(serverAddress), reconnectToken)
            c.open()
            return c
        }

        /**
         * Accepts `host`, `host:port`, `http(s)://host[:port]` or `ws(s)://...`.
         * A bare host with no scheme defaults to plain HTTP on Rex's port 7297.
         */
        fun parseServerAddress(input: String): HttpUrl {
            var s = input.trim().trimEnd('/')
            require(s.isNotEmpty()) { "Enter a server address" }
            val hadScheme = s.contains("://")
            s = when {
                s.startsWith("ws://") -> "http://" + s.removePrefix("ws://")
                s.startsWith("wss://") -> "https://" + s.removePrefix("wss://")
                hadScheme -> s
                else -> "http://$s"
            }
            var url = s.toHttpUrl()
            val hostPart = s.substringAfter("://").substringBefore('/')
            val hasExplicitPort = hostPart.substringAfterLast(']').contains(':')
            if (!hadScheme && !hasExplicitPort) {
                url = url.newBuilder().port(DEFAULT_PORT).build()
            }
            return url.newBuilder().encodedPath("/").build()
        }
    }

    private lateinit var ws: WebSocket
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val welcome = CompletableDeferred<JSONObject>()

    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 1024)
    /** Inner event objects (`{"type": ..., "payload": ...}`) as the server sends them. */
    val events: SharedFlow<JSONObject> = _events

    private val _closedReason = MutableStateFlow<String?>(null)
    /** Non-null once the connection has closed or failed. */
    val closedReason: StateFlow<String?> = _closedReason

    var clientId: String? = null
        private set

    /** Token to continue this client on a new connection. Never log it. */
    var reconnectToken: String? = null
        private set

    /** True if the server continued the client named by the token we sent. */
    var resumed = false
        private set

    private suspend fun open() {
        val request = Request.Builder()
            .url(baseUrl.resolve("/api/control")!!)
            .header("Sec-WebSocket-Protocol", "rex.control.v1")
            .build()
        ws = http.newWebSocket(request, Listener())
        val w = try {
            withTimeout(10_000) { welcome.await() }
        } catch (e: Exception) {
            ws.cancel()
            throw if (e is IOException) e else IOException("No welcome from server: ${e.message}", e)
        }
        if (w.optString("protocol") != "rex.control.v1") {
            ws.close(1000, null)
            throw IOException("Unexpected protocol: ${w.optString("protocol")}")
        }
        w.optJSONObject("client")?.let {
            clientId = it.optString("id")
            reconnectToken = it.optString("reconnect_token").ifEmpty { null }
            resumed = it.optBoolean("resumed")
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = JSONObject()
                .put("type", "hello")
                .put("api_revision", API_REVISION)
                .also { h -> if (resumeToken != null) h.put("reconnect_token", resumeToken) }
                .put(
                    "client", JSONObject()
                        .put("kind", "app")
                        .put("platform", "android")
                        .put("name", "Rex Android")
                        .put("version", BuildInfo.VERSION)
                )
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try { JSONObject(text) } catch (_: Exception) { return }
            when (msg.optString("type")) {
                "welcome" -> welcome.complete(msg)
                "response" -> pending.remove(msg.optString("id"))?.complete(msg)
                "event" -> msg.optJSONObject("payload")?.let { _events.tryEmit(it) }
                else -> Unit // Newer servers may add message types; skip them.
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            finish("closed by server ($code${if (reason.isNotEmpty()) " $reason" else ""})")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish("closed ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = when {
                response?.code == 403 -> "server refused this caller (403). Is it listening with access for you?"
                response != null -> "HTTP ${response.code} ${response.message}"
                else -> t.message ?: t.javaClass.simpleName
            }
            finish(detail)
        }
    }

    private fun finish(reason: String) {
        if (_closedReason.value == null) _closedReason.value = reason
        val err = IOException("Connection lost: $reason")
        welcome.completeExceptionally(err)
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    /** Calls a control API method and returns its result payload. */
    suspend fun call(method: String, payload: JSONObject? = null, timeoutMs: Long = 30_000): JSONObject {
        _closedReason.value?.let { throw IOException("Connection lost: $it") }
        val id = nextId.getAndIncrement().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val msg = JSONObject().put("type", "request").put("id", id).put("method", method)
        if (payload != null) msg.put("payload", payload)
        if (!ws.send(msg.toString())) {
            pending.remove(id)
            throw IOException("Connection lost")
        }
        val response = try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
        response.optJSONObject("error")?.let {
            throw RexError(it.optString("code"), it.optString("message"), it.optInt("status"))
        }
        return response.optJSONObject("payload") ?: JSONObject()
    }

    /** Calls a block method such as `com.superlogical.terminal.write`. */
    suspend fun callBlock(sessionId: String, blockId: String, method: String, args: JSONObject? = null): JSONObject =
        call(
            method,
            JSONObject().put("session_id", sessionId).put("block_id", blockId).also { p ->
                if (args != null) p.put("args", args)
            },
        )

    // --- Typed helpers for the handful of methods this client uses. ---

    suspend fun listSessions(): List<SessionSummary> {
        val arr = call("session.list").optJSONArray("sessions") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SessionSummary(o.getString("session_id"), o.optString("label"))
        }
    }

    suspend fun createShellSession(): String {
        val payload = JSONObject().put(
            "initial_windows",
            JSONArray().put(
                JSONObject().put(
                    "layout",
                    JSONObject().put("block", JSONObject().put("flavor", "com.superlogical.terminal.shell")),
                )
            ),
        )
        return call("session.create", payload).getString("session_id")
    }

    suspend fun attach(sessionId: String): SessionView =
        SessionView.parse(call("session.attach", JSONObject().put("session_id", sessionId)).getJSONObject("view"))

    suspend fun view(sessionId: String): SessionView =
        SessionView.parse(call("session.view", JSONObject().put("session_id", sessionId)).let { it.optJSONObject("view") ?: it })

    suspend fun detach(sessionId: String) {
        call("session.detach", JSONObject().put("session_id", sessionId))
    }

    suspend fun ownBlock(sessionId: String, blockId: String, take: Boolean = true) {
        call(
            "session.own_block",
            JSONObject()
                .put("session_id", sessionId)
                .put("block_ids", JSONArray().put(blockId))
                .put("mode", if (take) "take" else "request"),
        )
    }

    suspend fun disownBlock(sessionId: String, blockId: String) {
        call(
            "session.disown_block",
            JSONObject().put("session_id", sessionId).put("block_ids", JSONArray().put(blockId)),
        )
    }

    fun close() {
        if (this::ws.isInitialized) ws.close(1000, null)
        finish("closed by client")
    }
}

data class SessionSummary(val id: String, val label: String)

data class BlockInfo(
    val id: String,
    val label: String,
    val creator: String,
    val windowId: String,
    val windowLabel: String,
    val focused: Boolean,
) {
    val isTerminal get() = creator == "com.superlogical.terminal"
}

data class SessionView(
    val id: String,
    val label: String,
    val revision: Long,
    val activeWindowId: String?,
    val blocks: List<BlockInfo>,
) {
    companion object {
        fun parse(v: JSONObject): SessionView {
            val blocks = mutableListOf<BlockInfo>()
            val windows = v.optJSONArray("windows") ?: JSONArray()
            for (i in 0 until windows.length()) {
                val w = windows.getJSONObject(i)
                val windowId = w.optString("window_id")
                val focused = w.optString("focused_block_id")
                val layers = w.optJSONArray("layers") ?: JSONArray()
                for (j in 0 until layers.length()) {
                    val layerBlocks = layers.getJSONObject(j).optJSONArray("blocks") ?: JSONArray()
                    for (k in 0 until layerBlocks.length()) {
                        val b = layerBlocks.getJSONObject(k)
                        val id = b.getString("block_id")
                        blocks += BlockInfo(
                            id = id,
                            label = b.optString("label").ifEmpty { id.takeLast(8) },
                            creator = b.optString("creator_name"),
                            windowId = windowId,
                            windowLabel = w.optString("label"),
                            focused = id == focused,
                        )
                    }
                }
            }
            return SessionView(
                id = v.optString("session_id"),
                label = v.optString("label"),
                revision = v.optLong("revision"),
                activeWindowId = v.optString("active_window_id").ifEmpty { null },
                blocks = blocks,
            )
        }
    }
}

object BuildInfo {
    const val VERSION = "0.1.0"
}
