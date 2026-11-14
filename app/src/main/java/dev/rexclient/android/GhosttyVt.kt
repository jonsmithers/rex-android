package dev.rexclient.android

/** JNI entry points into librexvt.so (app/src/main/cpp/rexvt.c), a thin layer over libghostty-vt. */
internal object GhosttyVt {
    init {
        System.loadLibrary("rexvt")
    }

    external fun newTerminal(cols: Int, rows: Int): Long
    external fun decodeSnapshot(data: ByteArray): Long
    external fun free(handle: Long)
    external fun write(handle: Long, data: ByteArray, off: Int, len: Int)
    external fun resize(handle: Long, cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)
    external fun setColors(handle: Long, fg: Int, bg: Int, cursor: Int, palette: IntArray?)
    external fun scroll(handle: Long, delta: Int)
    external fun frame(handle: Long, meta: IntArray, cells: IntArray?): Array<String>?
    external fun encodeKey(handle: Long, androidKey: Int, mods: Int, text: String?, unshifted: Int): ByteArray?
    external fun buildInfo(): String

    // Indices into the meta array filled by frame(); keep in sync with rexvt.c.
    const val M_COLS = 0
    const val M_ROWS = 1
    const val M_CURSOR_VISIBLE = 2
    const val M_CURSOR_X = 3
    const val M_CURSOR_Y = 4
    const val M_CURSOR_STYLE = 5
    const val M_CURSOR_WIDE_TAIL = 6
    const val M_BG = 7
    const val M_FG = 8
    const val M_CURSOR_COLOR = 9
    const val M_SCROLL_TOTAL = 10
    const val M_SCROLL_OFFSET = 11
    const val M_SCROLL_LEN = 12
    const val M_TOO_SMALL = 13
    const val M_ALT_SCREEN = 14
    const val M_MOUSE_TRACKING = 15
    const val M_COUNT = 16

    // Per-cell flags; keep in sync with rexvt.c.
    const val F_BOLD = 1 shl 0
    const val F_ITALIC = 1 shl 1
    const val F_FAINT = 1 shl 2
    const val F_BLINK = 1 shl 3
    const val F_INVERSE = 1 shl 4
    const val F_INVISIBLE = 1 shl 5
    const val F_STRIKE = 1 shl 6
    const val F_OVERLINE = 1 shl 7
    const val F_UNDERLINE_SHIFT = 8
    const val F_WIDE_SHIFT = 11
    const val F_SELECTED = 1 shl 13

    const val WIDE_WIDE = 1
    const val WIDE_SPACER_TAIL = 2

    const val CURSOR_BAR = 0
    const val CURSOR_BLOCK = 1
    const val CURSOR_UNDERLINE = 2
    const val CURSOR_BLOCK_HOLLOW = 3

    const val MODS_SHIFT = 1
    const val MODS_CTRL = 2
    const val MODS_ALT = 4
    const val MODS_SUPER = 8
}

/**
 * A Ghostty terminal: VT parser plus screen state. Thread-safe; every call
 * takes this object's lock, and calls after [close] do nothing.
 */
class VtTerminal private constructor(private var handle: Long) {
    /** A copy of the visible screen, refreshed by [frame]. */
    class Frame {
        val meta = IntArray(GhosttyVt.M_COUNT)
        var cells = IntArray(0)
        var extras: Array<String>? = null
        val cols get() = meta[GhosttyVt.M_COLS]
        val rows get() = meta[GhosttyVt.M_ROWS]
    }

    @Synchronized fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
        if (handle != 0L && len > 0) GhosttyVt.write(handle, data, off, len)
    }

    @Synchronized fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        if (handle != 0L) GhosttyVt.resize(handle, cols, rows, cellWidthPx, cellHeightPx)
    }

    /** Colors are 0xRRGGBB or -1 for Ghostty's default; palette entries likewise. */
    @Synchronized fun setColors(fg: Int, bg: Int, cursor: Int, palette: IntArray?) {
        if (handle != 0L) GhosttyVt.setColors(handle, fg, bg, cursor, palette)
    }

    /** Scrolls by [delta] rows (negative is up into history), or to the bottom if 0. */
    @Synchronized fun scroll(delta: Int) {
        if (handle != 0L) GhosttyVt.scroll(handle, delta)
    }

    /** Copies the visible screen into [into]. Returns false once closed. */
    @Synchronized fun frame(into: Frame): Boolean {
        if (handle == 0L) return false
        var extras = GhosttyVt.frame(handle, into.meta, into.cells)
        if (into.meta[GhosttyVt.M_TOO_SMALL] != 0) {
            into.cells = IntArray(into.cols * into.rows * 4)
            extras = GhosttyVt.frame(handle, into.meta, into.cells)
        }
        into.extras = extras
        return true
    }

    /** The bytes a key press sends in the terminal's current modes, or null. */
    @Synchronized fun encodeKey(androidKey: Int, mods: Int, text: String?, unshifted: Int = 0): ByteArray? =
        if (handle == 0L) null else GhosttyVt.encodeKey(handle, androidKey, mods, text, unshifted)

    @Synchronized fun close() {
        if (handle != 0L) GhosttyVt.free(handle)
        handle = 0L
    }

    companion object {
        fun create(cols: Int, rows: Int): VtTerminal? =
            GhosttyVt.newTerminal(cols.coerceAtLeast(1), rows.coerceAtLeast(1)).takeIf { it != 0L }?.let(::VtTerminal)

        /** Restores a terminal from a complete Ghostty snapshot, or null if it can't be decoded. */
        fun fromSnapshot(snapshot: ByteArray): VtTerminal? =
            GhosttyVt.decodeSnapshot(snapshot).takeIf { it != 0L }?.let(::VtTerminal)
    }
}
