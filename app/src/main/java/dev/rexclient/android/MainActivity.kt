package dev.rexclient.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: RexViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { App(vm) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }
}

@Composable
fun App(vm: RexViewModel) {
    BackHandler(enabled = vm.screen != Screen.Connect) { vm.back() }
    Column(Modifier.fillMaxSize()) {
        vm.reconnecting?.let { ReconnectBanner(it, vm) }
        Box(Modifier.fillMaxWidth().weight(1f)) { Content(vm) }
    }
}

@Composable
private fun ReconnectBanner(reason: String, vm: RexViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Reconnecting… ($reason)",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = vm::retryNow) { Text("Retry") }
        TextButton(onClick = vm::disconnect) { Text("Disconnect") }
    }
}

@Composable
private fun Content(vm: RexViewModel) {
    when (val s = vm.screen) {
        Screen.Connect -> ConnectScreen(vm)
        Screen.Sessions -> SessionsScreen(vm)
        is Screen.Session -> SessionScreen(vm)
        is Screen.Terminal -> {
            val control = vm.control
            Column(Modifier.fillMaxSize()) {
                TopBar(title = s.block.label, subtitle = s.block.windowLabel, onBack = vm::back)
                if (control != null) {
                    // A new connection gets a fresh view and stream, which
                    // re-seeds the screen from the server.
                    key(control) {
                        TerminalScreen(control, s.sessionId, s.block.id, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
        else Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
private fun Status(vm: RexViewModel) {
    if (vm.busy) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text("Working…")
        }
    }
    vm.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ConnectScreen(vm: RexViewModel) {
    Column(
        Modifier.fillMaxSize().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Rex", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Connect to a Rex server listening on TCP — e.g. https://rex.<tailnet>.ts.net, " +
                "a tailnet/VPN address with port 7297, or 10.0.2.2:7297 from the emulator.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = vm.serverAddress,
            onValueChange = { vm.serverAddress = it },
            label = { Text("Server address") },
            placeholder = { Text("host:7297 or https://…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = vm::connect, enabled = !vm.busy && vm.serverAddress.isNotBlank()) { Text("Connect") }
        Status(vm)
    }
}

@Composable
private fun SessionsScreen(vm: RexViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Sessions", subtitle = vm.control?.baseUrl?.toString(), onBack = vm::disconnect) {
            TextButton(onClick = vm::refreshSessions) { Text("Refresh") }
            TextButton(onClick = vm::newSession) { Text("New") }
        }
        Status(vm)
        if (vm.sessions.isEmpty() && !vm.busy) {
            Text("No sessions. Tap New to start a shell.", Modifier.padding(16.dp))
        }
        LazyColumn {
            items(vm.sessions, key = { it.id }) { s ->
                ListRow(title = s.label.ifEmpty { s.id }, subtitle = s.id.removePrefix("session:").takeLast(6)) {
                    vm.openSession(s.id)
                }
            }
        }
    }
}

@Composable
private fun SessionScreen(vm: RexViewModel) {
    val view = vm.sessionView
    Column(Modifier.fillMaxSize()) {
        TopBar(title = view?.label ?: "Session", subtitle = "Pick a terminal", onBack = vm::back)
        Status(vm)
        val blocks = view?.blocks.orEmpty()
        LazyColumn {
            items(blocks, key = { it.id }) { b ->
                val active = b.windowId == view?.activeWindowId && b.focused
                ListRow(
                    title = b.label + if (active) "  •" else "",
                    subtitle = b.windowLabel + if (!b.isTerminal) "  (${b.creator})" else "",
                    enabled = b.isTerminal,
                ) { vm.openBlock(b) }
            }
        }
    }
}

@Composable
private fun ListRow(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    HorizontalDivider()
}
