package dev.rexclient.android

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Glue between a [TerminalStream] (Rex -> screen), a [VtTerminal] drawn by a
 * [TerminalView], and terminal.write (keyboard -> Rex).
 */
private class TerminalController(
    private val control: RexControl,
    private val sessionId: String,
    private val blockId: String,
    private val view: TerminalView,
    /** Status overlay text, or null. Main thread only. */
    private val status: MutableState<String?>,
) : TerminalStream.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stream = TerminalStream(control, sessionId, blockId, scope, this)
    private val input = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile private var term: VtTerminal? = null
    @Volatile private var disposed = false

    // The grid size the server last announced, and the last theme it sent.
    private var gridCols = 80
    private var gridRows = 24
    private var gridCellW = 0
    private var gridCellH = 0
    private var theme: JSONObject? = null

    // Size requests, newest only. One coroutine sends them in order, since
    // concurrent block calls can land on the server in any order.
    private val sizeRequests = Channel<List<Int>>(Channel.CONFLATED)
    @Volatile private var owned = false
    private val reportSize = Runnable { sizeRequests.trySend(fit) }
    private var fit = listOf(0, 0, 0, 0)

    init {
        view.onInput = { input.trySend(it) }
        view.onFitSize = { cols, rows, cw, ch ->
            fit = listOf(cols, rows, cw, ch)
            main.removeCallbacks(reportSize)
            main.postDelayed(reportSize, 150)
        }
        // Block methods don't run in order on the server, so send writes one
        // at a time, batching whatever was typed while the last one was in flight.
        scope.launch {
            for (first in input) {
                var bytes = first
                while (true) bytes += input.tryReceive().getOrNull() ?: break
                runCatching {
                    control.callBlock(
                        sessionId, blockId, "com.superlogical.terminal.write",
                        JSONObject().put("data", String(bytes, Charsets.UTF_8)),
                    )
                }
            }
        }
        scope.launch {
            // A view for this block that was just closed may still be giving it
            // back; let that finish so it doesn't undo our claim.
            pendingDisown[blockId]?.join()
            var requested: List<Int>? = null
            for (size in sizeRequests) {
                val (cols, rows, cw, ch) = size
                if (cols <= 0 || rows <= 0 || size == requested) continue
                requested = size
                runCatching {
                    if (!owned) {
                        control.ownBlock(sessionId, blockId)
                        owned = true
                    }
                    control.callBlock(
                        sessionId, blockId, "com.superlogical.terminal.resize",
                        JSONObject().put("columns", cols).put("rows", rows)
                            .put("cell_width_px", cw).put("cell_height_px", ch),
                    )
                }
            }
        }
        stream.start()
        // Opening a terminal is usually so you can type into it.
        view.post { view.showKeyboard() }
    }

    fun dispose() {
        disposed = true
        main.removeCallbacks(reportSize)
        stream.stop()
        input.close()
        sizeRequests.close()
        scope.cancel()
        // Give the block back so other clients (e.g. Rex.app) size it again.
        if (owned) {
            val job = CoroutineScope(Dispatchers.IO).launch {
                runCatching { withTimeout(5_000) { control.disownBlock(sessionId, blockId) } }
            }
            pendingDisown[blockId] = job
            job.invokeOnCompletion { pendingDisown.remove(blockId, job) }
        }
        view.terminal = null
        term?.close()
        term = null
    }

    /** Swaps in a new terminal (from a new stream's snapshot or seed). */
    private fun install(t: VtTerminal) {
        if (disposed) return t.close()
        theme?.let { applyTheme(t, it) }
        val old = term
        term = t
        view.terminal = t
        old?.close()
    }

    // --- Stream -> screen (called on a background thread, in order) ---

    override fun onSnapshot(snapshot: ByteArray): Boolean {
        val t = VtTerminal.fromSnapshot(snapshot)
        if (t == null) {
            Log.w(TAG, "Couldn't decode the ${snapshot.size}-byte snapshot; seeding from terminal.format instead")
            return false
        }
        install(t)
        return true
    }

    override fun onReset(vt: String) {
        val t = VtTerminal.create(gridCols, gridRows) ?: return
        t.resize(gridCols, gridRows, gridCellW, gridCellH)
        t.write(vt.toByteArray())
        install(t)
    }

    override fun onOutput(bytes: ByteArray) {
        term?.write(bytes)
        view.redraw()
    }

    override fun onResize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        if (cols <= 0 || rows <= 0) return
        gridCols = cols; gridRows = rows; gridCellW = cellWidthPx; gridCellH = cellHeightPx
        term?.resize(cols, rows, cellWidthPx, cellHeightPx)
        view.redraw()
    }

    override fun onTheme(theme: JSONObject) {
        this.theme = theme
        term?.let { applyTheme(it, theme) }
        view.redraw()
    }

    override fun onChildExited(exitCode: Int) = notice("[process exited with code $exitCode]")
    override fun onStatus(status: String?) { main.post { this.status.value = status } }
    override fun onEnded(reason: String) {
        if (term == null) main.post { status.value = reason } else notice(reason)
    }

    private fun notice(text: String) {
        term?.write("\r\n\u001b[7m $text \u001b[0m\r\n".toByteArray())
        view.redraw()
    }

    companion object {
        private const val TAG = "rexvt"

        /** Disown calls still in flight, by block id. */
        private val pendingDisown = java.util.concurrent.ConcurrentHashMap<String, Job>()

        init {
            Log.i(TAG, "libghostty-vt ${GhosttyVt.buildInfo()}")
        }

        /** Applies a Rex `theme` message; each one replaces the previous theme. */
        fun applyTheme(t: VtTerminal, theme: JSONObject) {
            fun color(s: String?): Int = s?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: -1
            val palette = IntArray(256) { -1 }
            theme.optJSONArray("palette")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val (idx, c) = arr.optString(i).split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                    val n = idx.trim().toIntOrNull() ?: continue
                    if (n in 0..255) palette[n] = color(c.trim())
                }
            }
            t.setColors(
                color(theme.optString("foreground").ifEmpty { null }),
                color(theme.optString("background").ifEmpty { null }),
                color(theme.optString("cursor").ifEmpty { null }),
                palette,
            )
        }
    }
}

@Composable
fun TerminalScreen(control: RexControl, sessionId: String, blockId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(sessionId, blockId) {
        TerminalView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    val ctrl = remember(view) { mutableStateOf(false) }
    val status = remember(view) { mutableStateOf<String?>(null) }
    DisposableEffect(view) {
        val controller = TerminalController(control, sessionId, blockId, view, status)
        view.onCtrlConsumed = { ctrl.value = false }
        onDispose { controller.dispose() }
    }

    // Keep the last status so the pill stays readable while it fades out.
    var lastStatus by remember(view) { mutableStateOf("") }
    status.value?.let { lastStatus = it }
    Column(modifier.background(Color(0xff1d1f21))) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            androidx.compose.animation.AnimatedVisibility(
                visible = status.value != null,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            ) {
                val t = rememberInfiniteTransition(label = "status")
                val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0xe6161b22))
                        .border(1.dp, Color(0x33ffffff), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).alpha(pulse).clip(CircleShape).background(RexColors.Green))
                    Spacer(Modifier.size(8.dp))
                    Text(lastStatus, color = RexColors.KeycapText, fontSize = 12.sp, fontFamily = RexMono)
                }
            }
        }
        KeysBar(
            ctrl = ctrl.value,
            onKeyboard = { view.toggleKeyboard() },
            onCtrl = { ctrl.value = !ctrl.value; view.stickyCtrl = ctrl.value },
            onKey = { view.pressKey(it, 0, null) },
            onText = { view.typeText(it) },
        )
    }
}

/** Keys that soft keyboards lack, as keycaps. Ctrl is sticky: it applies to the next key. */
@Composable
private fun KeysBar(
    ctrl: Boolean,
    onKeyboard: () -> Unit,
    onCtrl: () -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RexColors.TermChrome)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Keycap(glyph = Glyph.Keyboard, accent = true, action = onKeyboard)
        Keycap("Esc") { onKey(KeyEvent.KEYCODE_ESCAPE) }
        Keycap("Tab") { onKey(KeyEvent.KEYCODE_TAB) }
        Keycap("Ctrl", on = ctrl, action = onCtrl)
        Keycap(glyph = Glyph.ArrowLeft) { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        Keycap(glyph = Glyph.ArrowUp) { onKey(KeyEvent.KEYCODE_DPAD_UP) }
        Keycap(glyph = Glyph.ArrowDown) { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        Keycap(glyph = Glyph.ArrowRight) { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        for (s in listOf("|", "~", "/", "-")) Keycap(s) { onText(s) }
    }
}

@Composable
private fun Keycap(
    label: String? = null,
    glyph: String? = null,
    on: Boolean = false,
    accent: Boolean = false,
    action: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "key",
    )
    val bg by animateColorAsState(
        when {
            on -> RexColors.Green
            pressed -> Color(0xff323a46)
            else -> RexColors.Keycap
        },
        tween(150),
        label = "keybg",
    )
    val fg by animateColorAsState(
        when {
            on -> Color(0xff06210f)
            accent -> RexColors.Green
            else -> RexColors.KeycapText
        },
        tween(150),
        label = "keyfg",
    )
    Box(
        Modifier
            .widthIn(min = 46.dp)
            .height(38.dp)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            // A lighter top edge, like a keycap catching the light.
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x26ffffff), Color.Transparent)), RoundedCornerShape(10.dp))
            .clickable(interactionSource = source, indication = null, onClick = action)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) NfIcon(glyph, fg, size = 18.dp)
        else Text(label.orEmpty(), color = fg, fontSize = 14.sp, fontFamily = RexMono, fontWeight = FontWeight.Medium)
    }
}
