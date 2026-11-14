package dev.rexclient.android

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams one terminal block over a `rex.data.terminal.v1` data connection.
 *
 * The stream begins with a binary Ghostty snapshot of the whole terminal
 * (scrollback included), which goes to [Listener.onSnapshot] once it has
 * fully arrived. After it comes the program's output, passed through with
 * Rex's own `ESC _ rex1;<nonce>;<json> ESC \` messages stripped and acted on.
 *
 * If the snapshot can't be used (say, the server's Ghostty writes a format our
 * libghostty-vt doesn't read), we fall back to the old way: drop everything up
 * to the first Rex `resize` message (sent right after the snapshot), then seed
 * the screen from `com.superlogical.terminal.format` in `vt` form.
 *
 * All [Listener] callbacks are made from a background thread, in stream order.
 */
class TerminalStream(
    private val control: RexControl,
    private val sessionId: String,
    private val blockId: String,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        /**
         * A complete Ghostty snapshot (envelope through FINISH) that replaces the
         * screen. Return false if it couldn't be decoded, to fall back to [onReset].
         */
        fun onSnapshot(snapshot: ByteArray): Boolean
        /** Start a fresh screen and write [vt], which redraws the current screen. */
        fun onReset(vt: String)
        fun onOutput(bytes: ByteArray)
        fun onResize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)
        fun onTheme(theme: JSONObject)
        fun onChildExited(exitCode: Int)
        fun onStatus(status: String?)
        /** The stream is over for good. */
        fun onEnded(reason: String)
    }

    /**
     * HANDSHAKE → SNAPSHOT → LIVE normally. If the snapshot is unusable:
     * SNAPSHOT → SKIPPING (until the first resize) → SEEDING → LIVE.
     */
    private enum class Phase { HANDSHAKE, SNAPSHOT, SKIPPING, SEEDING, LIVE }

    private val lock = Any()
    private var ws: WebSocket? = null
    private var generation = 0
    private var phase = Phase.HANDSHAKE
    private var marker: ByteArray = ByteArray(0)
    private var stopped = false
    private var retries = 0
    private var seedJob: Job? = null

    // Snapshot bytes received so far, and where the next record header starts.
    private var snap = ByteArray(0)
    private var snapLen = 0
    private var snapScan = 0

    // APC filter state (see [filter]).
    private var escState = 0 // 0 normal, 1 saw ESC, 2 in APC, 3 saw ESC in APC
    private val apc = ByteArrayOutputStream()

    fun start() {
        scope.launch { connect() }
    }

    /** Ends the stream. Closing with 1000 tells the server we're done with it. */
    fun stop() {
        synchronized(lock) {
            stopped = true
            generation++
            ws?.close(1000, null)
            ws = null
        }
        seedJob?.cancel()
    }

    private suspend fun connect() {
        listener.onStatus("Connecting…")
        val ticketB64 = try {
            control.call(
                "data.connect",
                JSONObject()
                    .put("session_id", sessionId)
                    .put("block_id", blockId)
                    .put("data_ref_id", "terminal")
                    .put("subprotocol", SUBPROTOCOL),
            ).getString("ticket")
        } catch (e: Exception) {
            listener.onEnded("Couldn't open terminal: ${e.message}")
            return
        }
        val ticket = Base64.decode(ticketB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        val request = Request.Builder()
            .url(control.baseUrl.resolve("/api/data")!!)
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        synchronized(lock) {
            if (stopped) return
            val gen = ++generation
            phase = Phase.HANDSHAKE
            snap = ByteArray(0); snapLen = 0; snapScan = SNAPSHOT_ENVELOPE
            escState = 0
            apc.reset()
            ws = RexControl.http.newWebSocket(request, DataListener(gen, ticket))
        }
    }

    private inner class DataListener(val gen: Int, val ticket: ByteArray) : WebSocketListener() {
        private fun current() = synchronized(lock) { gen == generation && !stopped }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send((byteArrayOf(0x01) + ticket).toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            synchronized(lock) {
                if (gen != generation || stopped) return
                when (phase) {
                    Phase.HANDSHAKE -> handshake(webSocket, bytes.toByteArray())
                    Phase.SNAPSHOT -> snapshot(bytes.toByteArray())
                    else -> filter(bytes.toByteArray())
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (!current()) return
            when (code) {
                4000 -> end("[block closed]")
                1000 -> end("[stream ended]")
                1008 -> end("[terminal unavailable: $reason]")
                else -> reconnect("closed ($code $reason)")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current()) return
            reconnect(t.message ?: "connection failed")
        }
    }

    private fun handshake(webSocket: WebSocket, msg: ByteArray) {
        when (msg.firstOrNull()?.toInt()?.and(0xff)) {
            0x81 -> {
                val buf = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN)
                val nonce = java.lang.Long.toUnsignedString(buf.getLong(24))
                marker = "rex1;$nonce;".toByteArray()
                phase = Phase.SNAPSHOT
                retries = 0
                listener.onStatus("Loading screen…")
            }
            0x82 -> {
                val reason = msg.getOrNull(1)?.toInt()?.and(0xff) ?: -1
                webSocket.close(1000, null)
                end("[data connection rejected: ${REJECT_REASONS[reason] ?: "reason $reason"}]")
            }
            else -> {
                webSocket.close(1002, null)
                end("[bad handshake from server]")
            }
        }
    }

    /**
     * Collects the snapshot. Its end is found by walking the record framing
     * (each record is u16 tag, u32 payload length, u32 CRC, payload) up to the
     * FINISH record; whatever follows in the same message is live output.
     * Called with [lock] held.
     */
    private fun snapshot(data: ByteArray) {
        if (snapLen + data.size > snap.size) {
            snap = snap.copyOf(maxOf(snap.size * 2, snapLen + data.size, 64 * 1024))
        }
        data.copyInto(snap, snapLen)
        snapLen += data.size
        if (snapLen < SNAPSHOT_ENVELOPE) return
        if (!snap.copyOf(SNAPSHOT_MAGIC.size).contentEquals(SNAPSHOT_MAGIC) || snapLen > MAX_SNAPSHOT) {
            return useFallback(0)
        }
        val buf = ByteBuffer.wrap(snap, 0, snapLen).order(ByteOrder.LITTLE_ENDIAN)
        while (snapScan + RECORD_HEADER <= snapLen) {
            val tag = buf.getShort(snapScan).toInt() and 0xffff
            val end = snapScan.toLong() + RECORD_HEADER + (buf.getInt(snapScan + 2).toLong() and 0xffffffffL)
            if (end > snapLen) return // Wait for the rest of this record.
            snapScan = end.toInt()
            if (tag == TAG_FINISH) {
                val ok = listener.onSnapshot(snap.copyOf(snapScan))
                if (!ok) return useFallback(snapScan)
                val rest = snap.copyOfRange(snapScan, snapLen)
                snap = ByteArray(0); snapLen = 0
                phase = Phase.LIVE
                listener.onStatus(null)
                filter(rest)
                return
            }
        }
    }

    /** Give up on the snapshot; bytes from [from] on go through [filter]. */
    private fun useFallback(from: Int) {
        val rest = snap.copyOfRange(from, snapLen)
        snap = ByteArray(0); snapLen = 0
        phase = Phase.SKIPPING
        filter(rest)
    }

    /**
     * Splits stream bytes into program output (forwarded unless we're still
     * skipping the snapshot or seeding) and Rex APC messages (handled here).
     * Called with [lock] held.
     */
    private fun filter(data: ByteArray) {
        val out = ByteArrayOutputStream(data.size)
        fun flush() {
            if (out.size() > 0 && phase == Phase.LIVE) listener.onOutput(out.toByteArray())
            out.reset()
        }
        for (b in data) {
            val c = b.toInt() and 0xff
            when (escState) {
                0 -> if (c == ESC) escState = 1 else out.write(c)
                1 -> when (c) {
                    '_'.code -> { escState = 2; apc.reset() }
                    ESC -> out.write(ESC) // stay in state 1
                    else -> { out.write(ESC); out.write(c); escState = 0 }
                }
                2 -> if (c == ESC) escState = 3 else {
                    apc.write(c)
                    if (apc.size() > MAX_APC) { // Not one of ours; give up on it.
                        out.write(ESC); out.write('_'.code); apc.writeTo(out); apc.reset(); escState = 0
                    }
                }
                3 -> when (c) {
                    '\\'.code -> {
                        escState = 0
                        val body = apc.toByteArray()
                        apc.reset()
                        if (body.startsWith(marker)) {
                            flush()
                            handleRexMessage(String(body, marker.size, body.size - marker.size, Charsets.UTF_8))
                        } else {
                            out.write(ESC); out.write('_'.code); out.write(body); out.write(ESC); out.write('\\'.code)
                        }
                    }
                    // ESC inside a string aborts it and starts a new escape sequence.
                    '_'.code -> { apc.reset(); escState = 2 }
                    ESC -> { apc.reset(); escState = 1 }
                    else -> { apc.reset(); out.write(ESC); out.write(c); escState = 0 }
                }
            }
        }
        flush()
    }

    private fun handleRexMessage(json: String) {
        val msg = try { JSONObject(json) } catch (_: Exception) { return }
        when (msg.optString("type")) {
            "resize" -> {
                listener.onResize(msg.optInt("cols"), msg.optInt("rows"), msg.optInt("cell_width_px"), msg.optInt("cell_height_px"))
                if (phase == Phase.SKIPPING) beginSeed()
            }
            "theme" -> listener.onTheme(msg)
            "child_exited" -> listener.onChildExited(msg.optInt("exit_code"))
        }
    }

    /** Fetch the current screen as escape sequences, then go live. */
    private fun beginSeed() {
        phase = Phase.SEEDING
        val gen = generation
        seedJob = scope.launch {
            val vt = try {
                readFormat()
            } catch (e: Exception) {
                "\r\n[couldn't read screen: ${e.message}]\r\n"
            }
            synchronized(lock) {
                if (gen != generation || stopped) return@launch
                listener.onReset(vt)
                listener.onStatus(null)
                phase = Phase.LIVE
            }
        }
    }

    private suspend fun readFormat(): String {
        val args = JSONObject()
            .put("format", "vt")
            .put("max_bytes", 900_000)
            .put("extra_cursor", true)
            .put("extra_modes", true)
            .put("extra_style", true)
            .put("extra_scrolling_region", true)
            .put("extra_charsets", true)
            .put("extra_tabstops", true)
            .put("extra_keyboard", true)
        val first = control.callBlock(sessionId, blockId, "com.superlogical.terminal.format", args)
        val sb = StringBuilder(first.optString("content"))
        if (!first.has("id")) return sb.toString()
        // The rest is paged through blob_read.
        val id = first.getLong("id")
        var offset = sb.toString().toByteArray().size.toLong()
        while (true) {
            val part = control.callBlock(
                sessionId, blockId, "com.superlogical.terminal.blob_read",
                JSONObject().put("id", id).put("offset", offset).put("max_bytes", 900_000),
            )
            val data = part.optString("data")
            sb.append(data)
            offset += data.toByteArray().size
            if (part.optBoolean("eof") || data.isEmpty()) break
        }
        return sb.toString()
    }

    private fun end(reason: String) {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            ws = null
        }
        listener.onStatus(null)
        listener.onEnded(reason)
    }

    /** Start over with a fresh ticket; the new stream's snapshot replaces the screen. */
    private fun reconnect(why: String) {
        synchronized(lock) {
            if (stopped) return
            generation++
            ws = null
        }
        if (++retries > 5 || control.closedReason.value != null) {
            end("[disconnected: $why]")
            return
        }
        listener.onStatus("Reconnecting…")
        scope.launch {
            delay(500L * retries)
            connect()
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    companion object {
        const val SUBPROTOCOL = "rex.data.terminal.v1"
        private const val ESC = 0x1b
        private const val MAX_APC = 256 * 1024

        private val SNAPSHOT_MAGIC = "GHOSTSNP".toByteArray()
        private const val SNAPSHOT_ENVELOPE = 10 // magic + u16 version
        private const val RECORD_HEADER = 10
        private const val TAG_FINISH = 6
        private const val MAX_SNAPSHOT = 256 * 1024 * 1024
        private val REJECT_REASONS = mapOf(
            1 to "unknown or expired ticket",
            2 to "this client already streams this block",
            3 to "stream unavailable",
            4 to "limit reached",
            5 to "malformed handshake",
            6 to "server failure",
            7 to "wrong subprotocol",
        )
    }
}
