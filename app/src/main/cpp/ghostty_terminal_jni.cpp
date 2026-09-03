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

struct NativeTerminal {
    GhosttyTerminal terminal = nullptr;
    GhosttyRenderState render_state = nullptr;
    GhosttyRenderStateRowIterator row_iter = nullptr;
    GhosttyRenderStateRowCells row_cells = nullptr;

    // Bytes libghostty asks us to write back to the PTY (DSR/mode queries).
    std::vector<uint8_t> pending_pty_writes;

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

void freeNativeTerminal(NativeTerminal* native) {
    if (native == nullptr) return;
    if (native->closed) {
        // Safety net for accidental double free through a stale Kotlin handle.
        // Kotlin owners should still null/clear their handles after close.
        return;
    }
    native->closed = true;
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

// Snapshot binary format v1 (little-endian).
//
// Header (13 * int32):
//   [0]  version = 1
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
//
// Row record:
//   int32 row_index
//   int32 cell_count
//   repeated cell:
//     int32 fg ARGB
//     int32 bg ARGB
//     uint16 flags
//     uint16 text_len
//     uint8  text[text_len]

constexpr uint16_t kSnapshotVersion = 1;
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

    if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE) {
        putI32(out, kSnapshotVersion);
        putI32(out, static_cast<int32_t>(GHOSTTY_RENDER_STATE_DIRTY_FALSE));
        putI32(out, cols);
        putI32(out, rows);
        putI32(out, argb(colors.background));
        putI32(out, argb(colors.foreground));
        putI32(out, -1);
        putI32(out, -1);
        putI32(out, 0);
        putI32(out, 0);
        putI32(out, 0);
        putI32(out, 0);
        putI32(out, static_cast<int32_t>(native->generation & 0xFFFFFFFFu));
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
        putI32(out, row_y);
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

        ghostty_terminal_set(
            native->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, native);
        ghostty_terminal_set(
            native->terminal,
            GHOSTTY_TERMINAL_OPT_WRITE_PTY,
            (const void*)writePtyCallback);

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
    ghostty_terminal_resize(
        native->terminal,
        static_cast<uint16_t>(cols),
        static_cast<uint16_t>(rows),
        static_cast<uint32_t>(cell_width_px),
        static_cast<uint32_t>(cell_height_px));
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
