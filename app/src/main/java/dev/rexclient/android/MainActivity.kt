package dev.rexclient.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: RexViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RexTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

/** How deep a screen is, so navigating in slides left and going back slides right. */
private val Screen.depth
    get() = when (this) {
        Screen.Connect -> 0
        Screen.Sessions -> 1
        is Screen.Session -> 2
        is Screen.Terminal -> 3
    }

private val Screen.key: Any
    get() = when (this) {
        is Screen.Terminal -> "t:" + block.id
        is Screen.Session -> "s:$sessionId"
        else -> depth
    }

@Composable
fun App(vm: RexViewModel) {
    BackHandler(enabled = vm.screen != Screen.Connect) { vm.back() }
    Column(Modifier.fillMaxSize()) {
        ReconnectBanner(vm)
        AnimatedContent(
            targetState = vm.screen,
            contentKey = { it.key },
            transitionSpec = {
                val dir = if (targetState.depth >= initialState.depth) 1 else -1
                (slideInHorizontally(tween(340, easing = EaseOutCubic)) { it * dir / 3 } +
                    fadeIn(tween(240, delayMillis = 60)))
                    .togetherWith(
                        slideOutHorizontally(tween(340, easing = EaseOutCubic)) { -it * dir / 3 } +
                            fadeOut(tween(180)),
                    )
                    .using(SizeTransform(clip = false))
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = "screen",
        ) { s -> Content(vm, s) }
    }
}

@Composable
private fun Content(vm: RexViewModel, s: Screen) {
    when (s) {
        Screen.Connect -> ConnectScreen(vm)
        Screen.Sessions -> SessionsScreen(vm)
        is Screen.Session -> SessionScreen(vm)
        is Screen.Terminal -> TerminalPage(vm, s)
    }
}

// --- Shared pieces ---------------------------------------------------------

/** Fades and lifts content in once, [index] steps after the screen appears. */
@Composable
private fun Modifier.riseIn(index: Int = 0): Modifier {
    var shown by rememberSaveable { mutableStateOf(false) }
    val p = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (shown) return@LaunchedEffect
        delay(minOf(index, 10) * 45L)
        p.animateTo(1f, tween(420, easing = EaseOutCubic))
        shown = true
    }
    return graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 28.dp.toPx()
    }
}

/** Shrinks a little while pressed. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    ).value
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)?,
    backGlyph: String = Glyph.Back,
    live: Boolean? = null,
    icon: Pair<String, Color>? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { NfIcon(backGlyph, MaterialTheme.colorScheme.onSurface, size = 22.dp) }
        } else {
            Spacer(Modifier.size(16.dp))
        }
        if (icon != null) {
            GlyphTile(icon.first, icon.second, size = 34.dp, corner = 10.dp)
            Spacer(Modifier.size(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live != null) {
                        StatusDot(if (live) MaterialTheme.colorScheme.primary else RexColors.Amber, pulse = live)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        actions()
    }
}

/** A thin brand-colored progress line that fades in while busy, without moving the layout. */
@Composable
private fun BusyBar(busy: Boolean) {
    val a by animateFloatAsState(if (busy) 1f else 0f, tween(250), label = "busy")
    Box(Modifier.fillMaxWidth().height(2.dp).alpha(a)) {
        if (a > 0f) {
            LinearProgressIndicator(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 1f - a))
}

/** A small dot with a halo that breathes while [pulse] is set. */
@Composable
private fun StatusDot(color: Color, pulse: Boolean = true, size: Dp = 7.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    Canvas(Modifier.size(size * 2)) {
        val r = size.toPx() / 2
        if (pulse) {
            drawCircle(color.copy(alpha = 0.45f * (1f - phase)), radius = r * (1f + 1.6f * phase))
        }
        drawCircle(color, radius = r)
    }
}

@Composable
private fun GlyphTile(glyph: String, accent: Color, size: Dp = 44.dp, corner: Dp = 14.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) { NfIcon(glyph, accent, size = size * 0.52f) }
}

@Composable
private fun LetterTile(text: String, accent: Color, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.10f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.take(1).uppercase(),
            fontFamily = RexMono,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = accent,
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
        color = color,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ErrorCard(vm: RexViewModel, modifier: Modifier = Modifier) {
    // Keep the last message so it stays readable while the card animates away.
    var last by remember { mutableStateOf("") }
    vm.error?.let { last = it }
    AnimatedVisibility(
        visible = vm.error != null,
        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable { vm.error = null }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NfIcon(Glyph.Alert, MaterialTheme.colorScheme.error, size = 20.dp)
            Spacer(Modifier.size(12.dp))
            Text(
                last,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EmptyState(glyph: String, title: String, body: String, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "float")
    val y by t.animateFloat(-5f, 5f, infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse), label = "y")
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.graphicsLayer { translationY = y.dp.toPx() }) {
            GlyphTile(glyph, MaterialTheme.colorScheme.primary, size = 76.dp, corner = 24.dp)
        }
        Spacer(Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Fab(text: String, glyph: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = { if (enabled) onClick() },
        icon = { NfIcon(glyph, MaterialTheme.colorScheme.onPrimary, size = 20.dp) },
        text = { Text(text, fontWeight = FontWeight.SemiBold) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.padding(20.dp).alpha(if (enabled) 1f else 0.5f).riseIn(4),
    )
}

// --- Reconnecting ----------------------------------------------------------

@Composable
private fun ReconnectBanner(vm: RexViewModel) {
    var last by remember { mutableStateOf("") }
    vm.reconnecting?.let { last = it }
    AnimatedVisibility(
        visible = vm.reconnecting != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val t = rememberInfiniteTransition(label = "spin")
        val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "angle")
        Row(
            Modifier
                .fillMaxWidth()
                .background(RexColors.Amber.copy(alpha = 0.14f))
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NfIcon(Glyph.Sync, RexColors.Amber, Modifier.rotate(-angle), size = 18.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Reconnecting…", style = MaterialTheme.typography.labelLarge, color = RexColors.Amber)
                Text(
                    last,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = vm::retryNow) { Text("Retry") }
            TextButton(onClick = vm::disconnect) { Text("Disconnect", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// --- Connect ---------------------------------------------------------------

@Composable
private fun ConnectScreen(vm: RexViewModel) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        AuroraBackground()
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Logo(Modifier.riseIn(0))
            Spacer(Modifier.size(20.dp))
            Text(
                "rex",
                style = TextStyle(
                    brush = RexColors.brandBrush(),
                    fontFamily = RexMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp,
                    letterSpacing = (-1).sp,
                ),
                modifier = Modifier.riseIn(1),
            )
            Text(
                "Your terminals, in your pocket.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.riseIn(2),
            )
            Spacer(Modifier.size(36.dp))
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .riseIn(3)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.surfaceContainer.copy(alpha = 0.85f))
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "SERVER",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
                    color = colors.primary,
                )
                OutlinedTextField(
                    value = vm.serverAddress,
                    onValueChange = { vm.serverAddress = it },
                    placeholder = { Text("host:7297 or https://…", fontFamily = RexMono) },
                    leadingIcon = { NfIcon(Glyph.Server, colors.onSurfaceVariant, size = 20.dp) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = RexMono, fontSize = 15.sp),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outline,
                        focusedContainerColor = colors.surfaceContainerLow,
                        unfocusedContainerColor = colors.surfaceContainerLow,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.connect() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                GradientButton(
                    text = "Connect",
                    busyText = "Connecting…",
                    glyph = Glyph.LanConnect,
                    busy = vm.busy,
                    enabled = !vm.busy && vm.serverAddress.isNotBlank(),
                    onClick = vm::connect,
                )
                Text(
                    "Reach a Rex server over TCP: https://rex.<tailnet>.ts.net, a VPN address on port 7297, " +
                        "or 10.0.2.2:7297 from the emulator.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            ErrorCard(vm, Modifier.widthIn(max = 512.dp))
        }
    }
}

/** Slowly drifting blobs of the brand colors behind the connect screen. */
@Composable
private fun AuroraBackground() {
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = EaseInOutSine), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(13000, easing = EaseInOutSine), RepeatMode.Reverse), label = "b")
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val strength = if (dark) 0.22f else 0.16f
    Canvas(Modifier.fillMaxSize()) {
        fun blob(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = strength), Color.Transparent), Offset(cx, cy), r),
            radius = r,
            center = Offset(cx, cy),
        )
        val w = size.width
        val h = size.height
        blob(RexColors.Green, w * (0.15f + 0.25f * a), h * (0.12f + 0.06f * b), w * 0.75f)
        blob(RexColors.Violet, w * (0.95f - 0.25f * b), h * (0.30f + 0.08f * a), w * 0.70f)
        blob(RexColors.Teal, w * (0.40f + 0.20f * b), h * (0.92f - 0.05f * a), w * 0.65f)
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** The dino in a ring of the brand gradient that slowly turns, bobbing in on first show. */
@Composable
private fun Logo(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "logo")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "ring")
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier.size(112.dp).scale(pop.value), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            rotate(angle) {
                drawCircle(
                    Brush.sweepGradient(RexColors.brand + RexColors.brand.first()),
                    radius = size.minDimension / 2 - stroke,
                    style = Stroke(stroke),
                )
            }
            drawCircle(bg, radius = size.minDimension / 2 - stroke * 2.6f)
        }
        NfIcon(Glyph.Dino, RexColors.Green, size = 56.dp)
    }
}

@Composable
private fun GradientButton(
    text: String,
    busyText: String,
    glyph: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val ink = Color(0xff06210f)
    val alpha by animateFloatAsState(if (enabled || busy) 1f else 0.45f, label = "enabled")
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .scale(pressScale(source))
            .alpha(alpha)
            .clip(RoundedCornerShape(16.dp))
            .background(RexColors.brandBrush())
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            busy,
            transitionSpec = {
                (fadeIn() + slideInVertically { it / 2 }).togetherWith(fadeOut() + slideOutVertically { -it / 2 })
            },
            label = "busy",
        ) { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (b) CircularProgressIndicator(Modifier.size(18.dp), color = ink, strokeWidth = 2.dp)
                else NfIcon(glyph, ink, size = 20.dp)
                Spacer(Modifier.size(10.dp))
                Text(if (b) busyText else text, color = ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// --- Sessions --------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsScreen(vm: RexViewModel) {
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(vm.busy) { if (!vm.busy) pulled = false }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = "Sessions",
                subtitle = vm.control?.baseUrl?.host ?: "offline",
                onBack = vm::disconnect,
                backGlyph = Glyph.Logout,
                live = vm.control != null,
            ) {
                SpinningRefresh(spinning = vm.busy, onClick = vm::refreshSessions)
            }
            BusyBar(vm.busy && !pulled)
            ErrorCard(vm)
            PullToRefreshBox(
                isRefreshing = pulled,
                onRefresh = { pulled = true; vm.refreshSessions() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (vm.sessions.isEmpty() && !vm.busy) {
                        item(key = "empty") {
                            EmptyState(
                                Glyph.Layers,
                                "No sessions yet",
                                "Start a shell and it'll show up here.",
                                Modifier.fillMaxWidth().riseIn().animateItem(),
                            )
                        }
                    }
                    items(vm.sessions, key = { it.id }) { s ->
                        val index = vm.sessions.indexOf(s)
                        SessionCard(s, Modifier.animateItem().riseIn(index)) { vm.openSession(s.id) }
                    }
                }
            }
        }
        Fab("New session", Glyph.Plus, enabled = !vm.busy, onClick = vm::newSession, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun SpinningRefresh(spinning: Boolean, onClick: () -> Unit) {
    val turn = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (spinning) {
            while (true) turn.animateTo(turn.value + 360f, tween(800, easing = LinearEasing))
        } else {
            // Finish the turn so the icon comes to rest upright.
            val rest = (kotlin.math.ceil(turn.value / 360f) * 360f)
            turn.animateTo(rest, tween(300, easing = EaseOutCubic))
        }
    }
    IconButton(onClick = onClick) {
        NfIcon(Glyph.Refresh, MaterialTheme.colorScheme.onSurface, Modifier.rotate(turn.value), size = 22.dp)
    }
}

@Composable
private fun CardSurface(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, border),
            interactionSource = source,
            modifier = modifier.fillMaxWidth().scale(pressScale(source)),
            content = content,
        )
    } else {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, border),
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
private fun SessionCard(s: SessionSummary, modifier: Modifier, onClick: () -> Unit) {
    val name = s.label.ifEmpty { s.id }
    CardSurface(onClick = onClick, modifier = modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LetterTile(name, RexColors.accentFor(s.id))
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "#" + s.id.removePrefix("session:").takeLast(6),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NfIcon(Glyph.ChevronRight, MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
        }
    }
}

// --- One session -----------------------------------------------------------

@Composable
private fun SessionScreen(vm: RexViewModel) {
    val view = vm.sessionView
    // One group per tab, in Rex.app's tab order. Splits within a tab are
    // listed one after another; the app opens one terminal at a time.
    val tabs = view?.blocks.orEmpty().groupBy { it.windowId }
    val terminals = view?.blocks.orEmpty().count { it.isTerminal }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = view?.label?.ifEmpty { null } ?: "Session",
                subtitle = if (view == null) "loading…" else
                    "${tabs.size} ${if (tabs.size == 1) "tab" else "tabs"} · $terminals ${if (terminals == 1) "terminal" else "terminals"}",
                onBack = vm::back,
                live = vm.control != null,
            )
            BusyBar(vm.busy)
            ErrorCard(vm)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                var index = 0
                tabs.forEach { (windowId, blocks) ->
                    val i = index++
                    item(key = windowId) {
                        TabCard(
                            blocks = blocks,
                            active = windowId == view?.activeWindowId,
                            vm = vm,
                            modifier = Modifier.animateItem().riseIn(i),
                        )
                    }
                }
            }
        }
        Fab("New tab", Glyph.TabPlus, enabled = !vm.busy && view != null, onClick = vm::newTab, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun TabCard(blocks: List<BlockInfo>, active: Boolean, vm: RexViewModel, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    CardSurface(
        onClick = null,
        modifier = modifier,
        border = if (active) colors.primary.copy(alpha = 0.45f) else colors.outlineVariant,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NfIcon(Glyph.Tab, if (active) colors.primary else colors.onSurfaceVariant, size = 16.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    blocks.first().tabName(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) Pill("ACTIVE", colors.primary)
            }
            blocks.forEachIndexed { i, b ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = colors.outlineVariant)
                key(b.id) { BlockRow(b, vm.blockDetails[b.id], showFocus = b.focused && blocks.size > 1) { vm.openBlock(b) } }
            }
            Spacer(Modifier.size(6.dp))
        }
    }
}

@Composable
private fun BlockRow(b: BlockInfo, details: BlockDetails?, showFocus: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (glyph, accent) = programIcon(details?.takeUnless { it.atShell }?.program)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = b.isTerminal, onClick = onClick)
            .alpha(if (b.isTerminal) 1f else 0.5f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphTile(glyph, accent, size = 42.dp, corner = 12.dp)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    b.displayTitle(details),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showFocus) {
                    Spacer(Modifier.size(6.dp))
                    StatusDot(colors.secondary, pulse = false, size = 6.dp)
                }
            }
            val sub = b.displaySubtitle(details)
            if (sub.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NfIcon(Glyph.Folder, colors.onSurfaceVariant, size = 12.dp)
                    Spacer(Modifier.size(5.dp))
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        NfIcon(Glyph.ChevronRight, colors.onSurfaceVariant, size = 18.dp)
    }
}

// --- Terminal --------------------------------------------------------------

@Composable
private fun TerminalPage(vm: RexViewModel, s: Screen.Terminal) {
    val control = vm.control
    val block = vm.liveBlock(s.block)
    val details = vm.blockDetails[block.id]
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = block.displayTitle(details),
            subtitle = listOfNotNull(block.tabName(), block.displaySubtitle(details).ifEmpty { null }).joinToString("  ·  "),
            onBack = vm::back,
            icon = programIcon(details?.takeUnless { it.atShell }?.program),
        ) {
            IconButton(onClick = vm::newTab, enabled = !vm.busy && control != null) {
                NfIcon(Glyph.TabPlus, MaterialTheme.colorScheme.primary.copy(alpha = if (!vm.busy && control != null) 1f else 0.4f))
            }
        }
        BusyBar(vm.busy)
        if (control != null) {
            // A new connection gets a fresh view and stream, which
            // re-seeds the screen from the server.
            key(control) {
                TerminalScreen(control, s.sessionId, s.block.id, Modifier.fillMaxWidth().weight(1f))
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xff1d1f21)))
        }
    }
}
