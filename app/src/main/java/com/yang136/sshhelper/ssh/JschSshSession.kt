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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
                SimpleForwardHandle(actual) { active.delPortForwardingL(actual) }
            }
            ForwardType.REMOTE -> {
                val targetHost = request.targetHost ?: error("缺少目标主机")
                val targetPort = request.targetPort ?: error("缺少目标端口")
                active.setPortForwardingR(request.bindAddress, request.listenPort, targetHost, targetPort)
                SimpleForwardHandle(request.listenPort) { active.delPortForwardingR(request.listenPort) }
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
        @Volatile private var closed = false
        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
        }
    }

    override suspend fun connect(route: SshRoute, credentials: RouteCredentials) = withContext(Dispatchers.IO) {
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
            val newChannel = (target.openChannel("shell") as ChannelShell).apply {
                setPtyType("xterm-256color")
                setPtySize(80, 24, 0, 0)
            }
            val input = newChannel.inputStream
            writer = newChannel.outputStream
            channel = newChannel
            newChannel.connect(SSH_CONNECT_TIMEOUT_MS)
            mutableStage.value = ConnectionStage.READY
            mutableState.value = ConnectionState.Connected(connectedLabel(route))
            readerJob = scope.launch { readOutput(input) }
        } catch (error: Throwable) {
            val pendingHostKey = mutableHostKeyRequest.value
            disconnectInternal("连接失败", DisconnectCause.UNKNOWN, publishState = false)
            if (pendingHostKey?.issue == HostKeyIssue.CHANGED) {
                mutableHostKeyRequest.value = pendingHostKey
            }
            mutableStage.value = ConnectionStage.READY
            mutableState.value = ConnectionState.Error(error.toChineseMessage())
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
        val safeColumns = columns.coerceIn(2, 500)
        val safeRows = rows.coerceIn(2, 300)
        runCatching { channel?.takeIf { it.isConnected }?.setPtySize(safeColumns, safeRows, 0, 0) }
        Unit
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal("连接已关闭", DisconnectCause.USER, publishState = true)
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
            val accepted = runBlocking { decision.await() }
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
