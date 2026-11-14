package dev.rexclient.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.rexclient.android.GhosttyVt.F_BOLD
import dev.rexclient.android.GhosttyVt.F_FAINT
import dev.rexclient.android.GhosttyVt.F_INVERSE
import dev.rexclient.android.GhosttyVt.F_INVISIBLE
import dev.rexclient.android.GhosttyVt.F_ITALIC
import dev.rexclient.android.GhosttyVt.F_OVERLINE
import dev.rexclient.android.GhosttyVt.F_STRIKE
import dev.rexclient.android.GhosttyVt.F_UNDERLINE_SHIFT
import dev.rexclient.android.GhosttyVt.F_WIDE_SHIFT
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a [VtTerminal] and owns keyboard input for it.
 *
 * The grid size is the server's (it follows the stream's `resize` messages), so
 * it can differ from what fits here; a grid that's too big is scaled down to
 * fit. What would fit is reported through [onFitSize].
 *
 * Keys are encoded by Ghostty for the terminal's current modes and handed to
 * [onInput]. Soft keyboards get a visible-password input connection so they
 * commit each character instead of composing words.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context) : View(context) {
    /** Bytes to send to the program. */
    var onInput: (ByteArray) -> Unit = {}
    /** Columns, rows, and cell width and height in pixels that fit this view. */
    var onFitSize: (cols: Int, rows: Int, cellW: Int, cellH: Int) -> Unit = { _, _, _, _ -> }
    /** Called when the sticky Ctrl key is used up. */
    var onCtrlConsumed: () -> Unit = {}

    /** The sticky Ctrl key on the keys bar: applies to the next key typed. */
    var stickyCtrl = false

    @Volatile var terminal: VtTerminal? = null
        set(value) { field = value; postInvalidateOnAnimation() }

    private val density = resources.displayMetrics.density
    private val padPx = (2 * density).roundToInt()

    private val regular = Typeface.MONOSPACE
    private val bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val italic = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private val boldItalic = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = regular
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val fillPaint = Paint()
    private val strokePaint = Paint().apply { style = Paint.Style.STROKE }

    // Cell size in pixels, and the baseline offset within a cell.
    private val cellW: Float
    private val cellH: Float
    private val baseline: Float

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        val fm = textPaint.fontMetrics
        cellW = ceil(textPaint.measureText("M"))
        cellH = ceil(fm.descent - fm.ascent)
        baseline = -fm.ascent
        strokePaint.strokeWidth = maxOf(1f, density)
    }

    // --- Size ---

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cols = floor((w - padPx) / cellW).toInt()
        val rows = floor((h - padPx) / cellH).toInt()
        if (cols > 0 && rows > 0) onFitSize(cols, rows, cellW.toInt(), cellH.toInt())
    }

    // --- Drawing ---

    private val frame = VtTerminal.Frame()
    private val run = StringBuilder()
    private var lastBg = 0xff1d1f21.toInt()

    /** The background color, for filling around the grid. */
    val background get() = lastBg

    fun redraw() = postInvalidateOnAnimation()

    override fun onDraw(canvas: Canvas) {
        // Compose doesn't clip hosted views, and drawColor fills the whole clip.
        canvas.clipRect(0, 0, width, height)
        val term = terminal
        if (term == null || !term.frame(frame)) {
            canvas.drawColor(lastBg)
            return
        }
        val m = frame.meta
        val cols = frame.cols
        val rows = frame.rows
        val defFg = 0xff000000.toInt() or m[GhosttyVt.M_FG]
        val defBg = 0xff000000.toInt() or m[GhosttyVt.M_BG]
        lastBg = defBg
        canvas.drawColor(defBg)
        if (cols == 0 || rows == 0) return

        // Scale a grid that's bigger than the view down to fit.
        val avail = width - padPx.toFloat()
        val availH = height - padPx.toFloat()
        val scale = min(1f, min(avail / (cols * cellW), availH / (rows * cellH)))
        canvas.save()
        canvas.translate(padPx.toFloat(), padPx.toFloat())
        if (scale < 1f) canvas.scale(scale, scale)

        val cells = frame.cells
        // Backgrounds first, in runs of one color.
        for (y in 0 until rows) {
            var x = 0
            while (x < cols) {
                val bg = cellBg(cells, (y * cols + x) * 4, defFg, defBg)
                var end = x + 1
                while (end < cols && cellBg(cells, (y * cols + end) * 4, defFg, defBg) == bg) end++
                if (bg != defBg) {
                    fillPaint.color = bg
                    canvas.drawRect(x * cellW, y * cellH, end * cellW, (y + 1) * cellH, fillPaint)
                }
                x = end
            }
        }

        val curVisible = m[GhosttyVt.M_CURSOR_VISIBLE] != 0
        val curX = m[GhosttyVt.M_CURSOR_X]
        val curY = m[GhosttyVt.M_CURSOR_Y]
        val curStyle = if (hasFocus()) m[GhosttyVt.M_CURSOR_STYLE] else GhosttyVt.CURSOR_BLOCK_HOLLOW
        val curColor = if (m[GhosttyVt.M_CURSOR_COLOR] >= 0) 0xff000000.toInt() or m[GhosttyVt.M_CURSOR_COLOR] else defFg
        val curWide = curVisible && curY in 0 until rows && curX in 0 until cols &&
            (cells[(curY * cols + curX) * 4 + 3] shr F_WIDE_SHIFT and 3) == GhosttyVt.WIDE_WIDE
        if (curVisible && curStyle == GhosttyVt.CURSOR_BLOCK) {
            fillPaint.color = curColor
            canvas.drawRect(curX * cellW, curY * cellH, (curX + if (curWide) 2 else 1) * cellW, (curY + 1) * cellH, fillPaint)
        }

        // Then text. Runs of plain ASCII in one style are drawn together; any
        // other cell is drawn on its own, so glyphs that aren't exactly one
        // cell wide can't push the rest of the row out of alignment.
        for (y in 0 until rows) {
            var x = 0
            while (x < cols) {
                val i = (y * cols + x) * 4
                val cp = cells[i]
                val flags = cells[i + 3]
                val blockCursorHere = curVisible && curStyle == GhosttyVt.CURSOR_BLOCK && y == curY && x == curX
                var fg = cellFg(cells, i, defFg, defBg)
                if (blockCursorHere) fg = cellBg(cells, i, defFg, defBg)
                if (cp == 0 || cp == ' '.code || flags and F_INVISIBLE != 0) {
                    decorate(canvas, flags, fg, x, x + 1, y)
                    x++
                    continue
                }
                setStyle(flags, fg)
                if (cp in 0x21..0x7e && !blockCursorHere) {
                    run.setLength(0)
                    var end = x
                    while (end < cols) {
                        val j = (y * cols + end) * 4
                        val c = cells[j]
                        if (c !in 0x20..0x7e || cells[j + 3] != flags ||
                            cellFg(cells, j, defFg, defBg) != fg ||
                            (curVisible && curStyle == GhosttyVt.CURSOR_BLOCK && y == curY && end == curX)
                        ) break
                        run.append(c.toChar())
                        end++
                    }
                    canvas.drawText(run, 0, run.length, x * cellW, y * cellH + baseline, textPaint)
                    decorate(canvas, flags, fg, x, end, y)
                    x = end
                    continue
                }
                val text = if (cp < 0) frame.extras?.getOrNull(-cp - 1) ?: "" else String(Character.toChars(cp))
                val wide = (flags shr F_WIDE_SHIFT and 3) == GhosttyVt.WIDE_WIDE
                val span = if (wide) 2 else 1
                val w = textPaint.measureText(text)
                val room = span * cellW
                if (w > room * 1.05f) {
                    // Squeeze glyphs the font draws wider than their cells.
                    canvas.save()
                    canvas.scale(room / w, 1f, x * cellW, 0f)
                    canvas.drawText(text, x * cellW, y * cellH + baseline, textPaint)
                    canvas.restore()
                } else {
                    canvas.drawText(text, x * cellW + (room - w) / 2, y * cellH + baseline, textPaint)
                }
                decorate(canvas, flags, fg, x, x + span, y)
                x += span
            }
        }

        if (curVisible && curStyle != GhosttyVt.CURSOR_BLOCK && curY in 0 until rows) {
            val l = curX * cellW
            val r = (curX + if (curWide) 2 else 1) * cellW
            val t = curY * cellH
            val b = (curY + 1) * cellH
            fillPaint.color = curColor
            strokePaint.color = curColor
            when (curStyle) {
                GhosttyVt.CURSOR_BAR -> canvas.drawRect(l, t, l + maxOf(2f, density * 1.5f), b, fillPaint)
                GhosttyVt.CURSOR_UNDERLINE -> canvas.drawRect(l, b - maxOf(2f, density * 1.5f), r, b, fillPaint)
                else -> canvas.drawRect(l + 0.5f, t + 0.5f, r - 0.5f, b - 0.5f, strokePaint)
            }
        }

        // A scroll position marker while looking at history.
        val total = m[GhosttyVt.M_SCROLL_TOTAL]
        val len = m[GhosttyVt.M_SCROLL_LEN]
        if (total > len && m[GhosttyVt.M_SCROLL_OFFSET] + len < total) {
            val gridH = rows * cellH
            val top = gridH * m[GhosttyVt.M_SCROLL_OFFSET] / total
            val h = maxOf(gridH * len / total, 8 * density)
            fillPaint.color = (defFg and 0x00ffffff) or 0x80000000.toInt()
            canvas.drawRect(cols * cellW - 3 * density, top, cols * cellW, top + h, fillPaint)
        }
        canvas.restore()
    }

    private fun cellFg(cells: IntArray, i: Int, defFg: Int, defBg: Int): Int {
        val inverse = cells[i + 3] and F_INVERSE != 0
        val c = if (inverse) cells[i + 2] else cells[i + 1]
        return if (c >= 0) 0xff000000.toInt() or c else if (inverse) defBg else defFg
    }

    private fun cellBg(cells: IntArray, i: Int, defFg: Int, defBg: Int): Int {
        val inverse = cells[i + 3] and F_INVERSE != 0
        val c = if (inverse) cells[i + 1] else cells[i + 2]
        return if (c >= 0) 0xff000000.toInt() or c else if (inverse) defFg else defBg
    }

    private fun setStyle(flags: Int, fg: Int) {
        val b = flags and F_BOLD != 0
        val it = flags and F_ITALIC != 0
        textPaint.typeface = when {
            b && it -> boldItalic
            b -> bold
            it -> italic
            else -> regular
        }
        textPaint.color = if (flags and F_FAINT != 0) (fg and 0x00ffffff) or 0x80000000.toInt() else fg
    }

    private fun decorate(canvas: Canvas, flags: Int, fg: Int, x0: Int, x1: Int, y: Int) {
        val underline = flags shr F_UNDERLINE_SHIFT and 7
        if (underline == 0 && flags and (F_STRIKE or F_OVERLINE) == 0) return
        fillPaint.color = fg
        val l = x0 * cellW
        val r = x1 * cellW
        val t = y * cellH
        val th = maxOf(1f, density)
        if (underline != 0) {
            val uy = t + baseline + 2 * density
            canvas.drawRect(l, uy, r, uy + th, fillPaint)
            if (underline == 2) canvas.drawRect(l, uy + 2 * th, r, uy + 3 * th, fillPaint) // double
        }
        if (flags and F_STRIKE != 0) canvas.drawRect(l, t + cellH / 2, r, t + cellH / 2 + th, fillPaint)
        if (flags and F_OVERLINE != 0) canvas.drawRect(l, t, r, t + th, fillPaint)
    }

    // --- Touch: tap shows the keyboard, vertical drag scrolls ---

    private var scrollRemainder = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scrollRemainder = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollRemainder += dy
            val lines = (scrollRemainder / cellH).toInt()
            if (lines == 0) return true
            scrollRemainder -= lines * cellH
            scrollLines(lines)
            return true
        }
    })

    /** Scrolls history, or sends arrow keys to full-screen programs (which have none). */
    private fun scrollLines(lines: Int) {
        val term = terminal ?: return
        if (frame.meta[GhosttyVt.M_ALT_SCREEN] != 0) {
            val key = if (lines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
            repeat(minOf(kotlin.math.abs(lines), 10)) { term.encodeKey(key, 0, null)?.let(onInput) }
        } else {
            term.scroll(lines)
            redraw()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    // --- Keyboard ---

    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private val isKeyboardVisible: Boolean
        get() = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /**
     * Shows the soft keyboard. Right after focus moves to this view the IME may
     * not be bound to it yet, so showSoftInput can silently do nothing; ask the
     * window's insets controller too, and retry once after a moment.
     */
    fun showKeyboard() {
        if (!hasFocus()) requestFocus()
        post {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowInsetsController?.show(WindowInsets.Type.ime())
            }
        }
        postDelayed({
            if (!isKeyboardVisible) {
                requestFocus()
                imm.restartInput(this)
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 250)
    }

    fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    fun toggleKeyboard() {
        if (isKeyboardVisible) hideKeyboard() else showKeyboard()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        redraw() // Hollow cursor when unfocused.
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password text makes keyboards commit each character instead
        // of composing words, and turns off autocorrect and suggestions.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return TermInputConnection()
    }

    private inner class TermInputConnection : BaseInputConnection(this, false) {
        private var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            typeText(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Shown only once committed; fine for a barebones client.
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) typeText(composing)
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isNotEmpty()) {
                composing = composing.dropLast(beforeLength)
            } else {
                repeat(beforeLength) { pressKey(KeyEvent.KEYCODE_DEL, 0, null) }
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            dispatchKeyEvent(event)
            return true
        }
    }

    /** Text from a soft keyboard (or the keys bar). Keyboards send Enter as "\n". */
    fun typeText(s: String) {
        if (s.isEmpty()) return
        if (stickyCtrl) {
            // Ctrl applies to the first character only.
            val first = String(Character.toChars(s.codePointAt(0)))
            consumeCtrl()
            if (first == "\n") pressKey(KeyEvent.KEYCODE_ENTER, GhosttyVt.MODS_CTRL, null)
            else pressKey(0, GhosttyVt.MODS_CTRL, first)
            typeText(s.substring(first.length))
            return
        }
        var start = 0
        for (i in s.indices) {
            if (s[i] == '\n') {
                if (i > start) send(s.substring(start, i).toByteArray())
                pressKey(KeyEvent.KEYCODE_ENTER, 0, null)
                start = i + 1
            }
        }
        if (start < s.length) send(s.substring(start).toByteArray())
    }

    /**
     * Sends a key through Ghostty's encoder. [androidKey] is a KeyEvent keycode, or 0
     * to derive the key from [text]; [mods] uses GhosttyVt.MODS_* bits.
     */
    fun pressKey(androidKey: Int, mods: Int, text: String?, unshifted: Int = 0) {
        var m = mods
        if (stickyCtrl) {
            m = m or GhosttyVt.MODS_CTRL
            consumeCtrl()
        }
        val term = terminal
        val bytes = term?.encodeKey(androidKey, m, text, unshifted)
        when {
            bytes != null -> send(bytes)
            text != null && m and (GhosttyVt.MODS_CTRL or GhosttyVt.MODS_ALT) == 0 -> send(text.toByteArray())
        }
    }

    private fun consumeCtrl() {
        if (!stickyCtrl) return
        stickyCtrl = false
        onCtrlConsumed()
    }

    private fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Typing jumps back to the live screen.
        terminal?.let { if (frame.meta[GhosttyVt.M_SCROLL_OFFSET] + frame.meta[GhosttyVt.M_SCROLL_LEN] < frame.meta[GhosttyVt.M_SCROLL_TOTAL]) { it.scroll(0); redraw() } }
        onInput(bytes)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            event.characters?.let { typeText(it) }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) handleKeyDown(event)
        return true
    }

    private fun handleKeyDown(e: KeyEvent) {
        if (KeyEvent.isModifierKey(e.keyCode)) return
        var mods = 0
        if (e.isShiftPressed) mods = mods or GhosttyVt.MODS_SHIFT
        if (e.isCtrlPressed) mods = mods or GhosttyVt.MODS_CTRL
        if (e.isAltPressed) mods = mods or GhosttyVt.MODS_ALT
        if (e.isMetaPressed) mods = mods or GhosttyVt.MODS_SUPER
        // What the key types with Shift but without Ctrl/Alt, as Ghostty expects.
        val meta = e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val uc = e.getUnicodeChar(meta)
        val text = if (uc != 0 && uc and KeyCharacterMap.COMBINING_ACCENT == 0 && uc >= 0x20) String(Character.toChars(uc)) else null
        val unshifted = e.getUnicodeChar(0).takeIf { it > 0 && it and KeyCharacterMap.COMBINING_ACCENT == 0 } ?: 0
        pressKey(e.keyCode, mods, text, unshifted)
    }
}
