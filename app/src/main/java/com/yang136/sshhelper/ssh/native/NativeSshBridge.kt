package com.yang136.sshhelper.ssh.native

/**
 * Managed JNI bridge to libsshhelper_ssh.so.
 *
 * Handles are opaque registry IDs rather than native pointers. Close is
 * idempotent and unknown/0 handles are safe. All SSH operations are driven by
 * the nonblocking runtime; blocking POC JNI methods have been removed.
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
     * Same as [nativeRunTcpHandshake] but keeps the handshaked unauthenticated
     * connection in the runtime pending session slot for a host-key decision.
     */
    external fun nativeRunTcpHostKeyProbe(
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
     * Same as [nativeRunPendingTcpHandshake] but keeps the handshaked
     * unauthenticated connection in the runtime pending session slot.
     */
    external fun nativeRunPendingHostKeyProbe(
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

    /** Connects a TCP socket and stores it in the runtime's pending transport slot. */
    external fun nativeRunTcpConnect(
        handle: Long,
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): String

    /**
     * Consumes the pending transport, authenticates, and stores the result as
     * the active persistent SSH session. Returns "session=ok".
     */
    external fun nativeRunOpenSession(
        handle: Long,
        username: String,
        password: String,
        expectedFingerprint: String,
        timeoutMillis: Long,
    ): String

    /**
     * Same as [nativeRunOpenSession] but authenticates with an in-memory
     * private key.
     */
    external fun nativeRunOpenSessionWithPrivateKey(
        handle: Long,
        username: String,
        privateKey: ByteArray,
        passphrase: String?,
        expectedFingerprint: String,
        timeoutMillis: Long,
    ): String

    /** Opens the jump hop as a persistent route session, not the active target. */
    external fun nativeRunOpenJumpSession(
        handle: Long,
        username: String,
        password: String,
        expectedFingerprint: String,
        timeoutMillis: Long,
    ): String

    /** Opens the jump hop with an in-memory private key as a route session. */
    external fun nativeRunOpenJumpSessionWithPrivateKey(
        handle: Long,
        username: String,
        privateKey: ByteArray,
        passphrase: String?,
        expectedFingerprint: String,
        timeoutMillis: Long,
    ): String

    /**
     * Uses the already-open jump session to open a temporary direct-tcpip tunnel
     * and perform only the target SSH handshake, returning the target host key.
     */
    external fun nativeRunOpenJumpTargetHandshake(
        handle: Long,
        targetHost: String,
        targetPort: Int,
        timeoutMillis: Long,
    ): String

    /**
     * Same as [nativeRunOpenJumpTargetHandshake] but keeps the handshaked target
     * connection in the runtime pending session slot for a host-key decision.
     */
    external fun nativeRunOpenJumpTargetHostKeyProbe(
        handle: Long,
        targetHost: String,
        targetPort: Int,
        timeoutMillis: Long,
    ): String

    /**
     * Uses the already-open jump session to open a direct-tcpip tunnel to the
     * target, then authenticates the target over that tunnel and stores it as
     * the active persistent SSH session.
     */
    external fun nativeRunOpenJumpTargetSession(
        handle: Long,
        targetHost: String,
        targetPort: Int,
        username: String,
        password: String,
        privateKey: ByteArray?,
        passphrase: String?,
        expectedFingerprint: String,
        timeoutMillis: Long,
    ): String

    /**
     * Continues a held unauthenticated SSH session after a positive host-key
     * decision, authenticating on the same connection and making it active.
     */
    external fun nativeRunContinuePendingSession(
        handle: Long,
        username: String,
        password: String,
        privateKey: ByteArray?,
        passphrase: String?,
        storeAsJump: Boolean,
        timeoutMillis: Long,
    ): String

    /** Closes a held unauthenticated SSH session after reject/timeout/change. */
    external fun nativeRunAbortPendingSession(
        handle: Long,
    ): String

    /** Sends an SSH keepalive on active/jump sessions and waits for a readable reply. */
    external fun nativeRunKeepalive(
        handle: Long,
        timeoutMillis: Long,
    ): String

    /** Runs one exec on the active persistent SSH session. */
    external fun nativeRunPersistentExec(
        handle: Long,
        command: String,
        maxOutputBytes: Int,
        timeoutMillis: Long,
    ): String

    /** Opens a PTY shell channel on the active persistent SSH session. */
    external fun nativeRunOpenShell(
        handle: Long,
        columns: Int,
        rows: Int,
    ): String

    /** Opens a PTY exec channel (used by tmux/zellij persistent sessions). */
    external fun nativeRunOpenPtyExec(
        handle: Long,
        command: String,
        columns: Int,
        rows: Int,
    ): String

    /** Writes bytes to the active shell channel. */
    external fun nativeRunShellWrite(
        handle: Long,
        data: ByteArray,
    ): String

    /** Reads one chunk of bytes from the active shell channel; null on timeout. */
    external fun nativeRunShellRead(
        handle: Long,
        maxBytes: Int,
        timeoutMillis: Long,
    ): ByteArray?

    /** Resizes the active shell PTY. */
    external fun nativeRunShellResize(
        handle: Long,
        columns: Int,
        rows: Int,
    ): String

    /** Closes the active shell channel while leaving the SSH session open. */
    external fun nativeRunCloseShell(
        handle: Long,
    ): String

    /** Starts a native local (-L) port-forwarding listener. */
    external fun nativeRunStartLocalForward(
        handle: Long,
        bindAddress: String,
        listenPort: Int,
        targetHost: String,
        targetPort: Int,
    ): String

    /** Starts a native remote (-R) port-forwarding listener. */
    external fun nativeRunStartRemoteForward(
        handle: Long,
        bindAddress: String,
        listenPort: Int,
        targetHost: String,
        targetPort: Int,
    ): String

    /** Closes a native forwarding listener and its child connections. */
    external fun nativeRunCloseForward(
        handle: Long,
        forwardHandle: Long,
    ): String

    /** Runs an SFTP metadata/path command on the active authenticated session. */
    external fun nativeCreateSftpClient(handle: Long): String
    external fun nativeCloseSftpClient(handle: Long, clientHandle: Long)

    external fun nativeRunSftpCommand(
        handle: Long,
        clientHandle: Long,
        command: Int,
        path: String,
        target: String,
        value: Long,
    ): String

    /** Opens a streaming SFTP file and returns its opaque handle and size. */
    external fun nativeRunSftpOpen(
        handle: Long,
        clientHandle: Long,
        path: String,
        offset: Long,
        write: Boolean,
        truncate: Boolean,
    ): String

    external fun nativeRunSftpRead(handle: Long, fileHandle: Long, maxBytes: Int): ByteArray
    external fun nativeRunSftpWrite(handle: Long, fileHandle: Long, data: ByteArray): Int
    external fun nativeRunSftpClose(handle: Long, fileHandle: Long)

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


}
