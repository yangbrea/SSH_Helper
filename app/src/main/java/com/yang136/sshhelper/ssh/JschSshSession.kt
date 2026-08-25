package com.yang136.sshhelper.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.ForwardType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.data.KnownHostEntity
import com.yang136.sshhelper.sftp.JschSftpClient
import com.yang136.sshhelper.sftp.SftpClient
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JschSshSession(private val knownHostDao: KnownHostDao) : SshSession, SftpCapableSession, PortForwardCapableSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val mutableOutput = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    private val mutableHostKeyRequest = MutableStateFlow<HostKeyRequest?>(null)
    private val mutableStage = MutableStateFlow(ConnectionStage.READY)
    private val hostKeyDecision = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val writeMutex = Mutex()

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    override val output: Flow<ByteArray> = mutableOutput.asSharedFlow()
    override val hostKeyRequest: StateFlow<HostKeyRequest?> = mutableHostKeyRequest.asStateFlow()
    override val stage: StateFlow<ConnectionStage> = mutableStage.asStateFlow()

    @Volatile private var session: Session? = null
    @Volatile private var jumpSession: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var writer: OutputStream? = null
    private var readerJob: Job? = null

    /** 串行化 connect/disconnect/close，杜绝并发连接交错导致 JSch 会话/线程泄漏。 */
    private val lifecycleMutex = Mutex()
    @Volatile private var closed = false
    /** 当前连接是否带有 shell 通道（转发专用会话不创建 shell/PTY）。 */
    @Volatile private var shellEnabled = false

    override suspend fun openSftpClient(): SftpClient = withContext(Dispatchers.IO) {
        val activeSession = session?.takeIf(Session::isConnected) ?: error("SSH 连接不可用")
        val sftp = activeSession.openChannel("sftp") as ChannelSftp
        sftp.connect(15_000)
        JschSftpClient(sftp)
    }

    override suspend fun registerForward(request: ForwardRequest): ForwardHandle = withContext(Dispatchers.IO) {
        val active = session?.takeIf(Session::isConnected) ?: error("SSH 连接不可用")
        when (request.type) {
            ForwardType.LOCAL -> {
                val targetHost = request.targetHost ?: error("缺少目标主机")
                val targetPort = request.targetPort ?: error("缺少目标端口")
                val actual = active.setPortForwardingL(request.bindAddress, request.listenPort, targetHost, targetPort)
                // 注销必须按注册时的 bind 地址进行：delPortForwardingL(int) 固定按
                // "127.0.0.1" 查找（JSch 字节码确认），0.0.0.0 等绑定会抛
                // JSchException("not registered")。会话断开时 JSch 已自行卸载转发
                // （Session.disconnect → PortWatcher.delPort），因此整体静默忽略。
                SimpleForwardHandle(actual) {
                    runCatching { active.delPortForwardingL(request.bindAddress, actual) }
                }
            }
            ForwardType.REMOTE -> {
                val targetHost = request.targetHost ?: error("缺少目标主机")
                val targetPort = request.targetPort ?: error("缺少目标端口")
                active.setPortForwardingR(request.bindAddress, request.listenPort, targetHost, targetPort)
                SimpleForwardHandle(request.listenPort) { runCatching { active.delPortForwardingR(request.listenPort) } }
            }
            ForwardType.DYNAMIC -> {
                val server = Socks5Server(active, request.bindAddress, request.listenPort)
                server.start()
                SimpleForwardHandle(server.actualPort) { server.close() }
            }
        }
    }

    private class SimpleForwardHandle(
        override val actualListenPort: Int,
        private val onClose: () -> Unit,
    ) : ForwardHandle {
        private val closed = AtomicBoolean(false)
        override fun close() {
            // 幂等 + 异常兜底：JSch 会话被自行清理后注销会抛 JSchException，
            // 直接忽略，避免未捕获异常经协程传播导致进程崩溃。
            if (closed.compareAndSet(false, true)) {
                runCatching { onClose() }
            }
        }
    }

    override suspend fun connect(route: SshRoute, credentials: RouteCredentials, openShell: Boolean) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (closed) return@withLock
            connectLocked(route, credentials, openShell)
        }
    }

    private suspend fun connectLocked(route: SshRoute, credentials: RouteCredentials, openShell: Boolean) {
        disconnectInternal("正在重新连接", DisconnectCause.UNKNOWN, publishState = false)
        mutableStage.value = if (route.jump != null) ConnectionStage.JUMP_AUTH else ConnectionStage.TARGET_AUTH
        mutableState.value = ConnectionState.Connecting
        try {
            val jump = if (route.jump != null) {
                val jumpCredential = credentials.jump ?: error("缺少跳板机凭据")
                connectJschSession(
                    route.jump,
                    jumpCredential,
                    HostKeySubject.JUMP,
                    proxyPassword = credentials.jumpProxyPassword,
                ).also { jumpSession = it }
            } else null
            mutableStage.value = ConnectionStage.TARGET_AUTH
            val target = connectJschSession(
                route.target,
                credentials.target,
                HostKeySubject.TARGET,
                proxyVia = jump,
                proxyPassword = credentials.targetProxyPassword,
            )
            session = target
            // 转发专用会话（PORT_FORWARD）不创建 shell/PTY：只允许 TCP forwarding 的
            // SSH 账号可用，且服务端关闭空闲 shell 不会误判为传输断开。
            val input = if (openShell) {
                val newChannel = (target.openChannel("shell") as ChannelShell).apply {
                    setPtyType("xterm-256color")
                    setPtySize(80, 24, 0, 0)
                }
                writer = newChannel.outputStream
                channel = newChannel
                newChannel.connect(SSH_CONNECT_TIMEOUT_MS)
                shellEnabled = true
                newChannel.inputStream
            } else {
                writer = null
                channel = null
                shellEnabled = false
                null
            }
            // 连接建立期间会话被 close()：立即拆除刚建立的会话，避免 JSch 会话泄漏。
            if (closed) {
                disconnectInternal("应用已关闭连接", DisconnectCause.APP_CLOSED, publishState = false)
                return
            }
            mutableStage.value = ConnectionStage.READY
            mutableState.value = ConnectionState.Connected(connectedLabel(route))
            readerJob = scope.launch {
                val stream = input
                if (stream != null) readOutput(stream) else watchTransport()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val pendingHostKey = mutableHostKeyRequest.value
            disconnectInternal("连接失败", DisconnectCause.UNKNOWN, publishState = false)
            if (pendingHostKey?.issue == HostKeyIssue.CHANGED) {
                mutableHostKeyRequest.value = pendingHostKey
            }
            mutableStage.value = ConnectionStage.READY
            mutableState.value = ConnectionState.Error(error.toChineseMessage())
        }
    }

    /**
     * 无 shell 会话的传输监视：JSch 传输失败时 Session.run() 内部会调用 disconnect()
     * （isConnected 翻转为 false），这里轮询兜底检测并发布断连事件，替代 shell 读取。
     */
    private suspend fun watchTransport() {
        while (true) {
            delay(TRANSPORT_WATCH_INTERVAL_MS)
            val active = session
            if (active == null) {
                if (mutableState.value is ConnectionState.Connected) return
                continue
            }
            if (!active.isConnected && mutableState.value is ConnectionState.Connected) {
                publishUnexpectedDisconnect(
                    ConnectionState.Disconnected("SSH 传输连接已中断", DisconnectCause.TRANSPORT_CLOSED),
                )
                return
            }
        }
    }

    private fun connectedLabel(route: SshRoute): String {
        val base = "${route.target.username}@${route.target.hostname}"
        return route.jump?.let { "$base 经 ${it.name}" } ?: base
    }

    /**
     * Opens one hop of the route. When [proxyVia] is a connected jump session, the new session's
     * transport is a `direct-tcpip` channel opened through the jump host, so the target SSH
     * handshake runs over the encrypted jump tunnel. Host keys of both hops are verified
     * independently against the shared known_hosts store. Each hop's own proxy configuration is
     * applied to the direct connection from this device (the tunnel interior needs no proxy).
     */
    private fun connectJschSession(
        profile: HostProfile,
        credential: Credential,
        subject: HostKeySubject,
        proxyVia: Session? = null,
        proxyPassword: String? = null,
    ): Session {
        val expected = runBlocking { knownHostDao.find(profile.hostname, profile.port) }
        val verifier = SessionHostKeyRepository(profile, expected, subject)
        val jsch = JSch().apply { hostKeyRepository = verifier }
        when (credential) {
            is Credential.PrivateKey -> {
                val passphrase = credential.passphrase?.concatToString()?.encodeToByteArray()
                jsch.addIdentity(
                    credential.fileName ?: "SSH Helper private key",
                    credential.bytes,
                    null,
                    passphrase,
                )
                passphrase?.fill(0)
            }
            is Credential.Password -> Unit
        }
        val newSession = jsch.getSession(profile.username, profile.hostname, profile.port).apply {
            serverAliveInterval = SSH_KEEPALIVE_INTERVAL_MS
            serverAliveCountMax = SSH_KEEPALIVE_MAX_MISSES
            setConfig(Properties().apply {
                put("StrictHostKeyChecking", "yes")
                put(
                    "PreferredAuthentications",
                    when (credential) {
                        is Credential.Password -> "password,keyboard-interactive"
                        is Credential.PrivateKey -> "publickey"
                    },
                )
                // JSch defaults to three password prompts. One bad password could
                // otherwise rapidly trigger sshd/PAM account lockout.
                put("NumberOfPasswordPrompts", "1")
            })
            when (credential) {
                is Credential.Password -> {
                    val password = credential.value.concatToString()
                    setPassword(password)
                    userInfo = PasswordUserInfo(password)
                }
                is Credential.PrivateKey -> userInfo = NonInteractiveUserInfo
            }
            if (proxyVia != null) {
                val tunnel = proxyVia.openChannel("direct-tcpip") as ChannelDirectTCPIP
                tunnel.setHost(profile.hostname)
                tunnel.setPort(profile.port)
                setProxy(JumpHostProxy(tunnel))
            } else {
                profile.connectionProxy(proxyPassword)?.let { setProxy(it) }
            }
        }
        newSession.connect(SSH_CONNECT_TIMEOUT_MS)
        return newSession
    }

    private fun HostProfile.connectionProxy(password: String?): Proxy? {
        val type = proxyType ?: return null
        val host = proxyHost ?: return null
        val port = proxyPort ?: return null
        return when (type) {
            com.yang136.sshhelper.data.ProxyType.HTTP -> ProxyHTTP(host, port).apply {
                proxyUsername?.let { setUserPasswd(it, password.orEmpty()) }
            }
            com.yang136.sshhelper.data.ProxyType.SOCKS5 -> ProxySOCKS5(host, port).apply {
                proxyUsername?.let { setUserPasswd(it, password.orEmpty()) }
            }
        }
    }

    /** Streams the target SSH handshake through a direct-tcpip channel on the jump session. */
    private class JumpHostProxy(private val tunnel: ChannelDirectTCPIP) : Proxy {
        @Volatile private var input: InputStream? = null
        @Volatile private var output: OutputStream? = null

        override fun connect(socketFactory: SocketFactory?, host: String?, port: Int, timeout: Int) {
            tunnel.connect(timeout.coerceAtLeast(SSH_CONNECT_TIMEOUT_MS))
            input = tunnel.inputStream
            output = tunnel.outputStream
        }

        override fun getInputStream(): InputStream = input ?: error("jump tunnel not connected")
        override fun getOutputStream(): OutputStream = output ?: error("jump tunnel not connected")
        override fun getSocket(): java.net.Socket? = null
        override fun close() {
            runCatching { tunnel.disconnect() }
        }
    }

    private suspend fun readOutput(input: InputStream) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) mutableOutput.emit(buffer.copyOf(count))
            }
            publishUnexpectedDisconnect(classifyDisconnect(null))
        } catch (error: Throwable) {
            if (error !is CancellationException) publishUnexpectedDisconnect(classifyDisconnect(error))
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        // 无 shell 的转发会话没有终端输入通道，直接忽略。
        if (!shellEnabled) return@withContext
        var failure: Throwable? = null
        writeMutex.withLock {
            val output = writer
            val activeChannel = channel
            if (output == null || activeChannel?.isConnected != true) {
                failure = IOException("SSH shell channel is not connected")
            } else {
                runCatching {
                    output.write(data)
                    output.flush()
                }.onFailure { failure = it }
            }
        }
        failure?.let { error ->
            publishUnexpectedDisconnect(
                ConnectionState.Disconnected(
                    reason = "SSH 写入失败：${error.safeMessage()}",
                    cause = DisconnectCause.WRITE_ERROR,
                )
            )
            disconnectInternal("SSH 写入失败", DisconnectCause.WRITE_ERROR, publishState = false)
        }
        Unit
    }

    override suspend fun resize(columns: Int, rows: Int) = withContext(Dispatchers.IO) {
        if (!shellEnabled) return@withContext
        val safeColumns = columns.coerceIn(2, 500)
        val safeRows = rows.coerceIn(2, 300)
        runCatching { channel?.takeIf { it.isConnected }?.setPtySize(safeColumns, safeRows, 0, 0) }
        Unit
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            disconnectInternal("连接已关闭", DisconnectCause.USER, publishState = true)
        }
    }

    private suspend fun disconnectInternal(reason: String, cause: DisconnectCause, publishState: Boolean) {
        val job = readerJob
        readerJob = null
        val activeChannel = channel
        val activeSession = session
        val activeJump = jumpSession
        writer = null
        channel = null
        session = null
        jumpSession = null
        shellEnabled = false

        // Closing the channel must happen before waiting for the reader. A coroutine
        // cancellation alone cannot interrupt InputStream.read(), and closing the
        // channel's output stream first can itself block while a PTY is still alive.
        runCatching { activeChannel?.disconnect() }
        runCatching { activeSession?.disconnect() }
        // The jump transport owns the direct-tcpip tunnel used by the target handshake;
        // disconnecting it tears the tunnel down with the jump session.
        runCatching { activeJump?.disconnect() }
        if (job != null && job != kotlinx.coroutines.currentCoroutineContext()[Job]) {
            job.cancelAndJoin()
        }
        hostKeyDecision.getAndSet(null)?.complete(false)
        mutableHostKeyRequest.value = null
        mutableStage.value = ConnectionStage.READY
        if (publishState) mutableState.value = ConnectionState.Disconnected(reason, cause)
    }

    private fun publishUnexpectedDisconnect(disconnected: ConnectionState.Disconnected) {
        if (mutableState.value is ConnectionState.Connected) mutableState.value = disconnected
    }

    private fun classifyDisconnect(error: Throwable?): ConnectionState.Disconnected {
        val activeChannel = channel
        val activeSession = session
        val exitStatus = activeChannel?.exitStatus ?: -1
        return when {
            exitStatus >= 0 -> ConnectionState.Disconnected(
                "远端 Shell 已退出（代码 $exitStatus）",
                DisconnectCause.REMOTE_SHELL_EXIT,
            )
            error.hasCause<SocketTimeoutException>() || error.hasCause<InterruptedIOException>() -> ConnectionState.Disconnected(
                "SSH 保活超时，服务器未响应",
                DisconnectCause.KEEPALIVE_TIMEOUT,
            )
            error.hasCause<SocketException>() || activeSession?.isConnected == false -> ConnectionState.Disconnected(
                "SSH 传输连接已中断${error?.safeMessage()?.let { "：$it" }.orEmpty()}",
                DisconnectCause.TRANSPORT_CLOSED,
            )
            activeChannel?.isClosed == true -> ConnectionState.Disconnected(
                "远端已关闭 Shell 通道",
                DisconnectCause.REMOTE_CHANNEL_CLOSED,
            )
            error != null -> ConnectionState.Disconnected(
                "SSH 读取失败：${error.safeMessage()}",
                DisconnectCause.READ_ERROR,
            )
            else -> ConnectionState.Disconnected(
                "SSH 输出流已结束",
                DisconnectCause.TRANSPORT_CLOSED,
            )
        }
    }

    override fun respondToHostKey(accept: Boolean) {
        hostKeyDecision.getAndSet(null)?.complete(accept)
        if (mutableHostKeyRequest.value?.issue == HostKeyIssue.CHANGED) {
            mutableHostKeyRequest.value = null
        }
    }

    override fun close() {
        closed = true
        // 先解除可能阻塞在主机密钥确认上的等待：连接线程持锁等待 decision 时，
        // 直接 complete(false) 使其立即结束，避免关闭线程等锁最多 60 秒。
        hostKeyDecision.getAndSet(null)?.complete(false)
        mutableHostKeyRequest.value = null
        if (lifecycleMutex.tryLock()) {
            try {
                closeLocked()
            } finally {
                lifecycleMutex.unlock()
            }
        } else {
            // 有 connect/disconnect 在途：绝不能与在途操作并发修改字段（窄窗口竞态）。
            // 在后台线程等待锁，让在途操作先结束再执行完整清理。close 调用稀少，
            // 临时线程可接受；closeLocked 内的 scope.cancel() 对等待方无害。
            Thread {
                runBlocking { lifecycleMutex.withLock { closeLocked() } }
            }.apply {
                isDaemon = true
                name = "ssh-helper-session-close"
                start()
            }
        }
    }

    private fun closeLocked() {
        hostKeyDecision.getAndSet(null)?.complete(false)
        mutableHostKeyRequest.value = null
        val activeChannel = channel
        val activeSession = session
        val activeJump = jumpSession
        val job = readerJob
        readerJob = null
        writer = null
        channel = null
        session = null
        jumpSession = null
        shellEnabled = false
        runCatching { activeChannel?.disconnect() }
        runCatching { activeSession?.disconnect() }
        runCatching { activeJump?.disconnect() }
        job?.cancel()
        mutableStage.value = ConnectionStage.READY
        mutableState.value = ConnectionState.Disconnected("应用已关闭连接", DisconnectCause.APP_CLOSED)
        scope.cancel()
    }

    private inner class SessionHostKeyRepository(
        private val profile: HostProfile,
        private val expected: KnownHostEntity?,
        private val subject: HostKeySubject = HostKeySubject.TARGET,
    ) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            val encoded = Base64.getEncoder().encodeToString(key)
            mutableStage.value = when (subject) {
                HostKeySubject.JUMP -> ConnectionStage.JUMP_HOST_KEY
                HostKeySubject.TARGET -> ConnectionStage.TARGET_HOST_KEY
            }
            if (expected != null) {
                if (compareHostKey(expected.keyBase64, key) == HostKeyMatch.MATCH) {
                    return HostKeyRepository.OK
                }
                mutableHostKeyRequest.value = HostKeyRequest(
                    profile.hostname,
                    profile.port,
                    HostKey(profile.hostname, key).type,
                    sha256Fingerprint(key),
                    expected.fingerprintSha256,
                    HostKeyIssue.CHANGED,
                    subject,
                )
                return HostKeyRepository.CHANGED
            }

            val hostKey = HostKey(profile.hostname, key)
            val request = HostKeyRequest(
                profile.hostname,
                profile.port,
                hostKey.type,
                sha256Fingerprint(key),
                issue = HostKeyIssue.UNKNOWN,
                subject = subject,
            )
            val decision = CompletableDeferred<Boolean>()
            hostKeyDecision.set(decision)
            mutableHostKeyRequest.value = request
            // 超时按拒绝处理：用户启动后锁屏、服务停止等场景不能让连接线程无限挂起。
            val accepted = runBlocking {
                withTimeoutOrNull(HOST_KEY_CONFIRM_TIMEOUT_MS) { decision.await() }
            } ?: false
            mutableHostKeyRequest.value = null
            if (!accepted) return HostKeyRepository.NOT_INCLUDED
            runBlocking {
                knownHostDao.insert(
                    KnownHostEntity(
                        id = "${profile.hostname.lowercase()}:${profile.port}",
                        hostname = profile.hostname,
                        port = profile.port,
                        keyType = hostKey.type,
                        keyBase64 = encoded,
                        fingerprintSha256 = request.fingerprint,
                    )
                )
            }
            return HostKeyRepository.OK
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "SSH Helper encrypted database"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

}

internal enum class HostKeyMatch { UNKNOWN, MATCH, CHANGED }

internal fun compareHostKey(expectedBase64: String?, key: ByteArray): HostKeyMatch {
    if (expectedBase64 == null) return HostKeyMatch.UNKNOWN
    val presented = Base64.getEncoder().encodeToString(key)
    return if (MessageDigest.isEqual(expectedBase64.encodeToByteArray(), presented.encodeToByteArray())) {
        HostKeyMatch.MATCH
    } else {
        HostKeyMatch.CHANGED
    }
}

internal fun sha256Fingerprint(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String = password
    override fun promptPassword(message: String?): Boolean = true
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit
    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String>? = prompt?.map { password }?.toTypedArray()
}

private object NonInteractiveUserInfo : UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?): Boolean = false
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit
}

private fun Throwable.toChineseMessage(): String {
    val raw = message.orEmpty()
    return when {
        this is SocketTimeoutException || raw.contains("timeout", ignoreCase = true) -> "连接超时，请检查地址、端口和网络"
        this is ConnectException || raw.contains("Connection refused", ignoreCase = true) -> "服务器拒绝连接，请检查 SSH 端口"
        raw.contains("Too many authentication failures", ignoreCase = true) -> "认证尝试次数过多，服务器可能已临时锁定账号，请稍后重试"
        raw.contains("Auth fail", ignoreCase = true) -> "认证失败，请检查用户名、密码或私钥"
        raw.contains("invalid privatekey", ignoreCase = true) -> "私钥格式或私钥口令不正确"
        raw.contains("HostKey", ignoreCase = true) || raw.contains("reject HostKey", ignoreCase = true) -> "服务器身份未被信任或主机密钥已变化"
        this is JSchException && raw.isNotBlank() -> "SSH 连接失败：$raw"
        raw.isNotBlank() -> raw
        else -> "连接发生未知错误"
    }
}

private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean {
    var current = this
    while (current != null) {
        if (current is T) return true
        current = current.cause
    }
    return false
}

private fun Throwable.safeMessage(): String = message?.takeIf(String::isNotBlank)?.take(160) ?: this::class.java.simpleName

internal const val SSH_CONNECT_TIMEOUT_MS = 15_000
internal const val SSH_KEEPALIVE_INTERVAL_MS = 20_000
internal const val SSH_KEEPALIVE_MAX_MISSES = 3
/** 无 shell 转发会话的传输监视轮询间隔。 */
internal const val TRANSPORT_WATCH_INTERVAL_MS = 5_000L
/** 未知主机密钥确认超时；超时按拒绝处理。 */
internal const val HOST_KEY_CONFIRM_TIMEOUT_MS = 60_000L
