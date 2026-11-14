package dev.rexclient.android

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface Screen {
    data object Connect : Screen
    data object Sessions : Screen
    data class Session(val sessionId: String) : Screen
    data class Terminal(val sessionId: String, val block: BlockInfo) : Screen
}

class RexViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("rex", Context.MODE_PRIVATE)

    var serverAddress by mutableStateOf(prefs.getString("server", "") ?: "")
    var screen by mutableStateOf<Screen>(Screen.Connect)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set
    var sessionView by mutableStateOf<SessionView?>(null)
        private set

    /** The live control connection, or null while disconnected or reconnecting. */
    var control by mutableStateOf<RexControl?>(null)
        private set

    /** Why the connection dropped, while we try to get it back. */
    var reconnecting by mutableStateOf<String?>(null)
        private set

    private var connectedAddress: String? = null
    private var lastToken: String? = null
    private var watchJobs = mutableListOf<Job>()
    private var reconnectJob: Job? = null

    fun connect() {
        val address = serverAddress.trim()
        prefs.edit().putString("server", address).apply()
        run("Couldn't connect") {
            stopReconnecting()
            control?.close()
            install(RexControl.connect(address), address)
            sessions = requireControl().listSessions()
            screen = Screen.Sessions
        }
    }

    private fun install(c: RexControl, address: String) {
        control = c
        connectedAddress = address
        lastToken = c.reconnectToken
        reconnecting = null
        watchJobs.forEach { it.cancel() }
        watchJobs = mutableListOf(
            viewModelScope.launch {
                c.closedReason.filterNotNull().collect { reason ->
                    if (control === c) onConnectionLost(reason)
                }
            },
            viewModelScope.launch { c.events.collect { ev -> onEvent(c, ev) } },
        )
    }

    fun disconnect() {
        stopReconnecting()
        val c = control
        control = null
        connectedAddress = null
        lastToken = null
        c?.close()
        screen = Screen.Connect
    }

    // --- Reconnecting ---
    //
    // Android cuts a backgrounded app's sockets after a few seconds, and phones
    // change networks, so a dropped connection is routine. The server keeps our
    // client for a minute; reconnect with its token, re-attach, and carry on.

    private fun onConnectionLost(reason: String) {
        control = null
        reconnecting = reason
        tryReconnect()
    }

    /** Called when the app comes to the foreground. */
    fun onForeground() {
        if (reconnecting != null && reconnectJob?.isActive != true) tryReconnect()
    }

    private fun tryReconnect() {
        val address = connectedAddress ?: return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            for (attempt in 1..6) {
                try {
                    val c = RexControl.connect(address, lastToken)
                    install(c, address)
                    when (val s = screen) {
                        is Screen.Session -> sessionView = c.attach(s.sessionId)
                        is Screen.Terminal -> sessionView = c.attach(s.sessionId)
                        Screen.Sessions -> sessions = c.listSessions()
                        Screen.Connect -> Unit
                    }
                    return@launch
                } catch (e: Exception) {
                    if (control != null) { // Connected, but re-attaching failed.
                        error = "Reconnected, but: ${e.message}"
                        screen = Screen.Sessions
                        runCatching { sessions = control!!.listSessions() }
                        return@launch
                    }
                    reconnecting = e.message ?: "connection failed"
                    delay(minOf(1000L * attempt, 5000L))
                }
            }
            // Gave up for now; onForeground() or the Retry button tries again.
        }
    }

    fun retryNow() = tryReconnect()

    private fun stopReconnecting() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnecting = null
    }

    // --- Navigation and actions ---

    fun refreshSessions() = run("Couldn't list sessions") {
        sessions = requireControl().listSessions()
    }

    fun newSession() = run("Couldn't create a session") {
        val c = requireControl()
        val id = c.createShellSession()
        sessions = c.listSessions()
        sessionView = c.attach(id)
        screen = Screen.Session(id)
    }

    fun openSession(sessionId: String) = run("Couldn't attach") {
        sessionView = requireControl().attach(sessionId)
        screen = Screen.Session(sessionId)
    }

    fun openBlock(block: BlockInfo) {
        val s = screen as? Screen.Session ?: return
        screen = Screen.Terminal(s.sessionId, block)
    }

    fun back() {
        when (val s = screen) {
            is Screen.Terminal -> screen = Screen.Session(s.sessionId)
            is Screen.Session -> {
                val c = control
                screen = Screen.Sessions
                sessionView = null
                if (c != null) {
                    viewModelScope.launch { runCatching { c.detach(s.sessionId) } }
                    refreshSessions()
                }
            }
            Screen.Sessions -> disconnect()
            Screen.Connect -> Unit
        }
    }

    private fun onEvent(c: RexControl, ev: JSONObject) {
        when (ev.optString("type")) {
            "session_created", "session_destroyed", "session_label_changed" ->
                if (screen == Screen.Sessions) refreshSessions()
            "session_event" -> {
                val p = ev.optJSONObject("payload") ?: return
                val sid = p.optString("session_id")
                val inner = p.optJSONObject("event")?.optString("type") ?: return
                val current = (screen as? Screen.Session)?.sessionId
                    ?: (screen as? Screen.Terminal)?.sessionId
                if (sid != current || inner !in VIEW_EVENTS) return
                viewModelScope.launch {
                    runCatching { c.view(sid) }.onSuccess { v ->
                        if (v.revision >= (sessionView?.revision ?: -1)) sessionView = v
                    }
                }
            }
        }
    }

    private fun requireControl() = control ?: error("Not connected")

    private fun run(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: Exception) {
                error = "$what: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        control?.close()
    }

    companion object {
        private val VIEW_EVENTS = setOf(
            "session_view_changed", "block_created", "block_closed",
            "window_created", "window_closed", "window_label_changed", "session_label_changed",
        )
    }
}
