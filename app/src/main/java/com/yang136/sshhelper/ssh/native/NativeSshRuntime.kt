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

    fun runOpenShell(
        columns: Int,
        rows: Int,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunOpenShell(handle, columns, rows)
    }

    fun runOpenPtyExec(
        command: String,
        columns: Int,
        rows: Int,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunOpenPtyExec(handle, command, columns, rows)
    }

    fun runShellWrite(data: ByteArray): String {
        ensureCreated()
        return NativeSshBridge.nativeRunShellWrite(handle, data)
    }

    fun runShellRead(maxBytes: Int, timeoutMillis: Long = 250L): ByteArray? {
        ensureCreated()
        return NativeSshBridge.nativeRunShellRead(handle, maxBytes, timeoutMillis)
    }

    fun runShellResize(columns: Int, rows: Int): String {
        ensureCreated()
        return NativeSshBridge.nativeRunShellResize(handle, columns, rows)
    }

    fun runCloseShell(): String {
        ensureCreated()
        return NativeSshBridge.nativeRunCloseShell(handle)
    }

    fun createSftpClient(): Long {
        ensureCreated()
        return NativeSshBridge.nativeCreateSftpClient(handle)
            .substringAfter("handle=", "")
            .toLongOrNull()
            ?: error("native SFTP client handle missing")
    }

    fun closeSftpClient(clientHandle: Long) {
        if (clientHandle == 0L || handle == 0L) return
        NativeSshBridge.nativeCloseSftpClient(handle, clientHandle)
    }

    fun runSftpCommand(
        clientHandle: Long,
        command: Int,
        path: String,
        target: String = "",
        value: Long = 0,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunSftpCommand(
            handle, clientHandle, command, path, target, value,
        )
    }

    fun runSftpOpen(
        clientHandle: Long,
        path: String,
        offset: Long,
        write: Boolean,
        truncate: Boolean,
    ): String {
        ensureCreated()
        return NativeSshBridge.nativeRunSftpOpen(
            handle, clientHandle, path, offset, write, truncate,
        )
    }

    fun runSftpRead(fileHandle: Long, maxBytes: Int): ByteArray {
        ensureCreated()
        return NativeSshBridge.nativeRunSftpRead(handle, fileHandle, maxBytes)
    }

    fun runSftpWrite(fileHandle: Long, data: ByteArray): Int {
        ensureCreated()
        return NativeSshBridge.nativeRunSftpWrite(handle, fileHandle, data)
    }

    fun runSftpClose(fileHandle: Long) {
        if (fileHandle == 0L || handle == 0L) return
        NativeSshBridge.nativeRunSftpClose(handle, fileHandle)
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
