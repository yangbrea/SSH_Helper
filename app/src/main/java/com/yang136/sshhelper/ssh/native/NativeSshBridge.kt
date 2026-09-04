package com.yang136.sshhelper.ssh.native

/**
 * Minimal managed JNI bridge to libsshhelper_ssh.so.
 *
 * Handles are opaque registry IDs rather than native pointers. Later migration
 * steps will add connect/auth/shell/SFTP/forward commands behind this bridge;
 * this initial surface only proves native library loading and handle lifecycle.
 */
object NativeSshBridge {
    init {
        System.loadLibrary("sshhelper_ssh")
    }

    /** Returns the libssh2/OpenSSL versions compiled into the native library. */
    external fun nativeVersion(): String

    /** Returns a stable capability string, including the ABI and algorithm policy. */
    external fun nativeCapabilities(): String

    /** Creates a native SSH runtime handle and returns an opaque ID. */
    external fun nativeCreate(): Long

    /** Closes a native SSH runtime handle; 0, repeated and unknown IDs are safe. */
    external fun nativeClose(handle: Long)
}
