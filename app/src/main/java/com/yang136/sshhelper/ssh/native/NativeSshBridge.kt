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

    /** Requests cancellation. Success means the request was still owned by the runtime. */
    external fun nativeCancel(handle: Long, requestId: Long): Boolean

    /**
     * Waits for one copied completion/state event. A zero timeout only polls;
     * a negative timeout waits indefinitely. Returns null on timeout.
     */
    external fun nativeAwaitEvent(handle: Long, timeoutMillis: Long): NativeSshEvent?

    /**
     * Submits a direct TCP+SSH handshake probe and waits for completion.
     */
    external fun nativeRunTcpHandshake(
        handle: Long,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): String

    /**
     * Submits a nonblocking HTTP CONNECT operation to the runtime handle and
     * waits for completion.
     */
    external fun nativeRunHttpProxyConnect(
        handle: Long,
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        username: String,
        password: String,
        timeoutMillis: Long,
    ): String

    /** Submits a nonblocking SOCKS5 CONNECT operation to the runtime handle. */
    external fun nativeRunSocks5ProxyConnect(
        handle: Long,
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        username: String,
        password: String,
        timeoutMillis: Long,
    ): String

    /**
     * Uses the runtime's pending transport fd (previously stored by an HTTP or
     * SOCKS5 CONNECT operation) to perform an SSH handshake and return the host
     * key fields without authenticating.
     */
    external fun nativeRunPendingTcpHandshake(
        handle: Long,
        timeoutMillis: Long,
    ): String

    /**
     * Uses the runtime's pending transport fd to perform SSH handshake, password
     * auth and one exec. Returns the same payload as the direct exec APIs.
     */
    external fun nativeRunPendingDirectPasswordExec(
        handle: Long,
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        expectedFingerprint: String,
        connectTimeoutMillis: Long,
        execTimeoutMillis: Long,
        maxOutputBytes: Int,
    ): String

    /**
     * Uses the runtime's pending transport fd to perform SSH handshake,
     * in-memory private key auth and one exec.
     */
    external fun nativeRunPendingDirectPrivateKeyExec(
        handle: Long,
        host: String,
        port: Int,
        username: String,
        privateKey: ByteArray,
        passphrase: String?,
        command: String,
        expectedFingerprint: String,
        connectTimeoutMillis: Long,
        execTimeoutMillis: Long,
        maxOutputBytes: Int,
    ): String

    /**
     * Submits a direct TCP+SSH password exec operation to the runtime handle and
     * waits for its completion. Returns the same payload as the direct exec APIs.
     */
    external fun nativeRunDirectPasswordExec(
        handle: Long,
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        expectedFingerprint: String,
        connectTimeoutMillis: Long,
        execTimeoutMillis: Long,
        maxOutputBytes: Int,
    ): String

    /**
     * Submits a direct TCP+SSH private-key exec operation to the runtime handle
     * and waits for its completion.
     */
    external fun nativeRunDirectPrivateKeyExec(
        handle: Long,
        host: String,
        port: Int,
        username: String,
        privateKey: ByteArray,
        passphrase: String?,
        command: String,
        expectedFingerprint: String,
        connectTimeoutMillis: Long,
        execTimeoutMillis: Long,
        maxOutputBytes: Int,
    ): String

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
