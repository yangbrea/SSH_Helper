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

    native->pending_pty_writes.clear();
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
