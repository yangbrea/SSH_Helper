package com.yang136.sshhelper.ssh.native

/**
 * Small lifecycle wrapper around one native SSH runtime handle.
 *
 * The handle is created lazily and closed exactly once. All methods submit an
 * operation to the runtime owner thread and block until the completion event is
 * returned, so callers should use them from a background dispatcher.
 */
class NativeSshRuntime {
    private var handle = 0L

    fun ensureCreated() {
        if (handle == 0L) {
            handle = NativeSshBridge.nativeCreate()
        }
    }

    fun runTcpConnect(
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunTcpConnect(
            handle, host, port, timeoutMillis,
        )
    }

    fun runOpenSession(
        username: String,
        password: String,
        expectedFingerprint: String,
        timeoutMillis: Long,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunOpenSession(
            handle, username, password, expectedFingerprint, timeoutMillis,
        )
    }

    fun runOpenSessionWithPrivateKey(
        username: String,
        privateKey: ByteArray,
        passphrase: String?,
        expectedFingerprint: String,
        timeoutMillis: Long,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunOpenSessionWithPrivateKey(
            handle, username, privateKey, passphrase, expectedFingerprint,
            timeoutMillis,
        )
    }

    fun runPersistentExec(
        command: String,
        maxOutputBytes: Int,
        timeoutMillis: Long,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunPersistentExec(
            handle, command, maxOutputBytes, timeoutMillis,
        )
    }

    fun runPendingTcpHandshake(
        timeoutMillis: Long,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunPendingTcpHandshake(
            handle, timeoutMillis,
        )
    }

    fun runPendingDirectPasswordExec(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        expectedFingerprint: String,
        connectTimeoutMillis: Long,
        execTimeoutMillis: Long,
        maxOutputBytes: Int,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunPendingDirectPasswordExec(
            handle, host, port, username, password, command,
            expectedFingerprint, connectTimeoutMillis, execTimeoutMillis,
            maxOutputBytes,
        )
    }

    fun runPendingDirectPrivateKeyExec(
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
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunPendingDirectPrivateKeyExec(
            handle, host, port, username, privateKey, passphrase, command,
            expectedFingerprint, connectTimeoutMillis, execTimeoutMillis,
            maxOutputBytes,
        )
    }

    fun runDirectPasswordExec(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        expectedFingerprint: String,
        connectTimeoutMillis: Long,
        execTimeoutMillis: Long,
        maxOutputBytes: Int,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunDirectPasswordExec(
            handle, host, port, username, password, command,
            expectedFingerprint, connectTimeoutMillis, execTimeoutMillis,
            maxOutputBytes,
        )
    }

    fun runDirectPrivateKeyExec(
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
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunDirectPrivateKeyExec(
            handle, host, port, username, privateKey, passphrase, command,
            expectedFingerprint, connectTimeoutMillis, execTimeoutMillis,
            maxOutputBytes,
        )
    }

    fun runTcpHandshake(
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunTcpHandshake(
            handle, host, port, timeoutMillis,
        )
    }

    fun runHttpProxyConnect(
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        username: String,
        password: String,
        timeoutMillis: Long,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunHttpProxyConnect(
            handle, proxyHost, proxyPort, targetHost, targetPort,
            username, password, timeoutMillis,
        )
    }

    fun runSocks5ProxyConnect(
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        username: String,
        password: String,
        timeoutMillis: Long,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunSocks5ProxyConnect(
            handle, proxyHost, proxyPort, targetHost, targetPort,
            username, password, timeoutMillis,
        )
    }

    fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            NativeSshBridge.nativeClose(current)
        }
    }
}
