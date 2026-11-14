package dev.rexclient.android

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Glue between a [TerminalStream] (Rex -> screen), the xterm.js page in a
 * WebView (rendering only), and terminal.write (keyboard -> Rex). Keyboard
 * input comes from [TermWebView], is routed through the page (so the sticky
 * Ctrl key and cursor-key mode apply), and arrives back here in [onInput].
 */
private class TerminalController(
    private val control: RexControl,
    private val sessionId: String,
    private val blockId: String,
    private val webView: TermWebView,
) : TerminalStream.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stream = TerminalStream(control, sessionId, blockId, scope, this)
    private val input = Channel<String>(Channel.UNLIMITED)

    // Output is coalesced and handed to the page at most once per frame.
    private val pendingOutput = ByteArrayOutputStream()
    private var flushPosted = false
    private var pageReady = false
    private val beforeReady = mutableListOf<String>()

    private var requestedSize: Pair<Int, Int>? = null
    private var owned = false

    init {
        webView.onText = { js("rex.type(${JSONObject.quote(it)})") }
        webView.onKey = { js("rex.key(${JSONObject.quote(it)})") }
        // Block methods don't run in order on the server, so send writes one
        // at a time, batching whatever was typed while the last one was in flight.
        scope.launch {
            for (first in input) {
                val sb = StringBuilder(first)
                while (true) sb.append(input.tryReceive().getOrNull() ?: break)
                runCatching {
                    control.callBlock(
                        sessionId, blockId, "com.superlogical.terminal.write",
                        JSONObject().put("data", sb.toString()),
                    )
                }
            }
        }
        stream.start()
    }

    fun dispose() {
        stream.stop()
        input.close()
        // Give the block back so other clients (e.g. Rex.app) size it again.
        CoroutineScope(Dispatchers.IO).launch {
            if (owned) runCatching { control.disownBlock(sessionId, blockId) }
        }
        scope.cancel()
    }

    private fun js(script: String) {
        main.post { if (pageReady) webView.evaluateJavascript(script, null) else beforeReady += script }
    }

    // --- JS -> Kotlin ---

    @JavascriptInterface
    fun onReady() {
        main.post {
            pageReady = true
            beforeReady.forEach { webView.evaluateJavascript(it, null) }
            beforeReady.clear()
            // Opening a terminal is usually so you can type into it.
            webView.showKeyboard()
        }
    }

    @JavascriptInterface
    fun onInput(data: String) {
        input.trySend(data)
    }

    @JavascriptInterface
    fun toggleKeyboard() {
        main.post { webView.toggleKeyboard() }
    }

    @JavascriptInterface
    fun onSize(cols: Int, rows: Int) {
        val size = cols to rows
        if (size == requestedSize) return
        requestedSize = size
        scope.launch {
            runCatching {
                if (!owned) {
                    control.ownBlock(sessionId, blockId)
                    owned = true
                }
                control.callBlock(
                    sessionId, blockId, "com.superlogical.terminal.resize",
                    JSONObject().put("columns", cols).put("rows", rows),
                )
            }
        }
    }

    // --- Stream -> page ---

    override fun onReset(vt: String) {
        synchronized(pendingOutput) { pendingOutput.reset() }
        js("rex.reset(${JSONObject.quote(vt)})")
    }

    override fun onOutput(bytes: ByteArray) {
        synchronized(pendingOutput) {
            pendingOutput.write(bytes)
            if (flushPosted) return
            flushPosted = true
        }
        main.postDelayed({
            val chunk = synchronized(pendingOutput) {
                flushPosted = false
                pendingOutput.toByteArray().also { pendingOutput.reset() }
            }
            if (chunk.isNotEmpty()) {
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                if (pageReady) webView.evaluateJavascript("rex.write('$b64')", null)
                else beforeReady += "rex.write('$b64')"
            }
        }, 8)
    }

    override fun onResize(cols: Int, rows: Int) { js("rex.resize($cols, $rows)") }
    override fun onTheme(theme: JSONObject) { js("rex.theme($theme)") }
    override fun onChildExited(exitCode: Int) { js("rex.notice(${JSONObject.quote("[process exited with code $exitCode]")})") }
    override fun onStatus(status: String?) { js("rex.status(${if (status == null) "null" else JSONObject.quote(status)})") }
    override fun onEnded(reason: String) { js("rex.notice(${JSONObject.quote(reason)})") }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen(control: RexControl, sessionId: String, blockId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val webView = remember(sessionId, blockId) {
        TermWebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
        }
    }
    DisposableEffect(webView) {
        val controller = TerminalController(control, sessionId, blockId, webView)
        webView.addJavascriptInterface(controller, "RexBridge")
        webView.loadUrl("file:///android_asset/terminal/index.html")
        onDispose {
            controller.dispose()
            webView.removeJavascriptInterface("RexBridge")
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = modifier)
}
