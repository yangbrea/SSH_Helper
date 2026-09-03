// JNI smoke bridge for libghostty-vt.
//
// Step 2 intentionally exposes only terminal lifecycle and version query.
// Later migration steps add vt_write/resize/render/input/selection/search.
//
// A native handle is an opaque GhosttyTerminal pointer stored as a jlong.
// Handles are single-threaded by contract: do not call native methods for the
// same handle from multiple threads concurrently.

#include <jni.h>

#include <cstdint>
#include <string>

// GHOSTTY_STATIC is provided by CMake target_compile_definitions.
#include <ghostty/vt.h>

namespace {

constexpr const char* kClassName =
    "com/yang136/sshhelper/terminal/GhosttyNativeBridge";

GhosttyTerminal fromHandle(jlong handle) {
    return reinterpret_cast<GhosttyTerminal>(handle);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jint cols,
    jint rows) {
    if (cols <= 0 || rows <= 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "Ghostty terminal cols/rows must be positive");
        return 0;
    }

    GhosttyTerminal terminal = nullptr;
    const GhosttyResult result = ghostty_terminal_new(
        /* allocator */ nullptr,
        &terminal,
        static_cast<uint16_t>(cols),
        static_cast<uint16_t>(rows));

    if (result != GHOSTTY_SUCCESS || terminal == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "ghostty_terminal_new failed");
        return 0;
    }
    return reinterpret_cast<jlong>(terminal);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_terminal_GhosttyNativeBridge_nativeFree(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle == 0) {
        return;
    }
    ghostty_terminal_free(fromHandle(handle));
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
