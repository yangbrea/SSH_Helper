package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.ForwardType
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.data.KnownHostEntity
import com.yang136.sshhelper.data.ProxyType
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventLevel
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventStage
import com.yang136.sshhelper.diagnosticlog.DiagnosticHop
import com.yang136.sshhelper.diagnosticlog.DiagnosticSink
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceContext
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceSource
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceStatus
import com.yang136.sshhelper.diagnosticlog.NoOpDiagnosticSink
import com.yang136.sshhelper.sftp.NativeSftpClient
import com.yang136.sshhelper.ssh.native.NativeSshException
import com.yang136.sshhelper.ssh.native.NativeSshRuntime
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * This implementation uses the nonblocking runtime JNI path for host-key
 * probing and keeps an authenticated persistent session open for repeated exec
 * calls, including deadline/output-limit support. HTTP/SOCKS5 proxies are
 * supported through the runtime transport handoff path. Shell/PTY and SFTP are
 * wired to persistent native resources. Jump hosts are supported through a
 * nested direct-tcpip transport. Forwarding and full runtime host-key decisions
 * are not implemented yet.
 */
class Libssh2SshSession(
    private val knownHostDao: KnownHostDao? = null,
    private val allowHostKeyPrompt: Boolean = true,
    private val diagnostics: DiagnosticSink = NoOpDiagnosticSink,
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
    private var targetProxyPassword: String? = null
    private var jumpPassword: String? = null
    private var jumpPrivateKey: ByteArray? = null
    private var jumpPassphrase: String? = null
    private var jumpProxyPassword: String? = null
    private var persistentSessionOpen = false
    private val nativeRuntime = NativeSshRuntime()
    private val terminalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sftpClients = ConcurrentHashMap.newKeySet<NativeSftpClient>()
    private val forwardHandles = ConcurrentHashMap.newKeySet<NativeForwardHandle>()
    @Volatile private var terminalReaderJob: Job? = null
    @Volatile private var terminalChannelOpen = false
    @Volatile private var keepaliveJob: Job? = null
    private var activeTraceId: String? = null
    private var ptyColumns = 80
    private var ptyRows = 24
    @Volatile private var closed = false

    private data class TargetProxy(
        val type: ProxyType,
        val host: String,
        val port: Int,
        val username: String?,
        val password: String,
    )

    private inner class NativeForwardHandle(
        private val nativeHandle: Long,
        override val actualListenPort: Int,
    ) : ForwardHandle {
        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            forwardHandles.remove(this)
            nativeRuntime.closeForward(nativeHandle)
        }
    }

    override suspend fun connect(
        route: SshRoute,
        credentials: RouteCredentials,
        openShell: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        val targetCredential = credentials.target
        if (targetCredential !is Credential.Password && targetCredential !is Credential.PrivateKey) {
            mutableState.value = ConnectionState.Error("不支持的认证类型")
            return@withContext
        }
        if (route.jump != null) {
            val jumpCredential = credentials.jump
            if (jumpCredential !is Credential.Password && jumpCredential !is Credential.PrivateKey) {
                mutableState.value = ConnectionState.Error("缺少跳板机凭据或类型不支持")
                return@withContext
            }
        }
        mutableState.value = ConnectionState.Connecting
        mutableStage.value = if (route.jump != null) ConnectionStage.JUMP_AUTH else ConnectionStage.TARGET_AUTH
        activeTraceId = diagnostics.startTrace(
            DiagnosticTraceContext(
                source = DiagnosticTraceSource.SSH_CONNECTION,
                target = "${route.target.username}@${route.target.hostname}:${route.target.port}",
                hostId = route.target.id.takeIf { it > 0 },
                sessionId = route.diagnosticSessionId,
                feature = route.diagnosticFeature ?: if (openShell) "SHELL" else "HEADLESS",
            ),
        )
        diagnostics.record(
            activeTraceId.orEmpty(),
            DiagnosticEventStage.LIFECYCLE,
            "ssh.connect_started",
            "开始建立 SSH 连接",
            hop = if (route.jump == null) DiagnosticHop.DIRECT else DiagnosticHop.JUMP,
            details = mapOf("route" to if (route.jump == null) "direct" else "jump"),
        )
        try {
            val passwordText = (targetCredential as? Credential.Password)?.value?.concatToString()
            val privateKeyText = (targetCredential as? Credential.PrivateKey)?.bytes
            val passphraseText = (targetCredential as? Credential.PrivateKey)?.passphrase?.concatToString()
            val jumpCredential = if (route.jump != null) credentials.jump else null
            targetProxyPassword = credentials.targetProxyPassword
            jumpProxyPassword = credentials.jumpProxyPassword
            if (route.jump != null && jumpCredential != null) {
                val jumpPasswordText = (jumpCredential as? Credential.Password)?.value?.concatToString()
                val jumpPrivateKeyText = (jumpCredential as? Credential.PrivateKey)?.bytes
                val jumpPassphraseText = (jumpCredential as? Credential.PrivateKey)?.passphrase?.concatToString()
                jumpPassword = jumpPasswordText
                if (jumpPrivateKeyText != null) {
                    jumpPrivateKey = jumpPrivateKeyText.copyOf()
                    jumpPassphrase = jumpPassphraseText
                } else {
                    jumpPrivateKey = null
                    jumpPassphrase = null
                }
                openVerifiedJumpPersistentSession(
                    route = route,
                    username = route.target.username,
                    password = passwordText,
                    privateKey = privateKeyText,
                    passphrase = passphraseText,
                    jumpPassword = jumpPasswordText,
                    jumpPrivateKey = jumpPrivateKeyText,
                    jumpPassphrase = jumpPassphraseText,
                )
            } else {
                openVerifiedPersistentSession(
                    route = route,
                    username = route.target.username,
                    password = passwordText,
                    privateKey = privateKeyText,
                    passphrase = passphraseText,
                )
            }
            this@Libssh2SshSession.route = route
            persistentSessionOpen = true
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
            diagnostics.record(
                activeTraceId.orEmpty(),
                DiagnosticEventStage.LIFECYCLE,
                "ssh.connected",
                "SSH 连接已就绪",
                hop = if (route.jump == null) DiagnosticHop.DIRECT else DiagnosticHop.TARGET,
            )
            startKeepalive()
            if (openShell) openTerminal(TerminalTarget.PlainShell)
        } catch (error: HostKeyBlockedException) {
            nativeRuntime.close()
            persistentSessionOpen = false
            this@Libssh2SshSession.route = null
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
            finishActiveTrace(DiagnosticTraceStatus.FAILED, "主机密钥未确认")
        } catch (error: Throwable) {
            nativeRuntime.close()
            persistentSessionOpen = false
            this@Libssh2SshSession.route = null
            val message = if (error is NativeSshException) {
                error.toUserMessage()
            } else {
                sanitizeNativeSshMessage(error.message ?: "SSH 连接失败")
            }
            mutableStage.value = ConnectionStage.READY
            mutableState.value = ConnectionState.Error(message)
            finishActiveTrace(DiagnosticTraceStatus.FAILED, message)
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
            val raw = if (persistentSessionOpen) {
                nativeRuntime.runPersistentExec(
                    command = command,
                    maxOutputBytes = maxOutputBytes,
                    timeoutMillis = timeoutMillis,
                )
            } else if (expectedFingerprint != null) {
                runDirectExec(
                    route = activeRoute,
                    username = activeRoute.target.username,
                    password = activePassword,
                    privateKey = privateKey,
                    passphrase = passphrase,
                    command = command,
                    expectedFingerprint = expectedFingerprint,
                    execTimeoutMillis = timeoutMillis,
                    maxOutputBytes = maxOutputBytes,
                )
            } else {
                verifiedExec(
                    route = activeRoute,
                    username = activeRoute.target.username,
                    password = activePassword,
                    privateKey = privateKey,
                    passphrase = passphrase,
                    command = command,
                    execTimeoutMillis = timeoutMillis,
                    maxOutputBytes = maxOutputBytes,
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
            val message = if (error is NativeSshException) {
                error.toUserMessage()
            } else {
                sanitizeNativeSshMessage(error.message ?: "native exec failed")
            }
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

    private fun parseExecPayload(raw: String): RemoteCommandResult =
        parseRuntimeExecPayload(raw)

    private suspend fun verifiedExec(
        route: SshRoute,
        username: String,
        password: String?,
        privateKey: ByteArray?,
        passphrase: String?,
        command: String,
        execTimeoutMillis: Long = 10_000L,
        maxOutputBytes: Int = 1024 * 1024,
    ): String {
        val hostname = route.target.hostname
        val port = route.target.port
        val info = probeRuntimeHostKey(route, hostname, port)
        val request = hostKeyRequest(hostname, port, info)
        if (request != null) {
            if (request.issue == HostKeyIssue.CHANGED || !allowHostKeyPrompt) {
                mutableHostKeyRequest.value = request
                throw HostKeyBlockedException(request)
            }
            mutableHostKeyRequest.value = request
            val accepted = awaitHostKeyDecision()
            mutableHostKeyRequest.value = null
            if (!accepted) {
                throw HostKeyBlockedException(request)
            }
            saveKnownHost(hostname, port, info)
        }
        mutableStage.value = ConnectionStage.TARGET_AUTH
        return runDirectExec(
            route = route,
            username = username,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            command = command,
            expectedFingerprint = info.fingerprint,
            execTimeoutMillis = execTimeoutMillis,
            maxOutputBytes = maxOutputBytes,
        )
    }

    private suspend fun openVerifiedPersistentSession(
        route: SshRoute,
        username: String,
        password: String?,
        privateKey: ByteArray?,
        passphrase: String?,
    ): String {
        val hostname = route.target.hostname
        val port = route.target.port
        val info = probeAndHoldRuntimeHostKey(route, hostname, port)
        val request = hostKeyRequest(hostname, port, info)
        if (request != null) {
            if (request.issue == HostKeyIssue.CHANGED || !allowHostKeyPrompt) {
                abortPendingSession()
                mutableHostKeyRequest.value = request
                throw HostKeyBlockedException(request)
            }
            mutableHostKeyRequest.value = request
            val accepted = awaitHostKeyDecision()
            mutableHostKeyRequest.value = null
            if (!accepted) {
                abortPendingSession()
                throw HostKeyBlockedException(request)
            }
            saveKnownHost(hostname, port, info)
        }
        mutableStage.value = ConnectionStage.TARGET_AUTH
        return continuePendingSession(
            username = username,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            storeAsJump = false,
        )
    }

    private suspend fun openVerifiedJumpPersistentSession(
        route: SshRoute,
        username: String,
        password: String?,
        privateKey: ByteArray?,
        passphrase: String?,
        jumpPassword: String?,
        jumpPrivateKey: ByteArray?,
        jumpPassphrase: String?,
    ): String {
        openVerifiedJumpSession(
            route = route,
            jumpPassword = jumpPassword,
            jumpPrivateKey = jumpPrivateKey,
            jumpPassphrase = jumpPassphrase,
        )

        val hostname = route.target.hostname
        val port = route.target.port
        mutableStage.value = ConnectionStage.TARGET_HOST_KEY
        val info = probeAndHoldJumpTargetHostKey(hostname, port)
        val request = hostKeyRequest(hostname, port, info, HostKeySubject.TARGET)
        if (request != null) {
            if (request.issue == HostKeyIssue.CHANGED || !allowHostKeyPrompt) {
                abortPendingSession()
                mutableHostKeyRequest.value = request
                throw HostKeyBlockedException(request)
            }
            mutableHostKeyRequest.value = request
            val accepted = awaitHostKeyDecision()
            mutableHostKeyRequest.value = null
            if (!accepted) {
                abortPendingSession()
                throw HostKeyBlockedException(request)
            }
            saveKnownHost(hostname, port, info)
        }

        mutableStage.value = ConnectionStage.TARGET_AUTH
        return continuePendingSession(
            username = username,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            storeAsJump = false,
        )
    }

    private suspend fun openVerifiedJumpSession(
        route: SshRoute,
        jumpPassword: String?,
        jumpPrivateKey: ByteArray?,
        jumpPassphrase: String?,
    ): String {
        val jump = route.jump ?: error("缺少跳板机")
        mutableStage.value = ConnectionStage.JUMP_HOST_KEY
        val info = probeAndHoldJumpRuntimeHostKey(route, jump)
        val request = hostKeyRequest(jump.hostname, jump.port, info, HostKeySubject.JUMP)
        if (request != null) {
            if (request.issue == HostKeyIssue.CHANGED || !allowHostKeyPrompt) {
                abortPendingSession()
                mutableHostKeyRequest.value = request
                throw HostKeyBlockedException(request)
            }
            mutableHostKeyRequest.value = request
            val accepted = awaitHostKeyDecision()
            mutableHostKeyRequest.value = null
            if (!accepted) {
                abortPendingSession()
                throw HostKeyBlockedException(request)
            }
            saveKnownHost(jump.hostname, jump.port, info)
        }

        mutableStage.value = ConnectionStage.JUMP_AUTH
        return continuePendingSession(
            username = jump.username,
            password = jumpPassword,
            privateKey = jumpPrivateKey,
            passphrase = jumpPassphrase,
            storeAsJump = true,
        )
    }

    private suspend fun probeJumpRuntimeHostKey(
        route: SshRoute,
        jump: com.yang136.sshhelper.data.HostProfile,
    ): TcpHandshakeInfo {
        mutableStage.value = ConnectionStage.JUMP_HOST_KEY
        val proxy = jumpProxy(route)
        val raw = if (proxy != null) {
            connectProxy(proxy, jump.hostname, jump.port)
            nativeRuntime.runPendingTcpHandshake(SSH_CONNECT_TIMEOUT_MS.toLong())
        } else {
            nativeRuntime.runTcpHandshake(
                jump.hostname,
                jump.port,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        }
        return parseRuntimeTcpHandshakePayload(raw)
    }

    private suspend fun openJumpSession(
        route: SshRoute,
        jumpPassword: String?,
        jumpPrivateKey: ByteArray?,
        jumpPassphrase: String?,
        expectedFingerprint: String,
    ): String {
        val jump = route.jump ?: error("缺少跳板机")
        val proxy = jumpProxy(route)
        if (proxy != null) {
            connectProxy(proxy, jump.hostname, jump.port)
        } else {
            nativeRuntime.runTcpConnect(
                jump.hostname,
                jump.port,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        }
        return if (jumpPassword != null) {
            nativeRuntime.runOpenJumpSession(
                jump.username,
                jumpPassword,
                expectedFingerprint,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        } else {
            val key = jumpPrivateKey ?: error("跳板机私钥不可用")
            nativeRuntime.runOpenJumpSessionWithPrivateKey(
                jump.username,
                key,
                jumpPassphrase,
                expectedFingerprint,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        }
    }

    private suspend fun probeJumpTargetHostKey(
        route: SshRoute,
        hostname: String,
        port: Int,
    ): TcpHandshakeInfo {
        mutableStage.value = ConnectionStage.TARGET_HOST_KEY
        val raw = nativeRuntime.runOpenJumpTargetHandshake(
            hostname,
            port,
            SSH_CONNECT_TIMEOUT_MS.toLong(),
        )
        return parseRuntimeTcpHandshakePayload(raw)
    }

    private suspend fun openJumpTargetPersistentSession(
        route: SshRoute,
        username: String,
        password: String?,
        privateKey: ByteArray?,
        passphrase: String?,
        expectedFingerprint: String,
    ): String {
        val target = route.target
        return nativeRuntime.runOpenJumpTargetSession(
            target.hostname,
            target.port,
            username,
            password.orEmpty(),
            privateKey,
            passphrase,
            expectedFingerprint,
            SSH_CONNECT_TIMEOUT_MS.toLong(),
        )
    }

    private suspend fun openPersistentSession(
        route: SshRoute,
        username: String,
        password: String?,
        privateKey: ByteArray?,
        passphrase: String?,
        expectedFingerprint: String,
    ): String {
        val hostname = route.target.hostname
        val port = route.target.port
        val proxy = targetProxy(route)
        if (proxy != null) {
            connectProxy(proxy, hostname, port)
        } else {
            nativeRuntime.runTcpConnect(
                hostname,
                port,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        }
        return if (password != null) {
            nativeRuntime.runOpenSession(
                username,
                password,
                expectedFingerprint,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        } else {
            val key = privateKey ?: error("SSH private key unavailable")
            nativeRuntime.runOpenSessionWithPrivateKey(
                username,
                key,
                passphrase,
                expectedFingerprint,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        }
    }

    private suspend fun runDirectExec(
        route: SshRoute,
        username: String,
        password: String?,
        privateKey: ByteArray?,
        passphrase: String?,
        command: String,
        expectedFingerprint: String,
        execTimeoutMillis: Long,
        maxOutputBytes: Int,
    ): String {
        val hostname = route.target.hostname
        val port = route.target.port
        val proxy = targetProxy(route)
        if (proxy != null) {
            connectProxy(proxy, hostname, port)
        }
        return if (password != null) {
            if (proxy != null) {
                nativeRuntime.runPendingDirectPasswordExec(
                    hostname,
                    port,
                    username,
                    password,
                    command,
                    expectedFingerprint,
                    SSH_CONNECT_TIMEOUT_MS.toLong(),
                    execTimeoutMillis,
                    maxOutputBytes,
                )
            } else {
                nativeRuntime.runDirectPasswordExec(
                    hostname,
                    port,
                    username,
                    password,
                    command,
                    expectedFingerprint,
                    SSH_CONNECT_TIMEOUT_MS.toLong(),
                    execTimeoutMillis,
                    maxOutputBytes,
                )
            }
        } else {
            val key = privateKey ?: error("SSH private key unavailable")
            if (proxy != null) {
                nativeRuntime.runPendingDirectPrivateKeyExec(
                    hostname,
                    port,
                    username,
                    key,
                    passphrase,
                    command,
                    expectedFingerprint,
                    SSH_CONNECT_TIMEOUT_MS.toLong(),
                    execTimeoutMillis,
                    maxOutputBytes,
                )
            } else {
                nativeRuntime.runDirectPrivateKeyExec(
                    hostname,
                    port,
                    username,
                    key,
                    passphrase,
                    command,
                    expectedFingerprint,
                    SSH_CONNECT_TIMEOUT_MS.toLong(),
                    execTimeoutMillis,
                    maxOutputBytes,
                )
            }
        }
    }

    private suspend fun probeRuntimeHostKey(
        route: SshRoute,
        hostname: String,
        port: Int,
    ): TcpHandshakeInfo {
        mutableStage.value = ConnectionStage.TARGET_HOST_KEY
        val proxy = targetProxy(route)
        val raw = if (proxy != null) {
            connectProxy(proxy, hostname, port)
            nativeRuntime.runPendingTcpHandshake(SSH_CONNECT_TIMEOUT_MS.toLong())
        } else {
            nativeRuntime.runTcpHandshake(
                hostname,
                port,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        }
        return parseRuntimeTcpHandshakePayload(raw)
    }

    /** Probes and holds the unauthenticated connection for a direct target. */
    private suspend fun probeAndHoldRuntimeHostKey(
        route: SshRoute,
        hostname: String,
        port: Int,
    ): TcpHandshakeInfo {
        mutableStage.value = ConnectionStage.TARGET_HOST_KEY
        val proxy = targetProxy(route)
        val raw = if (proxy != null) {
            connectProxy(proxy, hostname, port)
            nativeRuntime.runPendingHostKeyProbe(SSH_CONNECT_TIMEOUT_MS.toLong())
        } else {
            nativeRuntime.runTcpHostKeyProbe(
                hostname,
                port,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        }
        return parseRuntimeTcpHandshakePayload(raw)
    }

    /** Probes and holds the unauthenticated connection for a jump host. */
    private suspend fun probeAndHoldJumpRuntimeHostKey(
        route: SshRoute,
        jump: com.yang136.sshhelper.data.HostProfile,
    ): TcpHandshakeInfo {
        mutableStage.value = ConnectionStage.JUMP_HOST_KEY
        val proxy = jumpProxy(route)
        val raw = if (proxy != null) {
            connectProxy(proxy, jump.hostname, jump.port)
            nativeRuntime.runPendingHostKeyProbe(SSH_CONNECT_TIMEOUT_MS.toLong())
        } else {
            nativeRuntime.runTcpHostKeyProbe(
                jump.hostname,
                jump.port,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        }
        return parseRuntimeTcpHandshakePayload(raw)
    }

    /** Probes and holds the target connection tunneled through an open jump host. */
    private suspend fun probeAndHoldJumpTargetHostKey(
        hostname: String,
        port: Int,
    ): TcpHandshakeInfo {
        mutableStage.value = ConnectionStage.TARGET_HOST_KEY
        val raw = nativeRuntime.runOpenJumpTargetHostKeyProbe(
            hostname,
            port,
            SSH_CONNECT_TIMEOUT_MS.toLong(),
        )
        return parseRuntimeTcpHandshakePayload(raw)
    }

    /** Authenticates the held unauthenticated session and stores it. */
    private fun continuePendingSession(
        username: String,
        password: String?,
        privateKey: ByteArray?,
        passphrase: String?,
        storeAsJump: Boolean,
    ): String {
        val raw = nativeRuntime.runContinuePendingSession(
            username = username,
            password = password.orEmpty(),
            privateKey = privateKey,
            passphrase = passphrase,
            storeAsJump = storeAsJump,
            timeoutMillis = SSH_CONNECT_TIMEOUT_MS.toLong(),
        )
        check(raw == "session=ok") { "native continue pending session failed: $raw" }
        return raw
    }

    private fun abortPendingSession() {
        runCatching { nativeRuntime.runAbortPendingSession() }
    }

    private fun targetProxy(route: SshRoute): TargetProxy? {
        // The target's device-side proxy applies only to a direct route. When a
        // jump host is used, the jump tunnel already replaces the device-side
        // transport and the target profile's own proxy is not applied.
        if (route.jump != null) return null
        return route.target.toProxy(targetProxyPassword)
    }

    private fun jumpProxy(route: SshRoute): TargetProxy? {
        val jump = route.jump ?: return null
        return jump.toProxy(jumpProxyPassword)
    }

    private fun com.yang136.sshhelper.data.HostProfile.toProxy(proxyPassword: String?): TargetProxy? {
        val type = proxyType ?: return null
        val host = proxyHost ?: return null
        val port = proxyPort ?: return null
        return TargetProxy(
            type = type,
            host = host,
            port = port,
            username = proxyUsername,
            password = proxyPassword.orEmpty(),
        )
    }

    private fun connectProxy(proxy: TargetProxy, targetHost: String, targetPort: Int) {
        val result = when (proxy.type) {
            ProxyType.HTTP -> nativeRuntime.runHttpProxyConnect(
                proxy.host,
                proxy.port,
                targetHost,
                targetPort,
                proxy.username.orEmpty(),
                proxy.password,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
            ProxyType.SOCKS5 -> nativeRuntime.runSocks5ProxyConnect(
                proxy.host,
                proxy.port,
                targetHost,
                targetPort,
                proxy.username.orEmpty(),
                proxy.password,
                SSH_CONNECT_TIMEOUT_MS.toLong(),
            )
        }
        check(result == "connected") { "proxy connect failed" }
    }

    private suspend fun hostKeyRequest(
        hostname: String,
        port: Int,
        info: TcpHandshakeInfo,
        subject: HostKeySubject = HostKeySubject.TARGET,
    ): HostKeyRequest? {
        val expected = findKnownHost(hostname, port)
        return when {
            expected == null -> HostKeyRequest(
                hostname = hostname,
                port = port,
                keyType = info.keyType,
                fingerprint = info.fingerprint,
                issue = HostKeyIssue.UNKNOWN,
                subject = subject,
            )
            sameKey(expected.keyBase64, info.keyBase64) -> null
            else -> HostKeyRequest(
                hostname = hostname,
                port = port,
                keyType = info.keyType,
                fingerprint = info.fingerprint,
                previousFingerprint = expected.fingerprintSha256,
                issue = HostKeyIssue.CHANGED,
                subject = subject,
            )
        }
    }

    private suspend fun findKnownHost(hostname: String, port: Int): KnownHostEntity? {
        knownHostDao?.let { return it.find(hostname, port) }
        synchronized(knownHostCache) {
            return knownHostCache["${hostname.lowercase()}:$port"]
        }
    }

    private suspend fun saveKnownHost(hostname: String, port: Int, info: TcpHandshakeInfo) {
        val entity = KnownHostEntity(
            id = "${hostname.lowercase()}:$port",
            hostname = hostname,
            port = port,
            keyType = info.keyType,
            keyBase64 = info.keyBase64,
            fingerprintSha256 = info.fingerprint,
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

    private fun startKeepalive() {
        stopKeepalive()
        if (!persistentSessionOpen || closed) return
        keepaliveJob = terminalScope.launch {
            var consecutiveTimeouts = 0
            while (persistentSessionOpen && !closed && currentCoroutineContext().isActive) {
                try {
                    nativeRuntime.runKeepalive(SSH_KEEPALIVE_INTERVAL_MS * 2L)
                    consecutiveTimeouts = 0
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    // A single request can expire while Android suspends the app.
                    // Retry it on the intact transport; hard I/O errors still fail immediately.
                    if (error is NativeSshException && error.domain == "timeout" &&
                        error.code == "keepalive_timeout" &&
                        ++consecutiveTimeouts < SSH_KEEPALIVE_MAX_MISSES
                    ) {
                        delay(SSH_KEEPALIVE_INTERVAL_MS.toLong())
                        continue
                    }
                    if (persistentSessionOpen && mutableState.value is ConnectionState.Connected) {
                        val cause = if (error is NativeSshException) {
                            error.toDisconnectCause()
                        } else {
                            DisconnectCause.UNKNOWN
                        }
                        val message = if (error is NativeSshException) {
                            error.toUserMessage("SSH keepalive 失败")
                        } else {
                            sanitizeNativeSshMessage(error.message ?: "SSH keepalive 失败")
                        }
                        failTransport(cause, message)
                    }
                    break
                }
                delay(SSH_KEEPALIVE_INTERVAL_MS.toLong())
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private suspend fun failTransport(cause: DisconnectCause, message: String) {
        if (mutableState.value !is ConnectionState.Connected) return
        terminalReaderJob?.cancel()
        terminalReaderJob = null
        terminalChannelOpen = false
        cancelPendingHostKey()
        sftpClients.toList().forEach(NativeSftpClient::close)
        sftpClients.clear()
        nativeRuntime.close()
        persistentSessionOpen = false
        this@Libssh2SshSession.route = null
        password = null
        privateKey?.fill(0)
        privateKey = null
        passphrase = null
        targetProxyPassword = null
        jumpPassword = null
        jumpPrivateKey?.fill(0)
        jumpPrivateKey = null
        jumpPassphrase = null
        jumpProxyPassword = null
        mutableTerminalState.value = TerminalChannelState.Closed
        mutableState.value = ConnectionState.Disconnected(message, cause)
        diagnostics.record(
            activeTraceId.orEmpty(),
            DiagnosticEventStage.DISCONNECT,
            "ssh.unexpected_disconnect",
            message,
            level = DiagnosticEventLevel.ERROR,
            details = mapOf("cause" to cause.name),
        )
        finishActiveTrace(DiagnosticTraceStatus.FAILED, message)
    }

    private suspend fun finishActiveTrace(status: DiagnosticTraceStatus, summary: String?) {
        val traceId = activeTraceId
        activeTraceId = null
        if (traceId != null && traceId != "noop") {
            diagnostics.finishTrace(traceId, status, summary)
        }
    }

    private fun finishActiveTraceAsync(status: DiagnosticTraceStatus, summary: String?) {
        val traceId = activeTraceId
        activeTraceId = null
        if (traceId != null && traceId != "noop") {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                diagnostics.finishTrace(traceId, status, summary)
            }
        }
    }

    private suspend fun readShellLoop() {
        try {
            while (terminalChannelOpen && currentCoroutineContext().isActive) {
                val data = nativeRuntime.runShellRead(8192, 250L) ?: continue
                if (data.isEmpty()) {
                    terminalChannelOpen = false
                    runCatching { nativeRuntime.runCloseShell() }
                    mutableTerminalState.value = TerminalChannelState.Ended("远端 Shell 已退出")
                    return
                }
                mutableOutput.emit(data)
            }
        } catch (error: Throwable) {
            if (error !is kotlinx.coroutines.CancellationException) {
                terminalChannelOpen = false
                runCatching { nativeRuntime.runCloseShell() }
                mutableTerminalState.value = TerminalChannelState.Error(
                    error.message ?: "Shell 读取失败",
                )
            }
        }
    }

    private fun cancelPendingHostKey() {
        hostKeyDecision.getAndSet(null)?.complete(false)
        mutableHostKeyRequest.value = null
    }

    override suspend fun openTerminal(target: TerminalTarget) = withContext(Dispatchers.IO) {
        if (!persistentSessionOpen) {
            mutableTerminalState.value = TerminalChannelState.Error("SSH 连接不可用")
            return@withContext
        }
        closeTerminal()
        mutableTerminalState.value = TerminalChannelState.Opening
        try {
            when (target) {
                TerminalTarget.PlainShell -> nativeRuntime.runOpenShell(ptyColumns, ptyRows)
                is TerminalTarget.Persistent -> {
                    val multiplexer = MultiplexerRegistry.forType(target.type)
                        ?: error("未配置远端会话管理器")
                    val command = if (target.create) {
                        multiplexer.createCommand(target.name)
                    } else {
                        multiplexer.attachCommand(target.name)
                    }
                    nativeRuntime.runOpenPtyExec(command, ptyColumns, ptyRows)
                }
            }
            terminalChannelOpen = true
            mutableTerminalState.value = TerminalChannelState.Active(target)
            terminalReaderJob = terminalScope.launch { readShellLoop() }
        } catch (error: Throwable) {
            terminalChannelOpen = false
            mutableTerminalState.value = TerminalChannelState.Error(error.message ?: "打开终端失败")
        }
    }

    override suspend fun closeTerminal() = withContext(Dispatchers.IO) {
        // Wait for the bounded native read to finish before replacing its channel.
        terminalReaderJob?.cancelAndJoin()
        terminalReaderJob = null
        if (terminalChannelOpen) {
            terminalChannelOpen = false
            runCatching { nativeRuntime.runCloseShell() }
        }
        mutableTerminalState.value = TerminalChannelState.Closed
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        if (!terminalChannelOpen || !persistentSessionOpen) return@withContext
        nativeRuntime.runShellWrite(data)
    }

    override suspend fun resize(columns: Int, rows: Int) = withContext(Dispatchers.IO) {
        if (!terminalChannelOpen || !persistentSessionOpen) return@withContext
        ptyColumns = columns
        ptyRows = rows
        nativeRuntime.runShellResize(columns, rows)
    }

    override suspend fun disconnect() {
        stopKeepalive()
        terminalReaderJob?.cancel()
        terminalReaderJob = null
        terminalChannelOpen = false
        cancelPendingHostKey()
        sftpClients.toList().forEach(NativeSftpClient::close)
        sftpClients.clear()
        forwardHandles.toList().forEach { it.close() }
        forwardHandles.clear()
        nativeRuntime.close()
        persistentSessionOpen = false
        route = null
        password = null
        privateKey?.fill(0)
        privateKey = null
        passphrase = null
        targetProxyPassword = null
        jumpPassword = null
        jumpPrivateKey?.fill(0)
        jumpPrivateKey = null
        jumpPassphrase = null
        jumpProxyPassword = null
        mutableState.value = ConnectionState.Disconnected("已断开", DisconnectCause.USER)
        mutableTerminalState.value = TerminalChannelState.Closed
        diagnostics.record(
            activeTraceId.orEmpty(),
            DiagnosticEventStage.DISCONNECT,
            "ssh.user_disconnect",
            "用户主动断开 SSH 连接",
        )
        finishActiveTrace(DiagnosticTraceStatus.SUCCEEDED, "用户主动断开连接")
    }

    override fun respondToHostKey(accept: Boolean) {
        hostKeyDecision.getAndSet(null)?.complete(accept)
        if (mutableHostKeyRequest.value?.issue == HostKeyIssue.CHANGED) {
            mutableHostKeyRequest.value = null
        }
    }

    override suspend fun openSftpClient(): com.yang136.sshhelper.sftp.SftpClient {
        check(persistentSessionOpen && !closed) { "SSH 连接不可用" }
        val clientHandle = withContext(Dispatchers.IO) { nativeRuntime.createSftpClient() }
        return NativeSftpClient(nativeRuntime, clientHandle) { client -> sftpClients.remove(client) }
            .also(sftpClients::add)
    }

    override suspend fun registerForward(request: ForwardRequest): ForwardHandle =
        withContext(Dispatchers.IO) {
            check(persistentSessionOpen && !closed) { "SSH 连接不可用" }
            when (request.type) {
                ForwardType.LOCAL -> {
                    val targetHost = request.targetHost ?: error("缺少目标主机")
                    val targetPort = request.targetPort ?: error("缺少目标端口")
                    val result = nativeRuntime.startLocalForward(
                        bindAddress = request.bindAddress,
                        listenPort = request.listenPort,
                        targetHost = targetHost,
                        targetPort = targetPort,
                    )
                    NativeForwardHandle(result.id, result.actualPort)
                        .also(forwardHandles::add)
                }
                ForwardType.REMOTE -> {
                    val targetHost = request.targetHost ?: error("缺少目标主机")
                    val targetPort = request.targetPort ?: error("缺少目标端口")
                    val result = nativeRuntime.startRemoteForward(
                        bindAddress = request.bindAddress,
                        listenPort = request.listenPort,
                        targetHost = targetHost,
                        targetPort = targetPort,
                    )
                    NativeForwardHandle(result.id, result.actualPort)
                        .also(forwardHandles::add)
                }
                ForwardType.DYNAMIC -> {
                    throw UnsupportedOperationException("动态转发暂未在 native 后端实现，已标记为延后")
                }
            }
        }

    override fun close() {
        closed = true
        stopKeepalive()
        terminalReaderJob?.cancel()
        terminalReaderJob = null
        terminalChannelOpen = false
        terminalScope.cancel()
        cancelPendingHostKey()
        sftpClients.toList().forEach(NativeSftpClient::close)
        sftpClients.clear()
        forwardHandles.toList().forEach { it.close() }
        forwardHandles.clear()
        nativeRuntime.close()
        persistentSessionOpen = false
        route = null
        password = null
        privateKey?.fill(0)
        privateKey = null
        passphrase = null
        targetProxyPassword = null
        jumpPassword = null
        jumpPrivateKey?.fill(0)
        jumpPrivateKey = null
        jumpPassphrase = null
        jumpProxyPassword = null
        mutableState.value = ConnectionState.Disconnected("应用已关闭", DisconnectCause.APP_CLOSED)
        mutableTerminalState.value = TerminalChannelState.Closed
        diagnostics.record(
            activeTraceId.orEmpty(),
            DiagnosticEventStage.DISCONNECT,
            "ssh.app_closed",
            "应用已关闭连接",
        )
        finishActiveTraceAsync(DiagnosticTraceStatus.SUCCEEDED, "应用已关闭连接")
    }
}

internal data class TcpHandshakeInfo(
    val fingerprint: String,
    val keyType: String,
    val keyBase64: String,
)

internal fun parseRuntimeTcpHandshakePayload(raw: String): TcpHandshakeInfo {
    val fields = mutableMapOf<String, String>()
    for (line in raw.lineSequence()) {
        if (line.isBlank()) continue
        val separator = line.indexOf('=')
        if (separator > 0) {
            fields[line.substring(0, separator)] = line.substring(separator + 1)
        }
    }
    return TcpHandshakeInfo(
        fingerprint = fields["fingerprint"]
            ?: error("runtime handshake payload missing fingerprint"),
        keyType = fields["keyType"]
            ?: error("runtime handshake payload missing keyType"),
        keyBase64 = fields["keyBase64"]
            ?: error("runtime handshake payload missing keyBase64"),
    )
}

private class HostKeyBlockedException(
    val request: HostKeyRequest,
) : IllegalStateException("主机密钥验证失败")

internal fun parseRuntimeExecPayload(raw: String): RemoteCommandResult {
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
