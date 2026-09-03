// Managed JNI bridge for libghostty-vt.
//
// A jlong handle points to a NativeTerminal wrapper rather than a bare
// GhosttyTerminal. The wrapper owns terminal + render state handles and
// buffers side effects (WRITE_PTY responses) so Kotlin never handles raw
// Ghostty pointers directly.
//
// Threading contract: all native methods for one handle must be called from
// the same thread / serialized executor. libghostty-vt creates no threads.

#include <jni.h>
#include <android/keycodes.h>

#include <cstdint>
#include <cstring>
#include <new>
#include <string>
#include <vector>

// GHOSTTY_STATIC is provided by CMake target_compile_definitions.
#include <ghostty/vt.h>

namespace {

constexpr const char* kClassName =
    "com/yang136/sshhelper/terminal/GhosttyNativeBridge";

constexpr uint32_t kEventBell = 1u << 0;
constexpr uint32_t kEventTitle = 1u << 1;
constexpr uint32_t kEventPwd = 1u << 2;

struct NativeTerminal {
    GhosttyTerminal terminal = nullptr;
    GhosttyRenderState render_state = nullptr;
    GhosttyRenderStateRowIterator row_iter = nullptr;
    GhosttyRenderStateRowCells row_cells = nullptr;
    GhosttySearch search = nullptr;
    GhosttyMouseEncoder mouse_encoder = nullptr;
    GhosttyMouseEvent mouse_event = nullptr;
    GhosttyKeyEncoder key_encoder = nullptr;
    GhosttyKeyEvent key_event = nullptr;
    GhosttySelectionGesture selection_gesture = nullptr;

    // Last grid size/cell geometry passed by the View. Used by selection
    // gesture drag events which require display geometry for edge behavior.
    uint32_t cols = 80;
    uint32_t rows = 24;
    uint32_t cell_width_px = 1;
    uint32_t cell_height_px = 1;

    // Bytes libghostty asks us to write back to the PTY (DSR/mode queries).
    std::vector<uint8_t> pending_pty_writes;

    // Effect flags (bell/title/pwd) observed since the last drain.
    uint32_t pending_events = 0;

    // Incremented on reset; Kotlin can discard stale output generations.
    uint64_t generation = 0;

    bool closed = false;
};

NativeTerminal* fromHandle(jlong handle) {
    return reinterpret_cast<NativeTerminal*>(handle);
}

void writePtyCallback(
    GhosttyTerminal /* terminal */,
    void* userdata,
    const uint8_t* data,
    size_t len) {
    auto* native = static_cast<NativeTerminal*>(userdata);
    if (native == nullptr || data == nullptr || len == 0) return;
    native->pending_pty_writes.insert(
        native->pending_pty_writes.end(), data, data + len);
}

void bellCallback(
    GhosttyTerminal /* terminal */,
    void* userdata) {
    auto* native = static_cast<NativeTerminal*>(userdata);
    if (native != nullptr) native->pending_events |= kEventBell;
}

void titleChangedCallback(
    GhosttyTerminal /* terminal */,
    void* userdata) {
    auto* native = static_cast<NativeTerminal*>(userdata);
    if (native != nullptr) native->pending_events |= kEventTitle;
}

void pwdChangedCallback(
    GhosttyTerminal /* terminal */,
    void* userdata) {
    auto* native = static_cast<NativeTerminal*>(userdata);
    if (native != nullptr) native->pending_events |= kEventPwd;
}

jbyteArray terminalStringData(
    JNIEnv* env,
    NativeTerminal* native,
    GhosttyTerminalData key) {
    if (native == nullptr || native->terminal == nullptr) return nullptr;
    GhosttyString str{};
    if (ghostty_terminal_get(native->terminal, key, &str) != GHOSTTY_SUCCESS ||
        str.ptr == nullptr || str.len == 0) {
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(str.len));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(
        out, 0, static_cast<jsize>(str.len),
        reinterpret_cast<const jbyte*>(str.ptr));
    return out;
}

void freeNativeTerminal(NativeTerminal* native) {
    if (native == nullptr) return;
    if (native->closed) {
        // Safety net for accidental double free through a stale Kotlin handle.
        // Kotlin owners should still null/clear their handles after close.
        return;
    }
    native->closed = true;
    if (native->search != nullptr) {
        ghostty_search_free(native->search);
        native->search = nullptr;
    }
    if (native->key_event != nullptr) {
        ghostty_key_event_free(native->key_event);
        native->key_event = nullptr;
    }
    if (native->key_encoder != nullptr) {
        ghostty_key_encoder_free(native->key_encoder);
        native->key_encoder = nullptr;
    }
    if (native->selection_gesture != nullptr) {
        ghostty_selection_gesture_free(native->selection_gesture, native->terminal);
        native->selection_gesture = nullptr;
    }
    if (native->mouse_event != nullptr) {
        ghostty_mouse_event_free(native->mouse_event);
        native->mouse_event = nullptr;
    }
    if (native->mouse_encoder != nullptr) {
        ghostty_mouse_encoder_free(native->mouse_encoder);
        native->mouse_encoder = nullptr;
    }
    if (native->row_cells != nullptr) {
        ghostty_render_state_row_cells_free(native->row_cells);
        native->row_cells = nullptr;
    }
    if (native->row_iter != nullptr) {
        ghostty_render_state_row_iterator_free(native->row_iter);
        native->row_iter = nullptr;
    }
    if (native->render_state != nullptr) {
        ghostty_render_state_free(native->render_state);
        native->render_state = nullptr;
    }
    if (native->terminal != nullptr) {
        ghostty_terminal_free(native->terminal);
        native->terminal = nullptr;
    }
    native->pending_pty_writes.clear();
    delete native;
}

jbyteArray toJByteArray(JNIEnv* env, const std::vector<uint8_t>& bytes) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (out == nullptr) return nullptr;
    if (!bytes.empty()) {
        env->SetByteArrayRegion(
            out, 0, static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return out;
}

// Snapshot binary format v3 (little-endian).
//
// Header (14 * int32):
//   [0]  version = 3
//   [1]  dirty_kind (0 none, 1 partial, 2 full)
//   [2]  cols
//   [3]  rows
//   [4]  default bg ARGB
//   [5]  default fg ARGB
//   [6]  cursor x or -1
//   [7]  cursor y or -1
//   [8]  cursor style (0 bar, 1 block, 2 underline, 3 hollow)
//   [9]  cursor visible (0/1)
//   [10] cursor blinking (0/1)
//   [11] row count
//   [12] generation (low 32 bits)
//   [13] effective cursor color ARGB
//
// Row record:
//   int32 row_index
//   int32 selection_start_x (-1 when no selection on this row)
//   int32 selection_end_x (-1 when no selection on this row)
//   int32 cell_count
//   repeated cell:
//     int32 fg ARGB
//     int32 bg ARGB
//     uint16 flags
//     uint16 text_len
//     uint8  text[text_len]

constexpr uint16_t kSnapshotVersion = 3;
constexpr uint16_t kCellFlagBold = 1 << 0;
constexpr uint16_t kCellFlagItalic = 1 << 1;
constexpr uint16_t kCellFlagFaint = 1 << 2;
constexpr uint16_t kCellFlagInverse = 1 << 3;
constexpr uint16_t kCellFlagUnderline = 1 << 4;
constexpr uint16_t kCellFlagUnderlineStyleShift = 10;
constexpr uint16_t kCellFlagUnderlineStyleMask = 0x7 << kCellFlagUnderlineStyleShift;
constexpr uint16_t kCellFlagStrikethrough = 1 << 5;
constexpr uint16_t kCellFlagOverline = 1 << 6;
constexpr uint16_t kCellFlagInvisible = 1 << 7;
constexpr uint16_t kCellFlagWide = 1 << 8;
constexpr uint16_t kCellFlagWideTail = 1 << 9;

inline void putI32(std::vector<uint8_t>& out, int32_t value) {
    const uint8_t bytes[4] = {
        static_cast<uint8_t>(value & 0xFF),
        static_cast<uint8_t>((value >> 8) & 0xFF),
        static_cast<uint8_t>((value >> 16) & 0xFF),
        static_cast<uint8_t>((value >> 24) & 0xFF),
    };
    out.insert(out.end(), bytes, bytes + 4);
}

inline void putU16(std::vector<uint8_t>& out, uint16_t value) {
    const uint8_t bytes[2] = {
        static_cast<uint8_t>(value & 0xFF),
        static_cast<uint8_t>((value >> 8) & 0xFF),
    };
    out.insert(out.end(), bytes, bytes + 2);
}

inline void putBytes(std::vector<uint8_t>& out, const uint8_t* data, size_t len) {
    out.insert(out.end(), data, data + len);
}

inline int32_t argb(GhosttyColorRgb color) {
    return static_cast<int32_t>(
        0xFF000000u |
        (static_cast<uint32_t>(color.r) << 16) |
        (static_cast<uint32_t>(color.g) << 8) |
        color.b);
}

// Populate a vector with the terminal's current render snapshot. Returns
// false if a Ghostty API call failed unexpectedly. The output vector starts
// empty on every call.
bool buildRenderSnapshot(NativeTerminal* native, std::vector<uint8_t>& out) {
    out.clear();

    GhosttyResult result = ghostty_render_state_update(
        native->render_state, native->terminal);
    if (result != GHOSTTY_SUCCESS) return false;

    GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    uint16_t cols = 0;
    uint16_t rows = 0;
    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);

    if (ghostty_render_state_get(
            native->render_state, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty) != GHOSTTY_SUCCESS ||
        ghostty_render_state_get(
            native->render_state, GHOSTTY_RENDER_STATE_DATA_COLS, &cols) != GHOSTTY_SUCCESS ||
        ghostty_render_state_get(
            native->render_state, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows) != GHOSTTY_SUCCESS ||
        ghostty_render_state_get(
            native->render_state, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors) != GHOSTTY_SUCCESS ||
        ghostty_render_state_get(
            native->render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor) != GHOSTTY_SUCCESS) {
        return false;
    }

    GhosttyColorRgb cursor_color = colors.cursor_has_value ? colors.cursor : colors.foreground;
    if (ghostty_terminal_get(
            native->terminal,
            GHOSTTY_TERMINAL_DATA_COLOR_CURSOR,
            &cursor_color) != GHOSTTY_SUCCESS) {
        cursor_color = colors.cursor_has_value ? colors.cursor : colors.foreground;
    }

    if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE) {
        putI32(out, kSnapshotVersion);
        putI32(out, static_cast<int32_t>(GHOSTTY_RENDER_STATE_DIRTY_FALSE));
        putI32(out, cols);
        putI32(out, rows);
        putI32(out, argb(colors.background));
        putI32(out, argb(colors.foreground));
        putI32(
            out,
            (cursor.viewport_has_value && cursor.visible)
                ? static_cast<int32_t>(cursor.viewport_x)
                : -1);
        putI32(
            out,
            (cursor.viewport_has_value && cursor.visible)
                ? static_cast<int32_t>(cursor.viewport_y)
                : -1);
        putI32(out, static_cast<int32_t>(cursor.visual_style));
        putI32(out, cursor.visible ? 1 : 0);
        putI32(out, cursor.blinking ? 1 : 0);
        putI32(out, 0);
        putI32(out, static_cast<int32_t>(native->generation & 0xFFFFFFFFu));
        putI32(out, argb(cursor_color));
        return true;
    }

    // Header placeholder; row count patched after iteration.
    putI32(out, kSnapshotVersion);
    putI32(out, static_cast<int32_t>(dirty));
    putI32(out, cols);
    putI32(out, rows);
    putI32(out, argb(colors.background));
    putI32(out, argb(colors.foreground));
    putI32(
        out,
        (cursor.viewport_has_value && cursor.visible)
            ? static_cast<int32_t>(cursor.viewport_x)
            : -1);
    putI32(
        out,
        (cursor.viewport_has_value && cursor.visible)
            ? static_cast<int32_t>(cursor.viewport_y)
            : -1);
    putI32(out, static_cast<int32_t>(cursor.visual_style));
    putI32(out, cursor.visible ? 1 : 0);
    putI32(out, cursor.blinking ? 1 : 0);
    const size_t row_count_offset = out.size();
    putI32(out, 0); // row count placeholder
    putI32(out, static_cast<int32_t>(native->generation & 0xFFFFFFFFu));
    putI32(out, argb(cursor_color));

    if (ghostty_render_state_get(
            native->render_state,
            GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
            &native->row_iter) != GHOSTTY_SUCCESS) {
        return false;
    }

    int32_t row_count = 0;
    uint16_t row_y = 0;
    while (ghostty_render_state_row_iterator_next_dirty(native->row_iter, &row_y)) {
        GhosttyCellsView raw_view{};
        if (ghostty_render_state_row_get(
                native->row_iter,
                GHOSTTY_RENDER_STATE_ROW_DATA_CELLS_RAW,
                &raw_view) != GHOSTTY_SUCCESS ||
            raw_view.ptr == nullptr) {
            return false;
        }
        if (ghostty_render_state_row_get(
                native->row_iter,
                GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                &native->row_cells) != GHOSTTY_SUCCESS) {
            return false;
        }

        const uint16_t cell_count = raw_view.len > cols ? cols : static_cast<uint16_t>(raw_view.len);

        GhosttyRenderStateRowSelection row_selection =
            GHOSTTY_INIT_SIZED(GhosttyRenderStateRowSelection);
        int32_t selection_start_x = -1;
        int32_t selection_end_x = -1;
        if (ghostty_render_state_row_get(
                native->row_iter,
                GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION,
                &row_selection) == GHOSTTY_SUCCESS) {
            selection_start_x = row_selection.start_x;
            selection_end_x = row_selection.end_x;
        }

        putI32(out, row_y);
        putI32(out, selection_start_x);
        putI32(out, selection_end_x);
        putI32(out, cell_count);

        for (uint16_t x = 0; x < cell_count; ++x) {
            GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
            GhosttyColorRgb fg = colors.foreground;
            GhosttyColorRgb bg = colors.background;

            if (ghostty_render_state_row_cells_select(native->row_cells, x) != GHOSTTY_SUCCESS) {
                return false;
            }
            if (ghostty_render_state_row_cells_get(
                    native->row_cells,
                    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                    &style) != GHOSTTY_SUCCESS) {
                return false;
            }
            if (ghostty_render_state_row_cells_get(
                    native->row_cells,
                    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
                    &fg) != GHOSTTY_SUCCESS) {
                fg = colors.foreground;
            }
            if (ghostty_render_state_row_cells_get(
                    native->row_cells,
                    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
                    &bg) != GHOSTTY_SUCCESS) {
                bg = colors.background;
            }

            uint16_t flags = 0;
            if (style.bold) flags |= kCellFlagBold;
            if (style.italic) flags |= kCellFlagItalic;
            if (style.faint) flags |= kCellFlagFaint;
            if (style.inverse) flags |= kCellFlagInverse;
            if (style.underline != 0) {
                flags |= kCellFlagUnderline;
                const uint16_t encoded_style =
                    static_cast<uint16_t>(style.underline) << kCellFlagUnderlineStyleShift;
                flags = static_cast<uint16_t>(
                    (flags & ~kCellFlagUnderlineStyleMask) | encoded_style);
            }
            if (style.strikethrough) flags |= kCellFlagStrikethrough;
            if (style.overline) flags |= kCellFlagOverline;
            if (style.invisible) flags |= kCellFlagInvisible;

            GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
            if (ghostty_cell_get(
                    raw_view.ptr[x], GHOSTTY_CELL_DATA_WIDE, &wide) == GHOSTTY_SUCCESS) {
                if (wide == GHOSTTY_CELL_WIDE_WIDE) flags |= kCellFlagWide;
                if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL) flags |= kCellFlagWideTail;
            }

            // Query UTF-8 grapheme length, then read the cell text.
            GhosttyBuffer length_query{};
            GhosttyResult text_result = ghostty_render_state_row_cells_get(
                native->row_cells,
                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
                &length_query);
            if (text_result != GHOSTTY_SUCCESS && text_result != GHOSTTY_OUT_OF_SPACE) {
                return false;
            }
            std::vector<uint8_t> cell_text(length_query.len);
            GhosttyBuffer text_out{
                cell_text.empty() ? nullptr : cell_text.data(),
                cell_text.size(),
                0,
            };
            if (!cell_text.empty()) {
                if (ghostty_render_state_row_cells_get(
                        native->row_cells,
                        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
                        &text_out) != GHOSTTY_SUCCESS) {
                    return false;
                }
            }

            putI32(out, argb(fg));
            putI32(out, argb(bg));
            putU16(out, flags);
            putU16(out, static_cast<uint16_t>(text_out.len));
            putBytes(out, text_out.ptr, text_out.len);
        }

        row_count += 1;
    }

    // Patch row count.
    const uint8_t row_count_bytes[4] = {
        static_cast<uint8_t>(row_count & 0xFF),
        static_cast<uint8_t>((row_count >> 8) & 0xFF),
        static_cast<uint8_t>((row_count >> 16) & 0xFF),
        static_cast<uint8_t>((row_count >> 24) & 0xFF),
    };
    std::memcpy(out.data() + row_count_offset, row_count_bytes, 4);

    return true;
}

bool gridRefAtViewport(NativeTerminal* native, jint col, jint row, GhosttyGridRef* out) {
    if (native == nullptr || native->terminal == nullptr || out == nullptr) return false;
    if (col < 0 || row < 0 ||
        static_cast<uint32_t>(col) >= native->cols ||
        static_cast<uint32_t>(row) >= native->rows) {
        return false;
    }
    GhosttyPoint point{};
    point.tag = GHOSTTY_POINT_TAG_VIEWPORT;
    point.value.coordinate.x = static_cast<uint16_t>(col);
    point.value.coordinate.y = static_cast<uint32_t>(row);
    return ghostty_terminal_grid_ref(native->terminal, point, out) == GHOSTTY_SUCCESS;
}

bool isMouseReportingActive(NativeTerminal* native) {
    if (native == nullptr || native->terminal == nullptr) return false;
    const GhosttyMode modes[] = {
        GHOSTTY_MODE_X10_MOUSE,
        GHOSTTY_MODE_NORMAL_MOUSE,
        GHOSTTY_MODE_BUTTON_MOUSE,
        GHOSTTY_MODE_ANY_MOUSE,
    };
    for (const GhosttyMode mode : modes) {
        GhosttyTerminalModeConfig config{};
        config.mode = mode;
        if (ghostty_terminal_get(
                native->terminal,
                GHOSTTY_TERMINAL_DATA_MODE,
                &config) == GHOSTTY_SUCCESS &&
            config.value) {
            return true;
        }
    }
    return false;
}

bool applySelectionEvent(
    NativeTerminal* native,
    GhosttySelectionGestureEventType type,
    jint col,
    jint row) {
    if (native == nullptr || native->terminal == nullptr ||
        native->selection_gesture == nullptr) {
        return false;
    }

    GhosttySelectionGestureEvent event = nullptr;
    if (ghostty_selection_gesture_event_new(nullptr, &event, type) != GHOSTTY_SUCCESS ||
        event == nullptr) {
        return false;
    }

    bool ok = false;
    GhosttyGridRef ref{};
    if (gridRefAtViewport(native, col, row, &ref)) {
        ghostty_selection_gesture_event_set(
            event, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF, &ref);
        if (type == GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG) {
            GhosttySelectionGestureGeometry geometry{};
            geometry.columns = native->cols;
            geometry.cell_width = native->cell_width_px;
            geometry.padding_left = 0;
            geometry.screen_height = native->rows * native->cell_height_px;
            ghostty_selection_gesture_event_set(
                event, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY, &geometry);
        }
    } else if (type != GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE) {
        ghostty_selection_gesture_event_free(event);
        return false;
    }

    GhosttySelection selection = GHOSTTY_INIT_SIZED(GhosttySelection);
    const GhosttyResult result = ghostty_selection_gesture_event(
        native->selection_gesture, native->terminal, event, &selection);
    if (result == GHOSTTY_SUCCESS) {
        ghostty_terminal_set(native->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &selection);
        ok = true;
    } else if (result == GHOSTTY_NO_VALUE) {
        // Press without a selection yet, or a release event. Not an error.
        ok = true;
    }
    ghostty_selection_gesture_event_free(event);
    return ok;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeCreateManaged(
    JNIEnv* env,
    jobject /* thiz */,
    jint cols,
    jint rows) {
    if (cols <= 0 || rows <= 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "Ghostty terminal cols/rows must be positive");
        return 0;
    }

    auto* native = new (std::nothrow) NativeTerminal();
    if (native == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "NativeTerminal allocation failed");
        return 0;
    }
    native->cols = static_cast<uint32_t>(cols);
    native->rows = static_cast<uint32_t>(rows);

    bool ok = false;
    do {
        if (ghostty_terminal_new(
                nullptr,
                &native->terminal,
                static_cast<uint16_t>(cols),
                static_cast<uint16_t>(rows)) != GHOSTTY_SUCCESS ||
            native->terminal == nullptr) {
            break;
        }

        if (ghostty_render_state_new(nullptr, &native->render_state) != GHOSTTY_SUCCESS ||
            native->render_state == nullptr) {
            break;
        }

        if (ghostty_render_state_row_iterator_new(
                nullptr, &native->row_iter) != GHOSTTY_SUCCESS ||
            native->row_iter == nullptr) {
            break;
        }

        if (ghostty_render_state_row_cells_new(
                nullptr, &native->row_cells) != GHOSTTY_SUCCESS ||
            native->row_cells == nullptr) {
            break;
        }

        if (ghostty_search_new(nullptr, &native->search, native->terminal) != GHOSTTY_SUCCESS ||
            native->search == nullptr) {
            break;
        }
        if (ghostty_mouse_encoder_new(nullptr, &native->mouse_encoder) != GHOSTTY_SUCCESS ||
            native->mouse_encoder == nullptr ||
            ghostty_mouse_event_new(nullptr, &native->mouse_event) != GHOSTTY_SUCCESS ||
            native->mouse_event == nullptr) {
            break;
        }
        if (ghostty_key_encoder_new(nullptr, &native->key_encoder) != GHOSTTY_SUCCESS ||
            native->key_encoder == nullptr ||
            ghostty_key_event_new(nullptr, &native->key_event) != GHOSTTY_SUCCESS ||
            native->key_event == nullptr) {
            break;
        }
        if (ghostty_selection_gesture_new(nullptr, &native->selection_gesture) != GHOSTTY_SUCCESS ||
            native->selection_gesture == nullptr) {
            break;
        }

        ghostty_terminal_set(
            native->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, native);
        ghostty_terminal_set(
            native->terminal,
            GHOSTTY_TERMINAL_OPT_WRITE_PTY,
            (const void*)writePtyCallback);
        ghostty_terminal_set(
            native->terminal, GHOSTTY_TERMINAL_OPT_BELL,
            (const void*)bellCallback);
        ghostty_terminal_set(
            native->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED,
            (const void*)titleChangedCallback);
        ghostty_terminal_set(
            native->terminal, GHOSTTY_TERMINAL_OPT_PWD_CHANGED,
            (const void*)pwdChangedCallback);

        ok = true;
    } while (false);

    if (!ok) {
        freeNativeTerminal(native);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "failed to create managed ghostty terminal");
        return 0;
    }

    return reinterpret_cast<jlong>(native);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeFreeManaged(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle == 0) return;
    freeNativeTerminal(fromHandle(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeVersion(
    JNIEnv* env,
    jobject /* thiz */) {
    GhosttyString version{};
    const GhosttyResult result = ghostty_build_info(
        GHOSTTY_BUILD_INFO_VERSION_STRING,
        &version);
    if (result != GHOSTTY_SUCCESS || version.ptr == nullptr || version.len == 0) {
        return env->NewStringUTF("");
    }
    const auto* versionBytes = reinterpret_cast<const char*>(version.ptr);
    return env->NewStringUTF(std::string(versionBytes, version.len).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeReset(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return;
    }
    native->generation += 1;
    native->pending_pty_writes.clear();
    if (native->selection_gesture != nullptr) {
        ghostty_selection_gesture_reset(native->selection_gesture, native->terminal);
    }
    ghostty_terminal_reset(native->terminal);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeWrite(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jbyteArray data) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return;
    }
    if (data == nullptr) return;

    const jsize len = env->GetArrayLength(data);
    if (len == 0) return;

    std::vector<jbyte> buffer(static_cast<size_t>(len));
    env->GetByteArrayRegion(data, 0, len, buffer.data());
    if (env->ExceptionCheck()) return;

    // Do NOT clear pending writes here. The Kotlin engine drains them after
    // every vt_write; clearing first would drop query responses if a previous
    // drain was not yet performed.
    ghostty_terminal_vt_write(
        native->terminal,
        reinterpret_cast<const uint8_t*>(buffer.data()),
        static_cast<size_t>(len));
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeResize(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint cols,
    jint rows,
    jint cell_width_px,
    jint cell_height_px) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return;
    }
    if (cols <= 0 || rows <= 0 || cell_width_px <= 0 || cell_height_px <= 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "resize dimensions must be positive");
        return;
    }
    native->cols = static_cast<uint32_t>(cols);
    native->rows = static_cast<uint32_t>(rows);
    native->cell_width_px = static_cast<uint32_t>(cell_width_px);
    native->cell_height_px = static_cast<uint32_t>(cell_height_px);
    ghostty_terminal_resize(
        native->terminal,
        static_cast<uint16_t>(cols),
        static_cast<uint16_t>(rows),
        static_cast<uint32_t>(cell_width_px),
        static_cast<uint32_t>(cell_height_px));
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeScrollViewport(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint delta_rows) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return;
    }
    GhosttyTerminalScrollViewport behavior{};
    behavior.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
    behavior.value.delta = delta_rows;
    ghostty_terminal_scroll_viewport(native->terminal, behavior);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSelectAll(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return JNI_FALSE;
    }

    GhosttySelection selection = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_select_all(native->terminal, &selection) != GHOSTTY_SUCCESS) {
        return JNI_FALSE;
    }
    return ghostty_terminal_set(
        native->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &selection) == GHOSTTY_SUCCESS
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeCopySelection(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return nullptr;
    }

    GhosttyTerminalSelectionFormatOptions options =
        GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
    options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    options.trim = true;
    options.selection = nullptr;

    uint8_t* buffer = nullptr;
    size_t length = 0;
    const GhosttyResult result = ghostty_terminal_selection_format_alloc(
        native->terminal, nullptr, options, &buffer, &length);
    if (result != GHOSTTY_SUCCESS || buffer == nullptr || length == 0) {
        if (buffer != nullptr) ghostty_free(nullptr, buffer, length);
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(length));
    if (out != nullptr) {
        env->SetByteArrayRegion(
            out, 0, static_cast<jsize>(length),
            reinterpret_cast<const jbyte*>(buffer));
    }
    ghostty_free(nullptr, buffer, length);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSelectionPress(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint col,
    jint row) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return JNI_FALSE;
    }
    return applySelectionEvent(
        native, GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_PRESS, col, row)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSelectionDrag(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint col,
    jint row) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return JNI_FALSE;
    }
    return applySelectionEvent(
        native, GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG, col, row)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSelectionRelease(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint col,
    jint row) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return JNI_FALSE;
    }
    return applySelectionEvent(
        native, GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE, col, row)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSelectionClear(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return;
    }
    ghostty_terminal_set(native->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, nullptr);
    if (native->selection_gesture != nullptr) {
        ghostty_selection_gesture_reset(native->selection_gesture, native->terminal);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeMouseReportingActive(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return JNI_FALSE;
    }
    return isMouseReportingActive(native) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeLinkUriAt(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint col,
    jint row) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return nullptr;
    }

    GhosttyGridRef ref{};
    if (!gridRefAtViewport(native, col, row, &ref)) return nullptr;

    size_t len = 0;
    if (ghostty_grid_ref_hyperlink_uri(&ref, nullptr, 0, &len) != GHOSTTY_SUCCESS ||
        len == 0) {
        return nullptr;
    }

    std::vector<uint8_t> bytes(len);
    size_t written = 0;
    const GhosttyResult result = ghostty_grid_ref_hyperlink_uri(
        &ref, bytes.data(), bytes.size(), &written);
    if (result != GHOSTTY_SUCCESS || written == 0) return nullptr;
    bytes.resize(written);
    return toJByteArray(env, bytes);
}

namespace {

GhosttyColorRgb colorFromArgb(jint argb) {
    return GhosttyColorRgb{
        .r = static_cast<uint8_t>((argb >> 16) & 0xFF),
        .g = static_cast<uint8_t>((argb >> 8) & 0xFF),
        .b = static_cast<uint8_t>(argb & 0xFF),
    };
}

struct PasteSource {
    const uint8_t* data;
    size_t len;
};

bool pasteTextReader(
    void* userdata,
    GhosttyString /* mime */,
    GhosttyWriter writer) {
    auto* source = static_cast<PasteSource*>(userdata);
    if (source == nullptr || source->data == nullptr) return false;
    return writer.write(writer.userdata, source->data, source->len);
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativePasteText(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jbyteArray data) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return;
    }
    if (data == nullptr) return;

    const jsize len = env->GetArrayLength(data);
    if (len == 0) return;

    std::vector<uint8_t> buffer(static_cast<size_t>(len));
    env->GetByteArrayRegion(
        data, 0, len, reinterpret_cast<jbyte*>(buffer.data()));
    if (env->ExceptionCheck()) return;

    PasteSource source{buffer.data(), buffer.size()};
    static constexpr char kTextPlain[] = "text/plain";
    GhosttyString mime{
        reinterpret_cast<const uint8_t*>(kTextPlain),
        sizeof(kTextPlain) - 1,
    };
    GhosttyPaste paste = GHOSTTY_INIT_SIZED(GhosttyPaste);
    paste.location = GHOSTTY_CLIPBOARD_LOCATION_STANDARD;
    paste.source = GHOSTTY_PASTE_SOURCE_TEXT;
    paste.mimes = &mime;
    paste.mimes_len = 1;
    paste.reader = GhosttyMimeReader{pasteTextReader, &source};
    paste.allow_unsafe = false;

    bool written = false;
    ghostty_terminal_paste(native->terminal, &paste, &written);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSetDefaultColors(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint background_argb,
    jint foreground_argb,
    jint cursor_argb) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return;
    }

    const GhosttyColorRgb background = colorFromArgb(background_argb);
    const GhosttyColorRgb foreground = colorFromArgb(foreground_argb);
    const GhosttyColorRgb cursor = colorFromArgb(cursor_argb);
    ghostty_terminal_set(
        native->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &background);
    ghostty_terminal_set(
        native->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &foreground);
    ghostty_terminal_set(
        native->terminal, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeTakeEventFlags(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) return 0;
    const uint32_t events = native->pending_events;
    native->pending_events = 0;
    return static_cast<jint>(events);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeGetTitle(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    return terminalStringData(env, fromHandle(handle), GHOSTTY_TERMINAL_DATA_TITLE);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeGetPwd(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    return terminalStringData(env, fromHandle(handle), GHOSTTY_TERMINAL_DATA_PWD);
}

namespace {

jint searchTotalMatches(GhosttySearch search) {
    size_t total = 0;
    if (ghostty_search_get(search, GHOSTTY_SEARCH_DATA_TOTAL_MATCHES, &total) != GHOSTTY_SUCCESS) {
        return 0;
    }
    return static_cast<jint>(total);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSearchSet(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jbyteArray query) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed || native->search == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return 0;
    }

    if (query == nullptr) {
        ghostty_search_set(native->search, GHOSTTY_SEARCH_OPT_NEEDLE, nullptr);
        return 0;
    }

    const jsize len = env->GetArrayLength(query);
    std::vector<uint8_t> bytes(static_cast<size_t>(len));
    if (len > 0) {
        env->GetByteArrayRegion(query, 0, len, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return 0;
    }

    GhosttyString needle{bytes.data(), bytes.size()};
    ghostty_search_set(native->search, GHOSTTY_SEARCH_OPT_NEEDLE, &needle);
    ghostty_search_run(native->search);
    return searchTotalMatches(native->search);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSearchSelect(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jboolean backwards) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed || native->search == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return -1;
    }

    const GhosttySearchOption option = backwards
        ? GHOSTTY_SEARCH_OPT_SELECT_PREV
        : GHOSTTY_SEARCH_OPT_SELECT_NEXT;
    if (ghostty_search_set(native->search, option, nullptr) != GHOSTTY_SUCCESS) {
        return -1;
    }

    size_t selected = 0;
    if (ghostty_search_get(
            native->search, GHOSTTY_SEARCH_DATA_SELECTED_INDEX, &selected) != GHOSTTY_SUCCESS) {
        return -1;
    }
    return static_cast<jint>(selected);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSearchTotal(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed || native->search == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return 0;
    }
    return searchTotalMatches(native->search);
}

GhosttyKey ghosttyKeyFromAndroidKeyCode(jint key_code) {
    switch (key_code) {
        case AKEYCODE_0: return GHOSTTY_KEY_DIGIT_0;
        case AKEYCODE_1: return GHOSTTY_KEY_DIGIT_1;
        case AKEYCODE_2: return GHOSTTY_KEY_DIGIT_2;
        case AKEYCODE_3: return GHOSTTY_KEY_DIGIT_3;
        case AKEYCODE_4: return GHOSTTY_KEY_DIGIT_4;
        case AKEYCODE_5: return GHOSTTY_KEY_DIGIT_5;
        case AKEYCODE_6: return GHOSTTY_KEY_DIGIT_6;
        case AKEYCODE_7: return GHOSTTY_KEY_DIGIT_7;
        case AKEYCODE_8: return GHOSTTY_KEY_DIGIT_8;
        case AKEYCODE_9: return GHOSTTY_KEY_DIGIT_9;
        case AKEYCODE_A: return GHOSTTY_KEY_A;
        case AKEYCODE_B: return GHOSTTY_KEY_B;
        case AKEYCODE_C: return GHOSTTY_KEY_C;
        case AKEYCODE_D: return GHOSTTY_KEY_D;
        case AKEYCODE_E: return GHOSTTY_KEY_E;
        case AKEYCODE_F: return GHOSTTY_KEY_F;
        case AKEYCODE_G: return GHOSTTY_KEY_G;
        case AKEYCODE_H: return GHOSTTY_KEY_H;
        case AKEYCODE_I: return GHOSTTY_KEY_I;
        case AKEYCODE_J: return GHOSTTY_KEY_J;
        case AKEYCODE_K: return GHOSTTY_KEY_K;
        case AKEYCODE_L: return GHOSTTY_KEY_L;
        case AKEYCODE_M: return GHOSTTY_KEY_M;
        case AKEYCODE_N: return GHOSTTY_KEY_N;
        case AKEYCODE_O: return GHOSTTY_KEY_O;
        case AKEYCODE_P: return GHOSTTY_KEY_P;
        case AKEYCODE_Q: return GHOSTTY_KEY_Q;
        case AKEYCODE_R: return GHOSTTY_KEY_R;
        case AKEYCODE_S: return GHOSTTY_KEY_S;
        case AKEYCODE_T: return GHOSTTY_KEY_T;
        case AKEYCODE_U: return GHOSTTY_KEY_U;
        case AKEYCODE_V: return GHOSTTY_KEY_V;
        case AKEYCODE_W: return GHOSTTY_KEY_W;
        case AKEYCODE_X: return GHOSTTY_KEY_X;
        case AKEYCODE_Y: return GHOSTTY_KEY_Y;
        case AKEYCODE_Z: return GHOSTTY_KEY_Z;
        case AKEYCODE_COMMA: return GHOSTTY_KEY_COMMA;
        case AKEYCODE_PERIOD: return GHOSTTY_KEY_PERIOD;
        case AKEYCODE_MINUS: return GHOSTTY_KEY_MINUS;
        case AKEYCODE_EQUALS: return GHOSTTY_KEY_EQUAL;
        case AKEYCODE_SEMICOLON: return GHOSTTY_KEY_SEMICOLON;
        case AKEYCODE_APOSTROPHE: return GHOSTTY_KEY_QUOTE;
        case AKEYCODE_SLASH: return GHOSTTY_KEY_SLASH;
        case AKEYCODE_BACKSLASH: return GHOSTTY_KEY_BACKSLASH;
        case AKEYCODE_LEFT_BRACKET: return GHOSTTY_KEY_BRACKET_LEFT;
        case AKEYCODE_RIGHT_BRACKET: return GHOSTTY_KEY_BRACKET_RIGHT;
        case AKEYCODE_GRAVE: return GHOSTTY_KEY_BACKQUOTE;
        case AKEYCODE_ENTER: return GHOSTTY_KEY_ENTER;
        case AKEYCODE_TAB: return GHOSTTY_KEY_TAB;
        case AKEYCODE_SPACE: return GHOSTTY_KEY_SPACE;
        case AKEYCODE_DEL: return GHOSTTY_KEY_BACKSPACE;
        case AKEYCODE_FORWARD_DEL: return GHOSTTY_KEY_DELETE;
        case AKEYCODE_ESCAPE: return GHOSTTY_KEY_ESCAPE;
        case AKEYCODE_DPAD_UP: return GHOSTTY_KEY_ARROW_UP;
        case AKEYCODE_DPAD_DOWN: return GHOSTTY_KEY_ARROW_DOWN;
        case AKEYCODE_DPAD_LEFT: return GHOSTTY_KEY_ARROW_LEFT;
        case AKEYCODE_DPAD_RIGHT: return GHOSTTY_KEY_ARROW_RIGHT;
        case AKEYCODE_MOVE_HOME: return GHOSTTY_KEY_HOME;
        case AKEYCODE_MOVE_END: return GHOSTTY_KEY_END;
        case AKEYCODE_PAGE_UP: return GHOSTTY_KEY_PAGE_UP;
        case AKEYCODE_PAGE_DOWN: return GHOSTTY_KEY_PAGE_DOWN;
        case AKEYCODE_INSERT: return GHOSTTY_KEY_INSERT;
        case AKEYCODE_F1: return GHOSTTY_KEY_F1;
        case AKEYCODE_F2: return GHOSTTY_KEY_F2;
        case AKEYCODE_F3: return GHOSTTY_KEY_F3;
        case AKEYCODE_F4: return GHOSTTY_KEY_F4;
        case AKEYCODE_F5: return GHOSTTY_KEY_F5;
        case AKEYCODE_F6: return GHOSTTY_KEY_F6;
        case AKEYCODE_F7: return GHOSTTY_KEY_F7;
        case AKEYCODE_F8: return GHOSTTY_KEY_F8;
        case AKEYCODE_F9: return GHOSTTY_KEY_F9;
        case AKEYCODE_F10: return GHOSTTY_KEY_F10;
        case AKEYCODE_F11: return GHOSTTY_KEY_F11;
        case AKEYCODE_F12: return GHOSTTY_KEY_F12;
        case AKEYCODE_NUMPAD_0: return GHOSTTY_KEY_NUMPAD_0;
        case AKEYCODE_NUMPAD_1: return GHOSTTY_KEY_NUMPAD_1;
        case AKEYCODE_NUMPAD_2: return GHOSTTY_KEY_NUMPAD_2;
        case AKEYCODE_NUMPAD_3: return GHOSTTY_KEY_NUMPAD_3;
        case AKEYCODE_NUMPAD_4: return GHOSTTY_KEY_NUMPAD_4;
        case AKEYCODE_NUMPAD_5: return GHOSTTY_KEY_NUMPAD_5;
        case AKEYCODE_NUMPAD_6: return GHOSTTY_KEY_NUMPAD_6;
        case AKEYCODE_NUMPAD_7: return GHOSTTY_KEY_NUMPAD_7;
        case AKEYCODE_NUMPAD_8: return GHOSTTY_KEY_NUMPAD_8;
        case AKEYCODE_NUMPAD_9: return GHOSTTY_KEY_NUMPAD_9;
        case AKEYCODE_NUMPAD_ADD: return GHOSTTY_KEY_NUMPAD_ADD;
        case AKEYCODE_NUMPAD_SUBTRACT: return GHOSTTY_KEY_NUMPAD_SUBTRACT;
        case AKEYCODE_NUMPAD_MULTIPLY: return GHOSTTY_KEY_NUMPAD_MULTIPLY;
        case AKEYCODE_NUMPAD_DIVIDE: return GHOSTTY_KEY_NUMPAD_DIVIDE;
        case AKEYCODE_NUMPAD_DOT: return GHOSTTY_KEY_NUMPAD_DECIMAL;
        case AKEYCODE_NUMPAD_ENTER: return GHOSTTY_KEY_NUMPAD_ENTER;
        default: return GHOSTTY_KEY_UNIDENTIFIED;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeEncodeKey(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint action,
    jint key_code,
    jint mods,
    jint unshifted_codepoint,
    jbyteArray utf8) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed ||
        native->key_encoder == nullptr || native->key_event == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return nullptr;
    }

    ghostty_key_encoder_setopt_from_terminal(native->key_encoder, native->terminal);

    ghostty_key_event_set_action(
        native->key_event, static_cast<GhosttyKeyAction>(action));
    ghostty_key_event_set_key(
        native->key_event, ghosttyKeyFromAndroidKeyCode(key_code));
    ghostty_key_event_set_mods(native->key_event, static_cast<GhosttyMods>(mods));
    ghostty_key_event_set_unshifted_codepoint(
        native->key_event, static_cast<uint32_t>(unshifted_codepoint));
    ghostty_key_event_set_composing(native->key_event, false);

    if (utf8 != nullptr) {
        const jsize len = env->GetArrayLength(utf8);
        std::vector<char> text(static_cast<size_t>(len));
        if (len > 0) {
            env->GetByteArrayRegion(utf8, 0, len, reinterpret_cast<jbyte*>(text.data()));
            if (env->ExceptionCheck()) return nullptr;
        }
        ghostty_key_event_set_utf8(native->key_event, text.data(), text.size());
    } else {
        ghostty_key_event_set_utf8(native->key_event, nullptr, 0);
    }

    size_t required = 0;
    GhosttyResult result = ghostty_key_encoder_encode(
        native->key_encoder, native->key_event, nullptr, 0, &required);
    if (result != GHOSTTY_OUT_OF_SPACE && result != GHOSTTY_SUCCESS) {
        return nullptr;
    }
    if (required == 0) return nullptr;

    std::vector<uint8_t> bytes(required);
    size_t written = 0;
    result = ghostty_key_encoder_encode(
        native->key_encoder, native->key_event,
        reinterpret_cast<char*>(bytes.data()), bytes.size(), &written);
    if (result != GHOSTTY_SUCCESS) return nullptr;
    bytes.resize(written);

    jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (out != nullptr && !bytes.empty()) {
        env->SetByteArrayRegion(
            out, 0, static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeEncodeMouse(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint action,
    jint button,
    jint mods,
    jfloat x,
    jfloat y,
    jboolean any_button_pressed) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed ||
        native->mouse_encoder == nullptr || native->mouse_event == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return nullptr;
    }

    ghostty_mouse_encoder_setopt_from_terminal(
        native->mouse_encoder, native->terminal);

    ghostty_mouse_event_set_action(
        native->mouse_event, static_cast<GhosttyMouseAction>(action));
    if (button == 0) {
        ghostty_mouse_event_clear_button(native->mouse_event);
    } else {
        ghostty_mouse_event_set_button(
            native->mouse_event, static_cast<GhosttyMouseButton>(button));
    }
    ghostty_mouse_event_set_mods(
        native->mouse_event, static_cast<GhosttyMods>(mods));
    ghostty_mouse_event_set_position(
        native->mouse_event, GhosttyMousePosition{x, y});

    const GhosttyMouseEncoderOption pressed_opt =
        GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED;
    ghostty_mouse_encoder_setopt(
        native->mouse_encoder, pressed_opt, &any_button_pressed);

    size_t required = 0;
    GhosttyResult result = ghostty_mouse_encoder_encode(
        native->mouse_encoder, native->mouse_event, nullptr, 0, &required);
    if (result != GHOSTTY_OUT_OF_SPACE && result != GHOSTTY_SUCCESS) {
        return nullptr;
    }
    if (required == 0) return nullptr;

    std::vector<uint8_t> bytes(required);
    size_t written = 0;
    result = ghostty_mouse_encoder_encode(
        native->mouse_encoder, native->mouse_event,
        reinterpret_cast<char*>(bytes.data()), bytes.size(), &written);
    if (result != GHOSTTY_SUCCESS) return nullptr;
    bytes.resize(written);

    jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (out != nullptr && !bytes.empty()) {
        env->SetByteArrayRegion(
            out, 0, static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeSearchClear(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed || native->search == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return;
    }
    ghostty_search_set(native->search, GHOSTTY_SEARCH_OPT_NEEDLE, nullptr);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeDrainPtyWrites(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        return nullptr;
    }
    jbyteArray out = toJByteArray(env, native->pending_pty_writes);
    native->pending_pty_writes.clear();
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeRenderSnapshot(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jobject buffer) {
    auto* native = fromHandle(handle);
    if (native == nullptr || native->closed) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "native terminal already closed");
        return -1;
    }

    auto* address = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity <= 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "render snapshot requires a direct ByteBuffer");
        return -1;
    }

    std::vector<uint8_t> snapshot;
    if (!buildRenderSnapshot(native, snapshot)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "failed to build ghostty render snapshot");
        return -1;
    }

    if (snapshot.size() > static_cast<size_t>(capacity)) {
        // Do not clean render state: the caller can retry with a larger
        // buffer and we must not lose the dirty frame.
        return -1;
    }
    if (!snapshot.empty()) {
        std::memcpy(address, snapshot.data(), snapshot.size());
    }

    ghostty_render_state_clean(native->render_state);
    if (snapshot.size() < 48) return 0;
    int32_t row_count = 0;
    std::memcpy(&row_count, snapshot.data() + 44, sizeof(row_count));
    return row_count;
}
