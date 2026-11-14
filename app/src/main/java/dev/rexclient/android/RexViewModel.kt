package dev.rexclient.android

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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

    /** Title, process, and directory of each terminal block in [sessionView], by block ID. */
    val blockDetails = mutableStateMapOf<String, BlockDetails>()
    private val fetchingDetails = mutableSetOf<String>()

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
                        is Screen.Session -> showView(c, c.attach(s.sessionId), fresh = true)
                        is Screen.Terminal -> showView(c, c.attach(s.sessionId), fresh = true)
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
        showView(c, c.attach(id), fresh = true)
        screen = Screen.Session(id)
    }

    fun openSession(sessionId: String) = run("Couldn't attach") {
        val c = requireControl()
        showView(c, c.attach(sessionId), fresh = true)
        screen = Screen.Session(sessionId)
    }

    /**
     * Opens a new tab with a shell in the current session and goes straight to
     * it. The app doesn't show layouts, so it never splits; the new tab also
     * doesn't become the active one, so Rex.app keeps showing its tab.
     */
    fun newTab() = run("Couldn't open a tab") {
        val sessionId = currentSessionId() ?: return@run
        val c = requireControl()
        val blockId = c.newWindow(sessionId)
        val v = c.view(sessionId)
        showView(c, v)
        val block = v.blocks.find { it.id == blockId } ?: return@run
        if (currentSessionId() == sessionId) screen = Screen.Terminal(sessionId, block)
    }

    /** The newest info for a block, falling back to what navigation captured. */
    fun liveBlock(block: BlockInfo): BlockInfo = sessionView?.blocks?.find { it.id == block.id } ?: block

    private fun currentSessionId(): String? =
        (screen as? Screen.Session)?.sessionId ?: (screen as? Screen.Terminal)?.sessionId

    /**
     * Shows a session view unless it's older than the one we hold, and reads
     * the details of terminal blocks we haven't seen. [fresh] drops what we
     * knew, for a new attach, where events may have been missed.
     */
    private fun showView(c: RexControl, v: SessionView, fresh: Boolean = false) {
        val old = sessionView
        if (fresh || old == null || old.id != v.id) {
            blockDetails.clear()
            fetchingDetails.clear()
        } else if (v.revision < old.revision) {
            return
        }
        sessionView = v
        val ids = v.blocks.mapTo(HashSet()) { it.id }
        blockDetails.keys.retainAll(ids)
        for (b in v.blocks) {
            if (!b.isTerminal || b.id in blockDetails || !fetchingDetails.add(b.id)) continue
            viewModelScope.launch {
                runCatching { c.terminalDetails(v.id, b.id) }
                    .onSuccess { d -> if (sessionView?.id == v.id && fetchingDetails.remove(b.id)) blockDetails[b.id] = d }
                    .onFailure { fetchingDetails.remove(b.id) }
            }
        }
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
                blockDetails.clear()
                fetchingDetails.clear()
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
                if (sid != current) return
                if (inner == "block_event") {
                    p.optJSONObject("event")?.optJSONObject("payload")?.let(::onBlockEvent)
                    return
                }
                if (inner !in VIEW_EVENTS) return
                viewModelScope.launch {
                    runCatching { c.view(sid) }.onSuccess { v ->
                        if (sessionView?.id == sid) showView(c, v)
                    }
                }
            }
        }
    }

    /** Keeps [blockDetails] current from a terminal's own events. */
    private fun onBlockEvent(e: JSONObject) {
        val id = e.optString("block_id")
        val d = blockDetails[id] ?: return // Not read yet; the read will include this change.
        val p = e.optJSONObject("payload") ?: JSONObject()
        blockDetails[id] = when (e.optString("name")) {
            "title_changed" -> d.copy(title = p.optString("title"))
            "process_changed" -> d.withProcess(p.optJSONObject("foreground"))
            "pwd_changed" -> d.withPwd(p)
            else -> return
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
