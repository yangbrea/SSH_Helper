package com.yang136.sshhelper.ssh.native

/**
 * Managed JNI bridge to libsshhelper_ssh.so.
 *
 * Handles are opaque registry IDs rather than native pointers. Close is
 * idempotent and unknown/0 handles are safe. The direct-handshake methods keep
 * a blocking TCP+SSH connection alive at the JNI layer so Kotlin can verify the
 * server host key before any password/private-key authentication is sent.
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

    /**
     * Opens a direct TCP connection and completes the SSH handshake without
     * authenticating. Returns an opaque handle that owns the connection.
     */
    external fun nativeOpenDirectHandshake(host: String, port: Int): Long

    /** Host-key type for an open direct-handshake handle. */
    external fun nativeDirectHostKeyType(handle: Long): String

    /** OpenSSH-style SHA256 fingerprint for an open direct-handshake handle. */
    external fun nativeDirectHostKeyFingerprint(handle: Long): String

    /** Standard padded Base64 key blob for an open direct-handshake handle. */
    external fun nativeDirectHostKeyBase64(handle: Long): String

    /**
     * Authenticates with a password on the open handle, executes one command and
     * returns "exit=N\n" + stdout.
     */
    external fun nativeDirectPasswordExec(
        handle: Long,
        username: String,
        password: String,
        command: String,
    ): String

    /**
     * Authenticates with an in-memory private key on the open handle, executes
     * one command and returns "exit=N\n" + stdout.
     */
    external fun nativeDirectPrivateKeyExec(
        handle: Long,
        username: String,
        privateKey: ByteArray,
        passphrase: String?,
        command: String,
    ): String

    /** Closes a direct-handshake handle; 0, repeated and unknown IDs are safe. */
    external fun nativeDirectClose(handle: Long)

    /**
     * Synchronous direct-connection POC: connect, SSH handshake, password auth,
     * run one command and return "exit=N\n" + stdout. Kept for older POC callers;
     * new host-key-aware code should use the direct-handshake methods above.
     */
    external fun nativeConnectExec(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
    ): String

    /** Same synchronous POC as [nativeConnectExec] but authenticates with an in-memory private key. */
    external fun nativeConnectExecWithPrivateKey(
        host: String,
        port: Int,
        username: String,
        privateKey: ByteArray,
        passphrase: String?,
        command: String,
    ): String
}
