package dev.rexclient.android

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// --- Palette ---------------------------------------------------------------

object RexColors {
    val Green = Color(0xff3ddc97)
    val Teal = Color(0xff2bc0e4)
    val Violet = Color(0xff8b7cf6)
    val Amber = Color(0xfff7b955)
    val Coral = Color(0xffff6b6b)
    val Pink = Color(0xfff472b6)
    val Sky = Color(0xff60a5fa)

    /** The brand gradient: dino green to teal to violet. */
    val brand = listOf(Green, Teal, Violet)
    fun brandBrush() = Brush.linearGradient(brand)

    /** Accents for avatars, picked by a stable hash. */
    val accents = listOf(Green, Teal, Violet, Amber, Pink, Sky, Coral)
    fun accentFor(key: String) = accents[abs(key.hashCode()) % accents.size]

    // The terminal chrome (keys bar) stays dark in both themes, like the terminal.
    val TermChrome = Color(0xff11151b)
    val Keycap = Color(0xff222831)
    val KeycapText = Color(0xffe3e8ef)
}

private val DarkColors = darkColorScheme(
    primary = RexColors.Green,
    onPrimary = Color(0xff00281a),
    primaryContainer = Color(0xff113d2c),
    onPrimaryContainer = Color(0xffa7f3d0),
    secondary = RexColors.Violet,
    onSecondary = Color(0xff1b1446),
    secondaryContainer = Color(0xff2a2456),
    onSecondaryContainer = Color(0xffdcd6ff),
    tertiary = RexColors.Amber,
    onTertiary = Color(0xff3a2800),
    background = Color(0xff0b0e13),
    onBackground = Color(0xffe6ebf2),
    surface = Color(0xff0b0e13),
    onSurface = Color(0xffe6ebf2),
    surfaceVariant = Color(0xff1a2029),
    onSurfaceVariant = Color(0xff97a3b4),
    surfaceContainerLowest = Color(0xff080a0e),
    surfaceContainerLow = Color(0xff11151b),
    surfaceContainer = Color(0xff151a21),
    surfaceContainerHigh = Color(0xff1b2129),
    surfaceContainerHighest = Color(0xff222932),
    outline = Color(0xff3a4350),
    outlineVariant = Color(0xff232a33),
    error = RexColors.Coral,
    onError = Color(0xff3b0909),
    errorContainer = Color(0xff3a1518),
    onErrorContainer = Color(0xffffc9c9),
)

private val LightColors = lightColorScheme(
    primary = Color(0xff0a8f5f),
    onPrimary = Color.White,
    primaryContainer = Color(0xffc9f5e2),
    onPrimaryContainer = Color(0xff00391f),
    secondary = Color(0xff6553e0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xffe6e1ff),
    onSecondaryContainer = Color(0xff1f1263),
    tertiary = Color(0xffb7791f),
    background = Color(0xfff5f7fa),
    onBackground = Color(0xff111821),
    surface = Color(0xfff5f7fa),
    onSurface = Color(0xff111821),
    surfaceVariant = Color(0xffe4e9f0),
    onSurfaceVariant = Color(0xff566273),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xfffbfcfd),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xffeef2f6),
    surfaceContainerHighest = Color(0xffe6ebf1),
    outline = Color(0xffc3cbd6),
    outlineVariant = Color(0xffe1e6ed),
    error = Color(0xffd23f3f),
    errorContainer = Color(0xffffe1e1),
    onErrorContainer = Color(0xff5c0b0b),
)

/** JetBrains Mono Nerd Font Mono, the terminal's font, for code-ish text in the UI. */
val RexMono = FontFamily(
    Font(R.font.jetbrains_mono_nf_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_nf_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_nf_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.jetbrains_mono_nf_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

@Composable
fun RexTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontFamily = RexMono),
    )
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = typography,
        content = content,
    )
}

// --- Icons -----------------------------------------------------------------

/**
 * Icon glyphs from the bundled Nerd Font (Material Design Icons and friends),
 * so the app needs no icon library.
 */
object Glyph {
    private fun g(cp: Int) = String(Character.toChars(cp))

    val Dino = g(0xF1362) // md-google_downasaur U+F1362
    val Back = g(0xF004D) // md-arrow_left
    val Plus = g(0xF0415) // md-plus
    val Refresh = g(0xF0450) // md-refresh
    val ChevronRight = g(0xF0142) // md-chevron_right
    val Server = g(0xF048B) // md-server
    val ServerNetwork = g(0xF048D) // md-server_network
    val LanConnect = g(0xF0318) // md-lan_connect
    val Logout = g(0xF0343) // md-logout
    val Console = g(0xF018D) // md-console
    val ConsoleLine = g(0xF07B7) // md-console_line
    val Tab = g(0xF04E9) // md-tab
    val TabPlus = g(0xF075C) // md-tab_plus
    val Folder = g(0xF0256) // md-folder_outline
    val Keyboard = g(0xF030C) // md-keyboard
    val KeyboardClose = g(0xF030F) // md-keyboard_close
    val Alert = g(0xF0028) // md-alert_circle
    val Sync = g(0xF04E6) // md-sync
    val Layers = g(0xF0F59) // md-layers_triple_outline
    val Bolt = g(0xF140B) // md-lightning_bolt
    val ArrowUp = g(0xF005D)
    val ArrowDown = g(0xF0045)
    val ArrowLeft = g(0xF004D)
    val ArrowRight = g(0xF0054)
    val Vim = g(0xE7C5) // dev-vim
    val Ssh = g(0xF08C0) // md-ssh
    val Git = g(0xF02A2) // md-git
    val Python = g(0xF0320)
    val Node = g(0xF0399)
    val Docker = g(0xF0868)
    val Rust = g(0xF1617)
    val Go = g(0xF07D3)
    val Monitor = g(0xF0A07) // md-monitor_dashboard
    val Sparkle = g(0xF0674) // md-creation
    val Database = g(0xF01BC)
}

private var cachedTypeface: Typeface? = null

/**
 * Draws a [Glyph] centered in a [size] box, scaled so its inked bounds fill
 * it. Measuring the ink (not the font's line box) keeps every icon optically
 * centered whatever the font's metrics.
 */
@Composable
fun NfIcon(glyph: String, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val context = LocalContext.current
    val paint = remember {
        val tf = cachedTypeface ?: context.resources.getFont(R.font.jetbrains_mono_nf_regular).also { cachedTypeface = it }
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = 100f }
    }
    val bounds = remember(glyph) { Rect().also { paint.textSize = 100f; paint.getTextBounds(glyph, 0, glyph.length, it) } }
    Canvas(modifier.size(size)) {
        if (bounds.isEmpty) return@Canvas
        val scale = minOf(this.size.width / bounds.width(), this.size.height / bounds.height()) * 0.92f
        paint.textSize = 100f * scale
        paint.color = tint.toArgb()
        val x = center.x - bounds.exactCenterX() * scale
        val y = center.y - bounds.exactCenterY() * scale
        drawIntoCanvas { it.nativeCanvas.drawText(glyph, x, y, paint) }
    }
}

/** A glyph and accent for what a terminal is running. */
fun programIcon(program: String?): Pair<String, Color> = when (program?.lowercase()) {
    "vim", "nvim", "vi", "view" -> Glyph.Vim to RexColors.Green
    "ssh", "mosh", "mosh-client", "et" -> Glyph.Ssh to RexColors.Sky
    "git", "lazygit", "tig", "gitui" -> Glyph.Git to RexColors.Coral
    "python", "python3", "ipython", "uv" -> Glyph.Python to RexColors.Amber
    "node", "npm", "pnpm", "yarn", "bun", "deno", "npx" -> Glyph.Node to RexColors.Green
    "docker", "podman" -> Glyph.Docker to RexColors.Sky
    "cargo", "rustc" -> Glyph.Rust to RexColors.Coral
    "go" -> Glyph.Go to RexColors.Teal
    "top", "htop", "btop", "btm", "glances" -> Glyph.Monitor to RexColors.Pink
    "claude", "codex", "aider" -> Glyph.Sparkle to RexColors.Violet
    "psql", "mysql", "sqlite3", "redis-cli" -> Glyph.Database to RexColors.Amber
    else -> Glyph.ConsoleLine to RexColors.Teal
}
