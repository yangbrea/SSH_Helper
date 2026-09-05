package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.data.KnownHostEntity
import com.yang136.sshhelper.ssh.native.NativeSshBridge
import com.yang136.sshhelper.ssh.native.NativeSshRuntime
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * libssh2-backed [SshSession] with in-memory host-key verification.
 *
 * This implementation verifies host keys during connect and, for subsequent
 * exec calls with a stored fingerprint, uses the nonblocking runtime JNI path
 * with deadline/output-limit support. Persistent channels, SFTP, forwarding,
 * proxy/jump and full runtime host-key decisions are not implemented yet.
 */
class Libssh2SshSession(
    private val knownHostDao: KnownHostDao? = null,
    private val allowHostKeyPrompt: Boolean = true,
) : SshSession, SftpCapableSession, PortForwardCapableSession {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val mutableOutput = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    private val mutableTerminalState = MutableStateFlow<TerminalChannelState>(TerminalChannelState.Closed)
    private val mutableHostKeyRequest = MutableStateFlow<HostKeyRequest?>(null)
    private val mutableStage = MutableStateFlow(ConnectionStage.READY)
    private val hostKeyDecision = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val knownHostCache = mutableMapOf<String, KnownHostEntity>()

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    override val output: Flow<ByteArray> = mutableOutput.asSharedFlow()
    override val terminalState: StateFlow<TerminalChannelState> = mutableTerminalState.asStateFlow()
    override val hostKeyRequest: StateFlow<HostKeyRequest?> = mutableHostKeyRequest.asStateFlow()
    override val stage: StateFlow<ConnectionStage> = mutableStage.asStateFlow()

    private var route: SshRoute? = null
    private var password: String? = null
    private var privateKey: ByteArray? = null
    private var passphrase: String? = null
    @Volatile private var liveHandle = 0L
    private val nativeRuntime = NativeSshRuntime()
    @Volatile private var closed = false

    override suspend fun connect(
        route: SshRoute,
        credentials: RouteCredentials,
        openShell: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        if (route.jump != null) {
            mutableState.value = ConnectionState.Error("libssh2 POC 暂不支持跳板机")
            return@withContext
        }
        val targetCredential = credentials.target
        if (targetCredential !is Credential.Password && targetCredential !is Credential.PrivateKey) {
            mutableState.value = ConnectionState.Error("不支持的认证类型")
            return@withContext
        }
        mutableState.value = ConnectionState.Connecting
        mutableStage.value = ConnectionStage.TARGET_AUTH
        try {
            val passwordText = (targetCredential as? Credential.Password)?.value?.concatToString()
            val privateKeyText = (targetCredential as? Credential.PrivateKey)?.bytes
            val passphraseText = (targetCredential as? Credential.PrivateKey)?.passphrase?.concatToString()
            verifiedExec(
                route = route,
                username = route.target.username,
                password = passwordText,
                privateKey = privateKeyText,
                passphrase = passphraseText,
                command = "true",
            )
            this@Libssh2SshSession.route = route
            password = passwordText
            if (privateKeyText != null) {
                privateKey = privateKeyText.copyOf()
                passphrase = passphraseText
            } else {
                privateKey = null
                passphrase = null
            }
            mutableStage.value = ConnectionStage.READY
            mutableState.value = ConnectionState.Connected("${route.target.username}@${route.target.hostname}")
        } catch (error: HostKeyBlockedException) {
            if (error.request.issue == HostKeyIssue.CHANGED) {
                mutableHostKeyRequest.value = error.request
            }
            mutableStage.value = ConnectionStage.READY
            mutableState.value = ConnectionState.Error(
                if (error.request.issue == HostKeyIssue.CHANGED) {
                    "主机密钥已变化，连接已阻止"
                } else {
                    "主机密钥未确认"
                },
            )
        } catch (error: Throwable) {
            mutableStage.value = ConnectionStage.READY
            mutableState.value = ConnectionState.Error(error.message ?: "SSH 连接失败")
        }
    }

    override suspend fun execute(
        command: String,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): RemoteCommandResult = withContext(Dispatchers.IO) {
        val activeRoute = route
        val activePassword = password
        if (activeRoute == null || (activePassword == null && privateKey == null)) {
            return@withContext RemoteCommandResult(
                exitCode = REMOTE_COMMAND_TIMEOUT_EXIT_CODE,
                stdout = "",
                stderr = "SSH 连接不可用",
            )
        }
        try {
            val expectedFingerprint = findKnownHost(
                activeRoute.target.hostname,
                activeRoute.target.port,
            )?.fingerprintSha256
            val raw = if (expectedFingerprint != null) {
                val host = activeRoute.target.hostname
                val port = activeRoute.target.port
                val username = activeRoute.target.username
                if (activePassword != null) {
                    nativeRuntime.runDirectPasswordExec(
                        host,
                        port,
                        username,
                        activePassword,
                        command,
                        expectedFingerprint,
                        SSH_CONNECT_TIMEOUT_MS.toLong(),
                        timeoutMillis,
                        maxOutputBytes,
                    )
                } else {
                    val key = privateKey ?: error("SSH private key unavailable")
                    nativeRuntime.runDirectPrivateKeyExec(
                        host,
                        port,
                        username,
                        key,
                        passphrase,
                        command,
                        expectedFingerprint,
                        SSH_CONNECT_TIMEOUT_MS.toLong(),
                        timeoutMillis,
                        maxOutputBytes,
                    )
                }
            } else {
                verifiedExec(
                    route = activeRoute,
                    username = activeRoute.target.username,
                    password = activePassword,
                    privateKey = privateKey,
                    passphrase = passphrase,
                    command = command,
                )
            }
            parseExecPayload(raw)
        } catch (error: HostKeyBlockedException) {
            if (error.request.issue == HostKeyIssue.CHANGED) {
                mutableHostKeyRequest.value = error.request
                mutableState.value = ConnectionState.Error("主机密钥已变化，连接已阻止")
            }
            RemoteCommandResult(
                exitCode = REMOTE_COMMAND_TIMEOUT_EXIT_CODE,
                stdout = "",
                stderr = error.message ?: "主机密钥验证失败",
            )
        } catch (error: Throwable) {
            val message = error.message ?: "native exec failed"
            if (message.contains("host key does not match", ignoreCase = true) ||
                message.contains("host_key_mismatch", ignoreCase = true)
            ) {
                mutableState.value = ConnectionState.Error("主机密钥已变化，连接已阻止")
            }
            RemoteCommandResult(
                exitCode = REMOTE_COMMAND_TIMEOUT_EXIT_CODE,
                stdout = "",
                stderr = message,
            )
        }
    }

    private fun parseExecPayload(raw: String): RemoteCommandResult {
        val newline = raw.indexOf('\n')
        val exitLine = if (newline >= 0) raw.substring(0, newline) else raw
        val afterExit = if (newline >= 0) raw.substring(newline + 1) else ""
        val exitCode = exitLine.removePrefix("exit=").toIntOrNull() ?: 0

        val stderrMarker = "\nSTDERR_BEGIN\n"
        val stderrIndex = afterExit.indexOf(stderrMarker)
        if (stderrIndex < 0) {
            return RemoteCommandResult(
                exitCode = exitCode,
                stdout = afterExit,
                stderr = "",
            )
        }
        val stdout = afterExit.substring(0, stderrIndex)
        val stderrEndMarker = "\nSTDERR_END\n"
        val stderrStart = stderrIndex + stderrMarker.length
        val stderrEnd = afterExit.indexOf(stderrEndMarker, stderrStart)
        val stderr = if (stderrEnd >= 0) {
            afterExit.substring(stderrStart, stderrEnd)
        } else {
            afterExit.substring(stderrStart)
        }
        return RemoteCommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
        )
    }

    private suspend fun verifiedExec(
        route: SshRoute,
        username: String,
        password: String?,
        privateKey: ByteArray?,
        passphrase: String?,
        command: String,
    ): String {
        var handle = 0L
        try {
            handle = openVerifiedHandle(route)
            mutableStage.value = ConnectionStage.TARGET_AUTH
            return if (password != null) {
                NativeSshBridge.nativeDirectPasswordExec(
                    handle,
                    username,
                    password,
                    command,
                )
            } else {
                val key = privateKey ?: error("SSH private key unavailable")
                NativeSshBridge.nativeDirectPrivateKeyExec(
                    handle,
                    username,
                    key,
                    passphrase,
                    command,
                )
            }
        } finally {
            if (handle != 0L) {
                liveHandle = 0L
                runCatching { NativeSshBridge.nativeDirectClose(handle) }
            }
        }
    }

    private suspend fun openVerifiedHandle(route: SshRoute): Long {
        val handle = NativeSshBridge.nativeOpenDirectHandshake(
            route.target.hostname,
            route.target.port,
        )
        liveHandle = handle
        try {
            val request = loadHostKeyRequest(route, handle) ?: return handle
            if (request.issue == HostKeyIssue.CHANGED || !allowHostKeyPrompt) {
                mutableHostKeyRequest.value = request
                closeLiveHandle()
                throw HostKeyBlockedException(request)
            }
            mutableHostKeyRequest.value = request
            val accepted = awaitHostKeyDecision()
            mutableHostKeyRequest.value = null
            if (!accepted) {
                closeLiveHandle()
                throw HostKeyBlockedException(request)
            }
            saveKnownHost(route, request)
            return handle
        } catch (error: Throwable) {
            if (liveHandle != 0L) {
                closeLiveHandle()
            }
            throw error
        }
    }

    private suspend fun loadHostKeyRequest(route: SshRoute, handle: Long): HostKeyRequest? {
        val hostname = route.target.hostname
        val port = route.target.port
        mutableStage.value = ConnectionStage.TARGET_HOST_KEY
        val keyType = NativeSshBridge.nativeDirectHostKeyType(handle)
        val fingerprint = NativeSshBridge.nativeDirectHostKeyFingerprint(handle)
        val keyBase64 = NativeSshBridge.nativeDirectHostKeyBase64(handle)
        val expected = findKnownHost(hostname, port)
        return when {
            expected == null -> HostKeyRequest(
                hostname = hostname,
                port = port,
                keyType = keyType,
                fingerprint = fingerprint,
                issue = HostKeyIssue.UNKNOWN,
                subject = HostKeySubject.TARGET,
            )
            sameKey(expected.keyBase64, keyBase64) -> null
            else -> HostKeyRequest(
                hostname = hostname,
                port = port,
                keyType = keyType,
                fingerprint = fingerprint,
                previousFingerprint = expected.fingerprintSha256,
                issue = HostKeyIssue.CHANGED,
                subject = HostKeySubject.TARGET,
            )
        }
    }

    private suspend fun findKnownHost(hostname: String, port: Int): KnownHostEntity? {
        knownHostDao?.let { return it.find(hostname, port) }
        synchronized(knownHostCache) {
            return knownHostCache["${hostname.lowercase()}:$port"]
        }
    }

    private suspend fun saveKnownHost(route: SshRoute, request: HostKeyRequest) {
        val entity = KnownHostEntity(
            id = "${route.target.hostname.lowercase()}:${route.target.port}",
            hostname = route.target.hostname,
            port = route.target.port,
            keyType = request.keyType,
            keyBase64 = NativeSshBridge.nativeDirectHostKeyBase64(liveHandle),
            fingerprintSha256 = request.fingerprint,
        )
        knownHostDao?.insert(entity)
        synchronized(knownHostCache) {
            knownHostCache[entity.id] = entity
        }
    }

    private suspend fun awaitHostKeyDecision(): Boolean {
        val decision = CompletableDeferred<Boolean>()
        hostKeyDecision.set(decision)
        try {
            return withTimeoutOrNull(HOST_KEY_CONFIRM_TIMEOUT_MS) { decision.await() } ?: false
        } finally {
            hostKeyDecision.compareAndSet(decision, null)
        }
    }

    private fun sameKey(expectedBase64: String, presentedBase64: String): Boolean =
        MessageDigest.isEqual(expectedBase64.encodeToByteArray(), presentedBase64.encodeToByteArray())

    private fun closeLiveHandle() {
        val handle = liveHandle
        if (handle != 0L) {
            liveHandle = 0L
            runCatching { NativeSshBridge.nativeDirectClose(handle) }
        }
    }

    private fun cancelPendingHostKey() {
        hostKeyDecision.getAndSet(null)?.complete(false)
        mutableHostKeyRequest.value = null
    }

    override suspend fun openTerminal(target: TerminalTarget) {
        mutableTerminalState.value = TerminalChannelState.Error("libssh2 POC 暂不支持交互终端")
    }

    override suspend fun closeTerminal() {
        mutableTerminalState.value = TerminalChannelState.Closed
    }

    override suspend fun write(data: ByteArray) {
        // No persistent channel yet; dropping is acceptable only for the POC.
    }

    override suspend fun resize(columns: Int, rows: Int) = Unit

    override suspend fun disconnect() {
        cancelPendingHostKey()
        closeLiveHandle()
        nativeRuntime.close()
        route = null
        password = null
        privateKey?.fill(0)
        privateKey = null
        passphrase = null
        mutableState.value = ConnectionState.Disconnected("已断开", DisconnectCause.USER)
        mutableTerminalState.value = TerminalChannelState.Closed
    }

    override fun respondToHostKey(accept: Boolean) {
        hostKeyDecision.getAndSet(null)?.complete(accept)
        if (mutableHostKeyRequest.value?.issue == HostKeyIssue.CHANGED) {
            mutableHostKeyRequest.value = null
        }
    }

    override suspend fun openSftpClient(): com.yang136.sshhelper.sftp.SftpClient {
        error("libssh2 POC 暂不支持 SFTP")
    }

    override suspend fun registerForward(request: ForwardRequest): ForwardHandle {
        error("libssh2 POC 暂不支持端口转发")
    }

    override fun close() {
        closed = true
        cancelPendingHostKey()
        closeLiveHandle()
        nativeRuntime.close()
        route = null
        password = null
        privateKey?.fill(0)
        privateKey = null
        passphrase = null
        mutableState.value = ConnectionState.Disconnected("应用已关闭", DisconnectCause.APP_CLOSED)
        mutableTerminalState.value = TerminalChannelState.Closed
    }
}

private class HostKeyBlockedException(
    val request: HostKeyRequest,
) : IllegalStateException("主机密钥验证失败")
