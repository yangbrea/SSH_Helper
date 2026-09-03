package com.yang136.sshhelper.terminal

/**
 * Minimal JNI bridge to libghostty-vt.
 *
 * Step 2 deliberately only exposes lifecycle + version. All methods must be
 * called from a single thread per handle; later migration steps will add the
 * full VT/render/input surface.
 */
object GhosttyNativeBridge {
    init {
        System.loadLibrary("sshhelper_terminal")
    }

    /** Returns an opaque handle, or 0/throws when creation fails. */
    external fun nativeCreate(cols: Int, rows: Int): Long

    /** Frees a handle returned by [nativeCreate]; 0 is a safe no-op. */
    external fun nativeFree(handle: Long)

    /** Returns the libghostty-vt version string baked into the native build. */
    external fun nativeVersion(): String
}
