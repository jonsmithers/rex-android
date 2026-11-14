package dev.rexclient.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * A WebView that owns keyboard input itself instead of letting xterm.js's
 * hidden textarea see it. Android IMEs and xterm.js's textarea diffing don't
 * get along (characters get dropped and repeated), so text from the soft
 * keyboard and key presses from a hardware keyboard are translated here and
 * handed to [onText] (literal text) or [onKey] (named keys whose escape
 * sequence depends on terminal modes, such as arrows).
 */
@SuppressLint("ViewConstructor")
class TermWebView(context: Context) : WebView(context) {
    var onText: (String) -> Unit = {}
    var onKey: (String) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

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

    // A tap (not a scroll) on the terminal brings up the keyboard. This is done
    // natively because xterm.js may consume the page's touch events.
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tapCandidate = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; tapCandidate = true
            }
            MotionEvent.ACTION_MOVE -> if (tapCandidate &&
                (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
            ) tapCandidate = false
            MotionEvent.ACTION_UP -> if (tapCandidate &&
                event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() &&
                event.y < height - keysBarHeightPx
            ) showKeyboard()
            MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }
        return super.onTouchEvent(event)
    }

    /** Height of the page's keys bar, whose buttons handle their own taps. */
    private val keysBarHeightPx get() = 41f * resources.displayMetrics.density

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

        private fun flushComposing() {
            if (composing.isNotEmpty()) onText(composing)
            composing = ""
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            emitText(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Shown only once committed; fine for a barebones client.
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            flushComposing()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isNotEmpty()) {
                composing = composing.dropLast(beforeLength)
            } else {
                repeat(beforeLength) { onText("\u007f") }
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            dispatchKeyEvent(event)
            return true
        }
    }

    private fun emitText(s: String) {
        // Keyboards send Enter as "\n"; terminals expect a carriage return.
        if (s.isNotEmpty()) onText(s.replace('\n', '\r'))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            event.characters?.let { emitText(it) }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) handleKeyDown(event)
        return true // Never let the page see keys.
    }

    private fun handleKeyDown(e: KeyEvent) {
        val named = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_MOVE_HOME -> "home"
            KeyEvent.KEYCODE_MOVE_END -> "end"
            else -> null
        }
        if (named != null) return onKey(named)

        val seq = when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> if (e.isAltPressed) "\u001b\u007f" else "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_TAB -> if (e.isShiftPressed) "\u001b[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            KeyEvent.KEYCODE_INSERT -> "\u001b[2~"
            else -> null
        }
        if (seq != null) return onText(seq)

        val meta = e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val uc = e.getUnicodeChar(meta)
        if (uc == 0 || uc and KeyCharacterMap.COMBINING_ACCENT != 0) return
        var s = String(Character.toChars(uc))
        if (e.isCtrlPressed) s = controlChar(s) ?: return
        if (e.isAltPressed) s = "\u001b" + s
        onText(s)
    }

    private fun controlChar(s: String): String? {
        val c = s.uppercase().singleOrNull() ?: return null
        return when (c) {
            in '@'..'_' -> (c.code - 64).toChar().toString()
            ' ' -> "\u0000"
            '?' -> "\u007f"
            else -> null
        }
    }
}
