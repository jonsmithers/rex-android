// JNI bridge between GhosttyVt.kt and libghostty-vt.
//
// One handle wraps a Ghostty terminal plus what's needed to draw it (render
// state and iterators) and to encode keys for it. The Kotlin side serializes
// all calls on a handle, so nothing here locks.
//
// The terminal never answers queries: GHOSTTY_TERMINAL_OPT_WRITE_PTY stays
// unset because the server-side terminal already answers them, and replies from
// here would arrive at the shell as typed input.

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/keycodes.h>
#include <android/log.h>
#include <ghostty/vt.h>

#define TAG "rexvt"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// Longest a program may hold the screen with synchronized output (mode 2026).
#define MAX_HOLD_MS 1000

typedef struct {
    GhosttyTerminal t;
    GhosttyRenderState rs;
    GhosttyRenderStateRowIterator rows;
    GhosttyRenderStateRowCells cells;
    GhosttyKeyEncoder enc;
    GhosttyKeyEvent ev;
    bool held;
    int64_t hold_start_ms;
} Term;

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// Synchronized output: capture the frame when a hold begins and draw that one
// until the hold ends (or times out; see frame()).
static void on_render_hold(GhosttyTerminal t, void *userdata, bool held) {
    Term *term = userdata;
    if (held) {
        ghostty_render_state_update(term->rs, t);
        term->hold_start_ms = now_ms();
    }
    term->held = held;
}

static Term *wrap(GhosttyTerminal t) {
    Term *term = calloc(1, sizeof(Term));
    if (!term) {
        ghostty_terminal_free(t);
        return NULL;
    }
    term->t = t;
    if (ghostty_render_state_new(NULL, &term->rs) ||
        ghostty_render_state_row_iterator_new(NULL, &term->rows) ||
        ghostty_render_state_row_cells_new(NULL, &term->cells) ||
        ghostty_key_encoder_new(NULL, &term->enc) ||
        ghostty_key_event_new(NULL, &term->ev)) {
        LOGW("out of memory creating terminal state");
    }
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_USERDATA, term);
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (const void *)on_render_hold);
    return term;
}

static void term_free(Term *term) {
    if (!term) return;
    ghostty_key_event_free(term->ev);
    ghostty_key_encoder_free(term->enc);
    ghostty_render_state_row_cells_free(term->cells);
    ghostty_render_state_row_iterator_free(term->rows);
    ghostty_render_state_free(term->rs);
    ghostty_terminal_free(term->t);
    free(term);
}

#define FN(name) Java_dev_rexclient_android_GhosttyVt_##name

JNIEXPORT jlong JNICALL FN(newTerminal)(JNIEnv *env, jobject self, jint cols, jint rows) {
    GhosttyTerminal t = NULL;
    GhosttyResult r = ghostty_terminal_new(NULL, &t, (uint16_t)cols, (uint16_t)rows);
    if (r != GHOSTTY_SUCCESS) {
        LOGW("ghostty_terminal_new failed: %d", r);
        return 0;
    }
    return (jlong)(intptr_t)wrap(t);
}

// Decodes a complete snapshot (envelope through FINISH). Returns 0 on failure.
JNIEXPORT jlong JNICALL FN(decodeSnapshot)(JNIEnv *env, jobject self, jbyteArray data) {
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return 0;

    GhosttySnapshotDecoder dec = NULL;
    GhosttyTerminal t = NULL;
    GhosttyResult r = ghostty_snapshot_decoder_new_buf(NULL, &dec, (const uint8_t *)bytes, (size_t)len);
    if (r == GHOSTTY_SUCCESS) {
        bool compress = true;
        ghostty_snapshot_decoder_set(dec, GHOSTTY_SNAPSHOT_DECODER_OPT_COMPRESS_HISTORY, &compress);
        r = ghostty_snapshot_decoder_decode(dec, &t);
    }
    if (r == GHOSTTY_SUCCESS) {
        size_t used = 0;
        ghostty_snapshot_decoder_get(dec, GHOSTTY_SNAPSHOT_DECODER_DATA_SOURCE_OFFSET, &used);
        if (used != (size_t)len) LOGW("snapshot used %zu of %d bytes", used, (int)len);
    } else {
        LOGW("snapshot decode failed: %d", r);
    }
    ghostty_snapshot_decoder_free(dec);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return t ? (jlong)(intptr_t)wrap(t) : 0;
}

JNIEXPORT void JNICALL FN(free)(JNIEnv *env, jobject self, jlong h) {
    term_free((Term *)(intptr_t)h);
}

JNIEXPORT void JNICALL FN(write)(JNIEnv *env, jobject self, jlong h, jbyteArray data, jint off, jint len) {
    Term *term = (Term *)(intptr_t)h;
    jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (!bytes) return;
    ghostty_terminal_vt_write(term->t, (const uint8_t *)bytes + off, (size_t)len);
    (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL FN(resize)(JNIEnv *env, jobject self, jlong h, jint cols, jint rows, jint cw, jint ch) {
    Term *term = (Term *)(intptr_t)h;
    if (cols <= 0 || rows <= 0) return;
    GhosttyResult r = ghostty_terminal_resize(term->t, (uint16_t)cols, (uint16_t)rows,
                                              (uint32_t)(cw > 0 ? cw : 1), (uint32_t)(ch > 0 ? ch : 1));
    if (r != GHOSTTY_SUCCESS) LOGW("resize %dx%d failed: %d", cols, rows, r);
}

static GhosttyColorRgb rgb(jint c) {
    return (GhosttyColorRgb){.r = (c >> 16) & 0xff, .g = (c >> 8) & 0xff, .b = c & 0xff};
}

// Colors are 0xRRGGBB, or -1 to reset to Ghostty's default. palette entries
// that are -1 keep Ghostty's default palette color; a null palette resets it.
JNIEXPORT void JNICALL FN(setColors)(JNIEnv *env, jobject self, jlong h, jint fg, jint bg, jint cursor,
                                     jintArray palette) {
    Term *term = (Term *)(intptr_t)h;
    GhosttyColorRgb c;
    c = rgb(fg);
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, fg < 0 ? NULL : &c);
    c = rgb(bg);
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, bg < 0 ? NULL : &c);
    c = rgb(cursor);
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, cursor < 0 ? NULL : &c);

    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, NULL);
    if (palette && (*env)->GetArrayLength(env, palette) >= 256) {
        GhosttyColorRgb pal[256];
        if (ghostty_terminal_get(term->t, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, pal) != GHOSTTY_SUCCESS) return;
        jint p[256];
        (*env)->GetIntArrayRegion(env, palette, 0, 256, p);
        for (int i = 0; i < 256; i++) if (p[i] >= 0) pal[i] = rgb(p[i]);
        ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, pal);
    }
}

// Scrolls the viewport by delta rows (negative is up), or to the bottom if
// delta is 0.
JNIEXPORT void JNICALL FN(scroll)(JNIEnv *env, jobject self, jlong h, jint delta) {
    Term *term = (Term *)(intptr_t)h;
    GhosttyTerminalScrollViewport s = {0};
    if (delta == 0) {
        s.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
    } else {
        s.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
        s.value.delta = delta;
    }
    ghostty_terminal_scroll_viewport(term->t, s);
}

// Indices into the meta array filled by frame(). Keep in sync with GhosttyVt.kt.
enum {
    M_COLS, M_ROWS, M_CURSOR_VISIBLE, M_CURSOR_X, M_CURSOR_Y, M_CURSOR_STYLE, M_CURSOR_WIDE_TAIL,
    M_BG, M_FG, M_CURSOR_COLOR, M_SCROLL_TOTAL, M_SCROLL_OFFSET, M_SCROLL_LEN, M_TOO_SMALL,
    M_ALT_SCREEN, M_MOUSE_TRACKING, M_COUNT
};

// Per-cell flags. Keep in sync with GhosttyVt.kt.
enum {
    F_BOLD = 1 << 0, F_ITALIC = 1 << 1, F_FAINT = 1 << 2, F_BLINK = 1 << 3, F_INVERSE = 1 << 4,
    F_INVISIBLE = 1 << 5, F_STRIKE = 1 << 6, F_OVERLINE = 1 << 7,
    F_UNDERLINE_SHIFT = 8,  // 3 bits: GHOSTTY_SGR_UNDERLINE_*
    F_WIDE_SHIFT = 11,      // 2 bits: GHOSTTY_CELL_WIDE_*
    F_SELECTED = 1 << 13,
};

static jint pack(GhosttyColorRgb c) { return (c.r << 16) | (c.g << 8) | c.b; }

// Copies the screen into cells (4 ints per cell: codepoint, fg, bg, flags; fg
// and bg are -1 for the default color) and fills meta. A cell whose grapheme
// has more than one codepoint gets codepoint -(i + 1), where i indexes the
// returned string array. If cells is too small for cols*rows*4, only meta is
// filled and meta[M_TOO_SMALL] is 1.
JNIEXPORT jobjectArray JNICALL FN(frame)(JNIEnv *env, jobject self, jlong h, jintArray metaArr, jintArray cellsArr) {
    Term *term = (Term *)(intptr_t)h;
    jint meta[M_COUNT] = {0};

    if (!term->held || now_ms() - term->hold_start_ms > MAX_HOLD_MS) {
        ghostty_render_state_update(term->rs, term->t);
    }

    uint16_t cols = 0, rows = 0;
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
    meta[M_COLS] = cols;
    meta[M_ROWS] = rows;

    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
    meta[M_BG] = pack(colors.background);
    meta[M_FG] = pack(colors.foreground);
    meta[M_CURSOR_COLOR] = colors.cursor_has_value ? pack(colors.cursor) : -1;

    GhosttyRenderStateCursor cur = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cur);
    meta[M_CURSOR_VISIBLE] = cur.visible && cur.viewport_has_value;
    meta[M_CURSOR_X] = cur.viewport_x;
    meta[M_CURSOR_Y] = cur.viewport_y;
    meta[M_CURSOR_STYLE] = cur.visual_style;
    meta[M_CURSOR_WIDE_TAIL] = cur.wide_tail;

    GhosttyTerminalScrollbar sb = {0};
    if (ghostty_terminal_get(term->t, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) == GHOSTTY_SUCCESS) {
        meta[M_SCROLL_TOTAL] = (jint)sb.total;
        meta[M_SCROLL_OFFSET] = (jint)sb.offset;
        meta[M_SCROLL_LEN] = (jint)sb.len;
    }
    GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    ghostty_terminal_get(term->t, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
    meta[M_ALT_SCREEN] = screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE;
    bool mouse = false;
    ghostty_terminal_get(term->t, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse);
    meta[M_MOUSE_TRACKING] = mouse;

    size_t need = (size_t)cols * rows * 4;
    if (!cellsArr || (size_t)(*env)->GetArrayLength(env, cellsArr) < need) {
        meta[M_TOO_SMALL] = 1;
        (*env)->SetIntArrayRegion(env, metaArr, 0, M_COUNT, meta);
        return NULL;
    }

    jint *out = malloc(need * sizeof(jint));
    if (!out) return NULL;
    // Multi-codepoint graphemes, converted to UTF-16 Java strings at the end.
    jobjectArray extras = NULL;
    jstring extraStrs[256];
    int nextras = 0;

    if (ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &term->rows) == GHOSTTY_SUCCESS) {
        uint16_t y = 0;
        while (ghostty_render_state_row_iterator_next(term->rows) && y < rows) {
            if (ghostty_render_state_row_get(term->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &term->cells) != GHOSTTY_SUCCESS) {
                y++;
                continue;
            }
            uint16_t x = 0;
            while (ghostty_render_state_row_cells_next(term->cells) && x < cols) {
                jint *c = out + ((size_t)y * cols + x) * 4;
                uint32_t glen = 0;
                ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
                jint cp = 0;
                if (glen == 1) {
                    uint32_t one;
                    ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, &one);
                    cp = (jint)one;
                } else if (glen > 1) {
                    uint32_t cps[32];
                    if (glen <= 32 && nextras < 256) {
                        ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, cps);
                        jchar u16[64];
                        int n = 0;
                        for (uint32_t i = 0; i < glen; i++) {
                            uint32_t v = cps[i];
                            if (v >= 0x10000) {
                                v -= 0x10000;
                                u16[n++] = (jchar)(0xd800 + (v >> 10));
                                u16[n++] = (jchar)(0xdc00 + (v & 0x3ff));
                            } else {
                                u16[n++] = (jchar)v;
                            }
                        }
                        extraStrs[nextras] = (*env)->NewString(env, u16, n);
                        cp = -(++nextras);
                    } else {
                        ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, cps);
                        cp = (jint)cps[0];
                    }
                }
                c[0] = cp;

                GhosttyColorRgb rgbv;
                c[1] = ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &rgbv) == GHOSTTY_SUCCESS ? pack(rgbv) : -1;
                c[2] = ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &rgbv) == GHOSTTY_SUCCESS ? pack(rgbv) : -1;

                jint flags = 0;
                bool styled = false;
                ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING, &styled);
                if (styled) {
                    GhosttyStyle st = GHOSTTY_INIT_SIZED(GhosttyStyle);
                    ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &st);
                    if (st.bold) flags |= F_BOLD;
                    if (st.italic) flags |= F_ITALIC;
                    if (st.faint) flags |= F_FAINT;
                    if (st.blink) flags |= F_BLINK;
                    if (st.inverse) flags |= F_INVERSE;
                    if (st.invisible) flags |= F_INVISIBLE;
                    if (st.strikethrough) flags |= F_STRIKE;
                    if (st.overline) flags |= F_OVERLINE;
                    flags |= (st.underline & 7) << F_UNDERLINE_SHIFT;
                }
                GhosttyCell raw = 0;
                if (ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw) == GHOSTTY_SUCCESS) {
                    GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
                    ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
                    flags |= (wide & 3) << F_WIDE_SHIFT;
                }
                bool sel = false;
                ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &sel);
                if (sel) flags |= F_SELECTED;
                c[3] = flags;
                x++;
            }
            // Rows shorter than cols (shouldn't happen) are padded with blanks.
            for (; x < cols; x++) {
                jint *c = out + ((size_t)y * cols + x) * 4;
                c[0] = 0; c[1] = -1; c[2] = -1; c[3] = 0;
            }
            y++;
        }
        for (; y < rows; y++) {
            for (uint16_t x = 0; x < cols; x++) {
                jint *c = out + ((size_t)y * cols + x) * 4;
                c[0] = 0; c[1] = -1; c[2] = -1; c[3] = 0;
            }
        }
    }
    ghostty_render_state_clean(term->rs);

    (*env)->SetIntArrayRegion(env, cellsArr, 0, (jsize)need, out);
    (*env)->SetIntArrayRegion(env, metaArr, 0, M_COUNT, meta);
    free(out);

    if (nextras > 0) {
        jclass str = (*env)->FindClass(env, "java/lang/String");
        extras = (*env)->NewObjectArray(env, nextras, str, NULL);
        for (int i = 0; i < nextras; i++) {
            (*env)->SetObjectArrayElement(env, extras, i, extraStrs[i]);
            (*env)->DeleteLocalRef(env, extraStrs[i]);
        }
        (*env)->DeleteLocalRef(env, str);
    }
    return extras;
}

static GhosttyKey key_from_android(int code) {
    if (code >= AKEYCODE_A && code <= AKEYCODE_Z) return GHOSTTY_KEY_A + (code - AKEYCODE_A);
    if (code >= AKEYCODE_0 && code <= AKEYCODE_9) return GHOSTTY_KEY_DIGIT_0 + (code - AKEYCODE_0);
    if (code >= AKEYCODE_NUMPAD_0 && code <= AKEYCODE_NUMPAD_9) return GHOSTTY_KEY_NUMPAD_0 + (code - AKEYCODE_NUMPAD_0);
    if (code >= AKEYCODE_F1 && code <= AKEYCODE_F12) return GHOSTTY_KEY_F1 + (code - AKEYCODE_F1);
    switch (code) {
    case AKEYCODE_DPAD_UP: return GHOSTTY_KEY_ARROW_UP;
    case AKEYCODE_DPAD_DOWN: return GHOSTTY_KEY_ARROW_DOWN;
    case AKEYCODE_DPAD_LEFT: return GHOSTTY_KEY_ARROW_LEFT;
    case AKEYCODE_DPAD_RIGHT: return GHOSTTY_KEY_ARROW_RIGHT;
    case AKEYCODE_MOVE_HOME: return GHOSTTY_KEY_HOME;
    case AKEYCODE_MOVE_END: return GHOSTTY_KEY_END;
    case AKEYCODE_PAGE_UP: return GHOSTTY_KEY_PAGE_UP;
    case AKEYCODE_PAGE_DOWN: return GHOSTTY_KEY_PAGE_DOWN;
    case AKEYCODE_INSERT: return GHOSTTY_KEY_INSERT;
    case AKEYCODE_FORWARD_DEL: return GHOSTTY_KEY_DELETE;
    case AKEYCODE_DEL: return GHOSTTY_KEY_BACKSPACE;
    case AKEYCODE_ENTER: return GHOSTTY_KEY_ENTER;
    case AKEYCODE_NUMPAD_ENTER: return GHOSTTY_KEY_NUMPAD_ENTER;
    case AKEYCODE_TAB: return GHOSTTY_KEY_TAB;
    case AKEYCODE_ESCAPE: return GHOSTTY_KEY_ESCAPE;
    case AKEYCODE_SPACE: return GHOSTTY_KEY_SPACE;
    case AKEYCODE_GRAVE: return GHOSTTY_KEY_BACKQUOTE;
    case AKEYCODE_MINUS: return GHOSTTY_KEY_MINUS;
    case AKEYCODE_EQUALS: return GHOSTTY_KEY_EQUAL;
    case AKEYCODE_LEFT_BRACKET: return GHOSTTY_KEY_BRACKET_LEFT;
    case AKEYCODE_RIGHT_BRACKET: return GHOSTTY_KEY_BRACKET_RIGHT;
    case AKEYCODE_BACKSLASH: return GHOSTTY_KEY_BACKSLASH;
    case AKEYCODE_SEMICOLON: return GHOSTTY_KEY_SEMICOLON;
    case AKEYCODE_APOSTROPHE: return GHOSTTY_KEY_QUOTE;
    case AKEYCODE_COMMA: return GHOSTTY_KEY_COMMA;
    case AKEYCODE_PERIOD: return GHOSTTY_KEY_PERIOD;
    case AKEYCODE_SLASH: return GHOSTTY_KEY_SLASH;
    case AKEYCODE_NUMPAD_ADD: return GHOSTTY_KEY_NUMPAD_ADD;
    case AKEYCODE_NUMPAD_SUBTRACT: return GHOSTTY_KEY_NUMPAD_SUBTRACT;
    case AKEYCODE_NUMPAD_MULTIPLY: return GHOSTTY_KEY_NUMPAD_MULTIPLY;
    case AKEYCODE_NUMPAD_DIVIDE: return GHOSTTY_KEY_NUMPAD_DIVIDE;
    case AKEYCODE_NUMPAD_DOT: return GHOSTTY_KEY_NUMPAD_DECIMAL;
    case AKEYCODE_NUMPAD_EQUALS: return GHOSTTY_KEY_NUMPAD_EQUAL;
    default: return GHOSTTY_KEY_UNIDENTIFIED;
    }
}

// The US-layout physical key that types an ASCII character, for text that
// arrives without a key code (soft keyboards) but needs modifiers applied.
static GhosttyKey key_from_char(uint32_t c) {
    if (c >= 'a' && c <= 'z') return GHOSTTY_KEY_A + (c - 'a');
    if (c >= 'A' && c <= 'Z') return GHOSTTY_KEY_A + (c - 'A');
    if (c >= '0' && c <= '9') return GHOSTTY_KEY_DIGIT_0 + (c - '0');
    switch (c) {
    case ' ': return GHOSTTY_KEY_SPACE;
    case '`': case '~': return GHOSTTY_KEY_BACKQUOTE;
    case '-': case '_': return GHOSTTY_KEY_MINUS;
    case '=': case '+': return GHOSTTY_KEY_EQUAL;
    case '[': case '{': return GHOSTTY_KEY_BRACKET_LEFT;
    case ']': case '}': return GHOSTTY_KEY_BRACKET_RIGHT;
    case '\\': case '|': return GHOSTTY_KEY_BACKSLASH;
    case ';': case ':': return GHOSTTY_KEY_SEMICOLON;
    case '\'': case '"': return GHOSTTY_KEY_QUOTE;
    case ',': case '<': return GHOSTTY_KEY_COMMA;
    case '.': case '>': return GHOSTTY_KEY_PERIOD;
    case '/': case '?': return GHOSTTY_KEY_SLASH;
    case '@': return GHOSTTY_KEY_DIGIT_2;
    case '^': return GHOSTTY_KEY_DIGIT_6;
    default: return GHOSTTY_KEY_UNIDENTIFIED;
    }
}

// Encodes a key press for the terminal's current modes (cursor-key mode, kitty
// keyboard flags, ...). androidKey is an Android KEYCODE_*, or 0 to derive the
// key from text. mods uses GHOSTTY_MODS_* bits. text is what the key types
// without Ctrl/Alt applied (may be null). Returns null if the key produces
// nothing.
JNIEXPORT jbyteArray JNICALL FN(encodeKey)(JNIEnv *env, jobject self, jlong h, jint androidKey, jint mods,
                                           jstring text, jint unshifted) {
    Term *term = (Term *)(intptr_t)h;
    const char *utf8 = text ? (*env)->GetStringUTFChars(env, text, NULL) : NULL;
    size_t utf8len = utf8 ? strlen(utf8) : 0;

    GhosttyKey key = androidKey ? key_from_android(androidKey) : GHOSTTY_KEY_UNIDENTIFIED;
    if (key == GHOSTTY_KEY_UNIDENTIFIED && utf8len == 1) key = key_from_char((uint8_t)utf8[0]);
    uint32_t base = unshifted > 0 ? (uint32_t)unshifted : 0;
    if (!base && utf8len == 1) {
        base = (uint8_t)utf8[0];
        if (base >= 'A' && base <= 'Z') base += 'a' - 'A';
    }

    ghostty_key_encoder_setopt_from_terminal(term->enc, term->t);
    bool alt_esc = true;
    ghostty_key_encoder_setopt(term->enc, GHOSTTY_KEY_ENCODER_OPT_ALT_ESC_PREFIX, &alt_esc);

    ghostty_key_event_set_action(term->ev, GHOSTTY_KEY_ACTION_PRESS);
    ghostty_key_event_set_key(term->ev, key);
    ghostty_key_event_set_mods(term->ev, (GhosttyMods)mods);
    ghostty_key_event_set_consumed_mods(term->ev, (mods & GHOSTTY_MODS_SHIFT) && utf8len ? GHOSTTY_MODS_SHIFT : 0);
    ghostty_key_event_set_composing(term->ev, false);
    ghostty_key_event_set_utf8(term->ev, utf8, utf8len);
    ghostty_key_event_set_unshifted_codepoint(term->ev, base);

    char buf[128];
    size_t n = 0;
    GhosttyResult r = ghostty_key_encoder_encode(term->enc, term->ev, buf, sizeof(buf), &n);
    ghostty_key_event_set_utf8(term->ev, NULL, 0);
    if (utf8) (*env)->ReleaseStringUTFChars(env, text, utf8);
    if (r != GHOSTTY_SUCCESS || n == 0) return NULL;

    jbyteArray out = (*env)->NewByteArray(env, (jsize)n);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)buf);
    return out;
}

#ifndef GHOSTTY_COMMIT
#define GHOSTTY_COMMIT "unknown"
#endif

// "<libghostty-vt version> (<Ghostty commit>)", for logs.
JNIEXPORT jstring JNICALL FN(buildInfo)(JNIEnv *env, jobject self) {
    GhosttyString s = {0};
    char tmp[192];
    if (ghostty_build_info(GHOSTTY_BUILD_INFO_VERSION_STRING, &s) != GHOSTTY_SUCCESS || !s.ptr) s.len = 0;
    snprintf(tmp, sizeof(tmp), "%.*s (%s)", (int)(s.len < 64 ? s.len : 64), s.ptr ? (const char *)s.ptr : "", GHOSTTY_COMMIT);
    return (*env)->NewStringUTF(env, tmp);
}
