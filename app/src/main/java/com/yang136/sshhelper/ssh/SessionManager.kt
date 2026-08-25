package com.yang136.sshhelper.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.HostRepository
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.security.CredentialVault
import com.yang136.sshhelper.security.VaultLockedException
import com.yang136.sshhelper.security.VaultState
import com.yang136.sshhelper.settings.SettingsRepository
import com.yang136.sshhelper.sftp.SftpClient
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JvmInline
value class SessionId(val value: String)

data class ManagedSessionState(
    val id: SessionId,
    val profile: HostProfile,
    val displayName: String,
    val connection: ConnectionState = ConnectionState.Idle,
    val needsCredential: Boolean = false,
    val credentialRole: CredentialRole = CredentialRole.TARGET,
    val jumpProfile: HostProfile? = null,
    val stage: ConnectionStage = ConnectionStage.READY,
    val hostKeyRequest: HostKeyRequest? = null,
    val reconnectAttempt: Int? = null,
    val features: Set<SessionFeature> = setOf(SessionFeature.SHELL),
    val needsVaultUnlock: Boolean = false,
)

enum class SessionFeature { SHELL, SFTP, PORT_FORWARD }

sealed interface TerminalOutputEvent {
    val sequence: Long

    data class Snapshot(override val sequence: Long, val bytes: ByteArray) : TerminalOutputEvent
    data class Chunk(override val sequence: Long, val bytes: ByteArray) : TerminalOutputEvent
}

fun interface SshSessionFactory {
    fun create(): SshSession
}

interface SessionManager {
    val sessions: StateFlow<List<ManagedSessionState>>

    fun create(hostId: Long, feature: SessionFeature = SessionFeature.SHELL): SessionId?
    fun create(profile: HostProfile, feature: SessionFeature = SessionFeature.SHELL): SessionId?
    fun state(id: SessionId): StateFlow<ManagedSessionState>?
    fun output(id: SessionId): Flow<TerminalOutputEvent>

    suspend fun connect(id: SessionId, credential: Credential, remember: Boolean)
    suspend fun write(id: SessionId, data: ByteArray)
    suspend fun resize(id: SessionId, columns: Int, rows: Int)
    suspend fun reconnect(id: SessionId)
    suspend fun disconnect(id: SessionId)
    suspend fun cancelReconnect(id: SessionId)
    suspend fun close(id: SessionId)
    suspend fun closeAll()
    suspend fun sftp(id: SessionId): SftpClient
    suspend fun newSftpClient(id: SessionId): SftpClient
    suspend fun forwardSession(id: SessionId): PortForwardCapableSession?
    fun enableFeature(id: SessionId, feature: SessionFeature)
    fun respondToHostKey(id: SessionId, accept: Boolean)
    suspend fun forgetChangedHostKey(id: SessionId)
}

class DefaultSessionManager(
    context: Context,
    private val hostRepository: HostRepository,
    private val knownHostDao: KnownHostDao,
    private val sessionFactory: SshSessionFactory = SshSessionFactory { JschSshSession(knownHostDao) },
    private val credentialVault: CredentialVault? = null,
    private val settings: SettingsRepository? = null,
) : SessionManager {
    private val applicationContext = context.applicationContext
    private val managerJob = SupervisorJob()
    private val scope = CoroutineScope(managerJob + Dispatchers.IO)
    private val runtimes = linkedMapOf<SessionId, RuntimeSession>()
    private val hostCache = mutableMapOf<Long, HostProfile>()
    private val nextOrdinal = mutableMapOf<Long, Int>()
    private val mutableSessions = MutableStateFlow<List<ManagedSessionState>>(emptyList())
    override val sessions: StateFlow<List<ManagedSessionState>> = mutableSessions.asStateFlow()
    /** 锁库后是否保留活动转发隧道的内存重连凭据（设置开关，默认开）。 */
    private val reconnectAfterLock = MutableStateFlow(true)

    /**
     * 由 AppContainer 注入：查询当前有活动转发的会话 ID 集合。
     * 凭据租约只授予真正在跑隧道的会话（而非仅带 PORT_FORWARD 特性的会话）。
     */
    @Volatile
    var forwardActivityProvider: (() -> Set<SessionId>)? = null

    init {
        settings?.let { repo ->
            scope.launch {
                repo.settings.collect { settings ->
                    reconnectAfterLock.value = settings.forwardReconnectAfterLock
                    // 关闭开关时立即收回已保留的租约凭据，不让凭据滞留内存。
                    if (!settings.forwardReconnectAfterLock && credentialVault?.state?.value == VaultState.Locked) {
                        synchronized(runtimes) { runtimes.values.toList() }.forEach(::revokeCredentialsFor)
                    }
                }
            }
        }
        scope.launch {
            hostRepository.hosts.collect { hosts ->
                synchronized(hostCache) {
                    hostCache.clear()
                    hosts.associateByTo(hostCache, HostProfile::id)
                }
            }
        }
        credentialVault?.let { vault ->
            scope.launch {
                vault.state.collect { vaultState ->
                    when (vaultState) {
                        VaultState.Locked -> synchronized(runtimes) { runtimes.values.toList() }.forEach { runtime ->
                            if (keepsForwardCredentials(runtime)) {
                                // 凭据租约：活动转发隧道保留内存凭据，断线后仍可无人值守重连。
                                // 凭据生命周期 = 隧道生命周期，随 stop/close/进程死亡清除。
                                update(runtime) { it.copy(needsVaultUnlock = false) }
                            } else {
                                revokeCredentialsFor(runtime)
                            }
                        }
                        is VaultState.Unlocked, VaultState.Disabled -> restoreVaultCredentials()
                        is VaultState.Unavailable -> synchronized(runtimes) { runtimes.values.toList() }.forEach { runtime ->
                            update(runtime) { it.copy(needsVaultUnlock = true) }
                        }
                    }
                }
            }
        }
        // Forwarding tunnels must recover as soon as connectivity returns (Wi-Fi ⇄ cellular
        // switch, screen-off network stall): trigger immediate retries instead of waiting for
        // the next backoff tick. Shell sessions keep their per-host autoReconnect policy.
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching {
            // 只监听默认网络：多个网络的回调事件不能作用于默认网络上的会话。
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private val networkLock = Any()
                private var lastNetwork: Network? = null

                override fun onAvailable(network: Network) {
                    val previous = synchronized(networkLock) { lastNetwork.also { lastNetwork = network } }
                    when {
                        previous == null -> retryForwardSessions() // 首次注册：仅补齐明显断开的会话
                        previous != network -> forceReconnectForwardSessions() // 默认网络切换
                    }
                }

                override fun onLost(network: Network) {
                    synchronized(networkLock) { if (lastNetwork == network) lastNetwork = null }
                    retryForwardSessions()
                }
            })
        }
    }

    /**
     * 默认网络已切换（Wi-Fi ⇄ 蜂窝）：系统会强制终止旧网络上的现有 TCP 连接，
     * 这里主动失效并串行重建转发隧道，而不是等连接自然断掉再退避重连。
     */
    private fun forceReconnectForwardSessions() {
        val candidates = synchronized(runtimes) { runtimes.values.toList() }.filter { runtime ->
            hasActiveForward(runtime) &&
                runtime.state.value.connection is ConnectionState.Connected &&
                !runtime.userDisconnected && !runtime.reconnectBlocked && runtime.routeCredentials != null
        }
        candidates.forEach { runtime ->
            synchronized(runtime.reconnectLock) {
                if (runtime.reconnectJob?.isActive == true) return@forEach
                runtime.reconnectJob = runtime.scope.launch {
                    // 先断开旧连接（DisconnectCause.USER 不会触发观察器重复调度重连），
                    // 再按退避在新默认网络上重建。
                    runCatching { runtime.ssh.disconnect() }
                    reconnectLoop(runtime)
                }
            }
        }
    }

    override fun create(hostId: Long, feature: SessionFeature): SessionId? =
        synchronized(hostCache) { hostCache[hostId] }?.let { create(it, feature) }

    override fun create(profile: HostProfile, feature: SessionFeature): SessionId? {
        val runtime = synchronized(runtimes) {
            if (runtimes.size >= MAX_MANAGED_SESSIONS) return null
            val ordinal = (nextOrdinal[profile.id] ?: 0) + 1
            nextOrdinal[profile.id] = ordinal
            val id = SessionId(UUID.randomUUID().toString())
            RuntimeSession(
                id = id,
                initial = ManagedSessionState(
                    id = id,
                    profile = profile,
                    displayName = sessionDisplayName(profile.name, ordinal),
                    features = setOf(feature),
                ),
                ssh = sessionFactory.create(),
            ).also { runtimes[id] = it }
        }
        observe(runtime)
        publishSessions()
        runtime.scope.launch {
            val jump = resolveJumpProfile(profile)
            update(runtime) { it.copy(jumpProfile = jump) }
            if (jump != null) {
                when (val jumpLoad = loadCredential(jump)) {
                    is CredentialLoad.Loaded -> Unit
                    CredentialLoad.Missing -> {
                        update(runtime) { it.copy(needsCredential = true, credentialRole = CredentialRole.JUMP, jumpProfile = jump) }
                        return@launch
                    }
                    CredentialLoad.Locked -> {
                        update(runtime) { it.copy(needsVaultUnlock = true) }
                        return@launch
                    }
                }
            }
            val jumpCredential = jump?.let { (loadCredential(it) as? CredentialLoad.Loaded)?.credential }
            when (val targetLoad = loadCredential(profile)) {
                is CredentialLoad.Loaded -> connectInternal(runtime, jump, jumpCredential, targetLoad.credential)
                CredentialLoad.Missing -> update(runtime) {
                    it.copy(needsCredential = true, credentialRole = CredentialRole.TARGET, jumpProfile = jump)
                }
                CredentialLoad.Locked -> update(runtime) { it.copy(needsVaultUnlock = true) }
            }
        }
        return runtime.id
    }

    override fun state(id: SessionId): StateFlow<ManagedSessionState>? = runtime(id)?.state
    override fun output(id: SessionId): Flow<TerminalOutputEvent> {
        val runtime = runtime(id) ?: return flowOf(TerminalOutputEvent.Snapshot(0, ByteArray(0)))
        return runtime.output.onSubscription { emit(runtime.snapshot()) }
    }

    /** Raw tail of the session scrollback, used as AI context. */
    fun recentOutput(id: SessionId, maxBytes: Int): ByteArray =
        runtime(id)?.snapshot()?.bytes?.takeLast(maxBytes)?.toByteArray() ?: ByteArray(0)

    override suspend fun connect(id: SessionId, credential: Credential, remember: Boolean) {
        val runtime = runtime(id) ?: return clearCredential(credential)
        runtime.reconnectJob?.cancel()
        runtime.userDisconnected = false
        runtime.reconnectBlocked = false
        when (runtime.state.value.credentialRole) {
            CredentialRole.JUMP -> {
                val jump = runtime.state.value.jumpProfile ?: return clearCredential(credential)
                if (remember) hostRepository.save(jump.copy(rememberCredential = true), credential.copyCredential())
                runtime.pendingJumpCredential = credential.copyCredential()
                clearCredential(credential)
                when (val targetLoad = loadCredential(runtime.state.value.profile)) {
                    is CredentialLoad.Loaded -> {
                        connectInternal(runtime, jump, runtime.pendingJumpCredential, targetLoad.credential)
                        clearCredential(runtime.pendingJumpCredential)
                        runtime.pendingJumpCredential = null
                        clearCredential(targetLoad.credential)
                    }
                    CredentialLoad.Missing -> update(runtime) {
                        it.copy(needsCredential = true, credentialRole = CredentialRole.TARGET, jumpProfile = jump)
                    }
                    CredentialLoad.Locked -> update(runtime) { it.copy(needsVaultUnlock = true) }
                }
            }
            CredentialRole.TARGET -> {
                val profile = runtime.state.value.profile
                if (remember) {
                    val updated = profile.copy(
                        rememberCredential = true,
                        privateKeyName = (credential as? Credential.PrivateKey)?.fileName ?: profile.privateKeyName,
                    )
                    hostRepository.save(updated, credential.copyCredential())
                    update(runtime) { state -> state.copy(profile = updated) }
                }
                val jump = runtime.state.value.jumpProfile
                val jumpCredential = runtime.pendingJumpCredential ?: when (val loaded = loadCredential(jump)) {
                    is CredentialLoad.Loaded -> loaded.credential
                    else -> null
                }
                if (jump != null && jumpCredential == null) {
                    clearCredential(credential)
                    update(runtime) {
                        it.copy(needsCredential = true, credentialRole = CredentialRole.JUMP, jumpProfile = jump)
                    }
                } else {
                    connectInternal(runtime, jump, jumpCredential, credential)
                    clearCredential(jumpCredential)
                    runtime.pendingJumpCredential?.let(::clearCredential)
                    runtime.pendingJumpCredential = null
                }
            }
        }
    }

    override suspend fun write(id: SessionId, data: ByteArray) {
        runtime(id)?.ssh?.write(data)
    }

    override suspend fun resize(id: SessionId, columns: Int, rows: Int) {
        runtime(id)?.let {
            it.columns = columns
            it.rows = rows
            it.ssh.resize(columns, rows)
        }
    }

    override suspend fun reconnect(id: SessionId) {
        val runtime = runtime(id) ?: return
        runtime.reconnectJob?.cancel()
        runtime.browserSftp?.close()
        runtime.browserSftp = null
        runtime.userDisconnected = false
        runtime.reconnectBlocked = false
        val routeCredentials = runtime.routeCredentials
        if (routeCredentials == null) {
            update(runtime) {
                it.copy(
                    needsCredential = !it.profile.rememberCredential,
                    needsVaultUnlock = it.profile.rememberCredential,
                    reconnectAttempt = null,
                )
            }
            return
        }
        appendLocal(runtime, "\r\n\u001b[36m—— 正在建立新的 SSH Shell ——\u001b[0m\r\n")
        runtime.ssh.connect(
            SshRoute(runtime.state.value.profile, runtime.state.value.jumpProfile),
            routeCredentials,
            openShell = openShellFor(runtime),
        )
        if (runtime.ssh.state.value is ConnectionState.Connected) {
            runtime.ssh.resize(runtime.columns, runtime.rows)
            appendLocal(runtime, "\r\n\u001b[33m—— 已重新连接；旧 Shell 上下文已丢失 ——\u001b[0m\r\n")
        }
    }

    override suspend fun disconnect(id: SessionId) {
        val runtime = runtime(id) ?: return
        runtime.userDisconnected = true
        runtime.reconnectJob?.cancel()
        update(runtime) { it.copy(reconnectAttempt = null) }
        runtime.browserSftp?.close()
        runtime.browserSftp = null
        runtime.ssh.disconnect()
    }

    override suspend fun cancelReconnect(id: SessionId) {
        val runtime = runtime(id) ?: return
        runtime.userDisconnected = true
        runtime.reconnectJob?.cancel()
        runtime.reconnectJob = null
        update(runtime) { it.copy(reconnectAttempt = null) }
    }

    override suspend fun close(id: SessionId) {
        val runtime = synchronized(runtimes) { runtimes.remove(id) } ?: return
        // Remove the session from every UI immediately. Socket cleanup can suspend briefly and
        // must not leave behind a stale, reopenable card with no attached output stream.
        publishSessions()
        runtime.userDisconnected = true
        runtime.reconnectJob?.cancel()
        runtime.browserSftp?.close()
        runtime.browserSftp = null
        runCatching { runtime.ssh.disconnect() }
        runtime.ssh.close()
        clearRouteCredentials(runtime)
        runtime.pendingJumpCredential?.let(::clearCredential)
        runtime.pendingJumpCredential = null
        runtime.scope.cancel()
    }

    override suspend fun closeAll() {
        val ids = synchronized(runtimes) { runtimes.keys.toList() }
        ids.forEach { close(it) }
    }

    override suspend fun sftp(id: SessionId): SftpClient {
        val runtime = runtime(id) ?: error("会话不存在")
        runtime.browserSftp?.let { return it }
        val client = newSftpClient(id)
        runtime.browserSftp = client
        update(runtime) { it.copy(features = it.features + SessionFeature.SFTP) }
        return client
    }

    override suspend fun newSftpClient(id: SessionId): SftpClient {
        val runtime = runtime(id) ?: error("会话不存在")
        val provider = runtime.ssh as? SftpCapableSession ?: error("当前 SSH 实现不支持 SFTP")
        return provider.openSftpClient()
    }

    override suspend fun forwardSession(id: SessionId): PortForwardCapableSession? =
        runtime(id)?.ssh as? PortForwardCapableSession

    override fun enableFeature(id: SessionId, feature: SessionFeature) {
        runtime(id)?.let { runtime ->
            val had = runtime.state.value.features
            update(runtime) { it.copy(features = it.features + feature) }
            // 转发专用会话默认不带 shell；用户把它打开成终端时才需要 shell 通道，
            // 补一次带 shell 的重连（连接期间隧道会短暂中断一次）。
            if (feature == SessionFeature.SHELL && SessionFeature.SHELL !in had &&
                runtime.ssh.state.value is ConnectionState.Connected && !runtime.userDisconnected
            ) {
                runtime.scope.launch { reconnect(runtime.id) }
            }
        }
    }

    override fun respondToHostKey(id: SessionId, accept: Boolean) {
        runtime(id)?.ssh?.respondToHostKey(accept)
    }

    override suspend fun forgetChangedHostKey(id: SessionId) {
        val runtime = runtime(id) ?: return
        val request = runtime.state.value.hostKeyRequest ?: return
        knownHostDao.delete(request.hostname, request.port)
        runtime.ssh.respondToHostKey(false)
        update(runtime) {
            it.copy(
                hostKeyRequest = null,
                connection = ConnectionState.Error("旧主机指纹已清除，请重新连接并核实新指纹"),
            )
        }
    }

    private fun observe(runtime: RuntimeSession) {
        runtime.scope.launch {
            runtime.ssh.state.collect { connection ->
                update(runtime) { it.copy(connection = connection) }
                if (connection is ConnectionState.Connected) {
                    runtime.everConnected = true
                    update(runtime) { it.copy(reconnectAttempt = null) }
                    runCatching { hostRepository.markConnected(runtime.state.value.profile.id) }
                } else if (
                    connection is ConnectionState.Disconnected && connection.cause.isAutoReconnectEligible() && runtime.everConnected &&
                    !runtime.userDisconnected && runtime.reconnectJob?.isActive != true &&
                    autoReconnectWanted(runtime)
                ) {
                    scheduleReconnect(runtime)
                }
            }
        }
        runtime.scope.launch {
            runtime.ssh.stage.collect { stage -> update(runtime) { it.copy(stage = stage) } }
        }
        runtime.scope.launch {
            runtime.ssh.output.collect { bytes ->
                runtime.publish(bytes)
            }
        }
        runtime.scope.launch {
            runtime.ssh.hostKeyRequest.collect { request ->
                update(runtime) { it.copy(hostKeyRequest = request) }
            }
        }
    }

    private suspend fun resolveJumpProfile(profile: HostProfile): HostProfile? {
        val jumpId = profile.jumpHostId ?: return null
        return hostRepository.getHost(jumpId)?.takeIf { it.jumpHostId == null }
    }

    private suspend fun loadCredential(profile: HostProfile?): CredentialLoad {
        if (profile == null) return CredentialLoad.Missing
        return try {
            CredentialLoad.Loaded(hostRepository.credentialFor(profile) ?: return CredentialLoad.Missing)
        } catch (error: VaultLockedException) {
            CredentialLoad.Locked
        }
    }

    private suspend fun connectInternal(
        runtime: RuntimeSession,
        jump: HostProfile?,
        jumpCredential: Credential?,
        targetCredential: Credential,
    ) {
        var jumpSnapshot = jump ?: runtime.state.value.jumpProfile
        if (jumpSnapshot == null && runtime.state.value.profile.jumpHostId != null) {
            jumpSnapshot = hostRepository.getHost(runtime.state.value.profile.jumpHostId!!)
            update(runtime) { it.copy(jumpProfile = jumpSnapshot) }
        }
        if (jumpSnapshot != null && jumpCredential == null) {
            update(runtime) {
                it.copy(needsCredential = true, credentialRole = CredentialRole.JUMP, jumpProfile = jumpSnapshot)
            }
            return
        }
        update(runtime) { it.copy(needsCredential = false, needsVaultUnlock = false, reconnectAttempt = null) }
        // Store a deep copy for reconnect; the originals passed in are cleared below so the
        // stored route credentials survive for later automatic reconnects.
        val targetProxyPassword = if (jumpSnapshot == null) hostRepository.proxyPasswordFor(runtime.state.value.profile) else null
        val stored = RouteCredentials(
            target = targetCredential.copyCredential(),
            jump = jumpCredential?.copyCredential(),
            targetProxyPassword = targetProxyPassword,
            jumpProxyPassword = if (jumpSnapshot != null) hostRepository.proxyPasswordFor(jumpSnapshot) else null,
        )
        replaceRouteCredentials(runtime, stored)
        try {
            runtime.ssh.connect(
                SshRoute(runtime.state.value.profile, jumpSnapshot),
                stored,
                openShell = openShellFor(runtime),
            )
        } finally {
            clearCredential(targetCredential)
            clearCredential(jumpCredential)
        }
    }

    private fun scheduleReconnect(runtime: RuntimeSession) {
        // 检查与赋值必须原子：observe() 收集器与网络回调（binder 线程）可能同时触发，
        // 否则会创建两个并发重连任务，各自调用 ssh.connect()。
        synchronized(runtime.reconnectLock) {
            if (runtime.reconnectJob?.isActive == true) return
            runtime.reconnectJob = runtime.scope.launch { reconnectLoop(runtime) }
        }
    }

    private suspend fun reconnectLoop(runtime: RuntimeSession) {
        // 转发判定以"活动转发"为准：复用普通终端会话承载转发时同样适用转发重连策略。
        val isForward = hasActiveForward(runtime)
        val delays = if (isForward) FORWARD_RECONNECT_DELAYS_SECONDS else AUTO_RECONNECT_DELAYS_SECONDS
        var attempt = 0
        while (true) {
            delay(backoffMillis(delays[attempt.coerceAtMost(delays.lastIndex)]))
            attempt++
            update(runtime) { it.copy(reconnectAttempt = attempt) }
            // 等待期间会话已恢复（例如手动重连成功）：直接结束，避免无谓重建。
            if (runtime.ssh.state.value is ConnectionState.Connected) return
            while (!networkAvailable()) delay(1_000)
            val routeCredentials = runtime.routeCredentials ?: break
            runtime.ssh.connect(
                SshRoute(runtime.state.value.profile, runtime.state.value.jumpProfile),
                routeCredentials,
                openShell = openShellFor(runtime),
            )
            if (runtime.ssh.state.value is ConnectionState.Connected) {
                runtime.ssh.resize(runtime.columns, runtime.rows)
                if (isForward) {
                    appendLocal(runtime, "\r\n\u001b[36m—— 转发隧道已自动重连 ——\u001b[0m\r\n")
                } else {
                    appendLocal(runtime, "\r\n\u001b[33m—— 已自动重连；旧 Shell 上下文已丢失 ——\u001b[0m\r\n")
                }
                update(runtime) { it.copy(reconnectAttempt = null) }
                return
            }
            val error = (runtime.ssh.state.value as? ConnectionState.Error)?.message.orEmpty()
            if (error.contains("认证") || error.contains("私钥") || error.contains("主机")) {
                // Credentials or host keys are invalid: retrying would only hammer the
                // server (and could trip PAM lockout). Stop until the user acts.
                runtime.reconnectBlocked = true
                break
            }
            if (runtime.userDisconnected) break
            // Shell sessions keep the bounded 3-attempt policy; forward tunnels retry
            // indefinitely with capped backoff so a dropped tunnel comes back by itself.
            if (!isForward && attempt >= AUTO_RECONNECT_DELAYS_SECONDS.size) break
        }
        update(runtime) { it.copy(reconnectAttempt = null) }
    }

    /**
     * 该会话当前是否承载活动转发（含复用普通终端会话承载转发的情况）。
     * 由 ForwardManager 的活动转发状态驱动，而非永久性的 PORT_FORWARD feature。
     */
    private fun hasActiveForward(runtime: RuntimeSession): Boolean =
        forwardActivityProvider?.invoke()?.contains(runtime.id) == true

    /** Forwarding tunnels always reconnect; shells follow the per-host autoReconnect toggle. */
    private fun autoReconnectWanted(runtime: RuntimeSession): Boolean =
        runtime.state.value.profile.autoReconnect || hasActiveForward(runtime)

    /**
     * Kicks reconnection for forward sessions that are down and retryable the moment
     * connectivity returns, instead of waiting for the next backoff tick. Sessions blocked on
     * auth/host-key failures or missing stored credentials are left alone.
     */
    private fun retryForwardSessions() {
        val candidates = synchronized(runtimes) { runtimes.values.toList() }.filter { runtime ->
            hasActiveForward(runtime) &&
                runtime.state.value.connection !is ConnectionState.Connected &&
                // Connecting 期间首次连接可能仍在进行：此时调度重连会在 2 秒后
                // 拆除健康的在途连接。等连接结果（Connected/Error）出来再处理。
                runtime.state.value.connection !is ConnectionState.Connecting &&
                !runtime.userDisconnected && !runtime.reconnectBlocked && runtime.routeCredentials != null
        }
        candidates.forEach { runtime ->
            if (runtime.reconnectJob?.isActive != true) scheduleReconnect(runtime)
        }
    }

    /**
     * 锁库后是否为该会话保留内存重连凭据：
     * 设置开关开启 + **该会话当前有活动转发**（不依赖 PORT_FORWARD feature，
     * 复用普通终端会话承载转发时同样获得租约）。
     */
    private fun keepsForwardCredentials(runtime: RuntimeSession): Boolean {
        if (!reconnectAfterLock.value) return false
        return hasActiveForward(runtime)
    }

    /** 收回单个会话的凭据租约：取消重连任务、清空内存凭据并标记等待解锁。 */
    private fun revokeCredentialsFor(runtime: RuntimeSession) {
        synchronized(runtime.reconnectLock) {
            runtime.reconnectJob?.cancel()
            runtime.reconnectJob = null
        }
        clearRouteCredentials(runtime)
        update(runtime) { it.copy(needsVaultUnlock = it.profile.rememberCredential) }
    }

    /** 会话是否需要 shell 通道：只有 SHELL 功能会话才创建（转发专用会话不创建）。 */
    private fun openShellFor(runtime: RuntimeSession): Boolean =
        SessionFeature.SHELL in runtime.state.value.features

    private suspend fun restoreVaultCredentials() {
        synchronized(runtimes) { runtimes.values.toList() }.forEach { runtime ->
            if (!runtime.state.value.needsVaultUnlock || !runtime.state.value.profile.rememberCredential) return@forEach
            val state = runtime.state.value
            val target = runCatching { hostRepository.credentialFor(state.profile) }.getOrNull() ?: return@forEach
            val jump = state.jumpProfile
            val jumpCredential = if (jump == null) null else runCatching { hostRepository.credentialFor(jump) }.getOrNull()
            if (jump != null && jumpCredential == null) {
                update(runtime) { it.copy(needsVaultUnlock = false, needsCredential = true, credentialRole = CredentialRole.JUMP, jumpProfile = jump) }
                clearCredential(target)
                return@forEach
            }
            val stored = RouteCredentials(
                target = target.copyCredential(),
                jump = jumpCredential?.copyCredential(),
                targetProxyPassword = if (jump == null) runCatching { hostRepository.proxyPasswordFor(state.profile) }.getOrNull() else null,
                jumpProxyPassword = if (jump != null) runCatching { hostRepository.proxyPasswordFor(jump) }.getOrNull() else null,
            )
            replaceRouteCredentials(runtime, stored)
            clearCredential(target)
            clearCredential(jumpCredential)
            update(runtime) { it.copy(needsVaultUnlock = false) }
            if (runtime.ssh.state.value !is ConnectionState.Connected) {
                runtime.scope.launch { reconnect(runtime.id) }
            }
        }
    }

    private fun networkAvailable(): Boolean {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // 只要求存在默认网络：局域网 Wi-Fi 可能没有 NET_CAPABILITY_INTERNET，
        // 但依然能到达局域网内的 SSH 主机。
        return manager.activeNetwork != null
    }

    private suspend fun appendLocal(runtime: RuntimeSession, text: String) {
        runtime.publish(text.encodeToByteArray())
    }

    private fun update(runtime: RuntimeSession, transform: (ManagedSessionState) -> ManagedSessionState) {
        runtime.mutableState.update(transform)
        publishSessions()
    }

    private fun publishSessions() {
        mutableSessions.update { synchronized(runtimes) { runtimes.values.map { it.state.value } } }
    }

    private fun runtime(id: SessionId): RuntimeSession? = synchronized(runtimes) { runtimes[id] }

    private fun replaceRouteCredentials(runtime: RuntimeSession, credentials: RouteCredentials) {
        clearRouteCredentials(runtime)
        runtime.routeCredentials = credentials
    }

    private fun clearRouteCredentials(runtime: RuntimeSession) {
        clearCredential(runtime.routeCredentials?.target)
        clearCredential(runtime.routeCredentials?.jump)
        runtime.routeCredentials = null
    }

    private class RuntimeSession(
        val id: SessionId,
        initial: ManagedSessionState,
        val ssh: SshSession,
    ) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val mutableState = MutableStateFlow(initial)
        val state: StateFlow<ManagedSessionState> = mutableState.asStateFlow()
        val mutableOutput = MutableSharedFlow<TerminalOutputEvent>(replay = 128, extraBufferCapacity = 64)
        val output = mutableOutput.asSharedFlow()
        private val outputMutex = Mutex()
        private val scrollback = ByteArrayOutputStream()
        private var outputSequence = 0L
        var routeCredentials: RouteCredentials? = null
        var pendingJumpCredential: Credential? = null
        var reconnectJob: Job? = null
        /** 保护 reconnectJob 的"检查 isActive + 赋值"，重连调度必须原子。 */
        val reconnectLock = Any()
        var userDisconnected = false
        var reconnectBlocked = false
        var everConnected = false
        var columns = 80
        var rows = 24
        var browserSftp: SftpClient? = null

        suspend fun publish(bytes: ByteArray) = outputMutex.withLock {
            mutableOutput.emit(append(bytes))
        }

        fun append(bytes: ByteArray): TerminalOutputEvent.Chunk = synchronized(scrollback) {
            if (scrollback.size() + bytes.size > MAX_SCROLLBACK) {
                val retained = scrollback.toByteArray().takeLast(MAX_SCROLLBACK / 2).toByteArray()
                scrollback.reset()
                scrollback.write(retained)
            }
            scrollback.write(bytes)
            TerminalOutputEvent.Chunk(++outputSequence, bytes)
        }

        fun snapshot(): TerminalOutputEvent.Snapshot = synchronized(scrollback) {
            TerminalOutputEvent.Snapshot(outputSequence, scrollback.toByteArray())
        }
    }

    private sealed interface CredentialLoad {
        data class Loaded(val credential: Credential) : CredentialLoad
        data object Missing : CredentialLoad
        data object Locked : CredentialLoad
    }

    private companion object {
        const val MAX_SCROLLBACK = 1024 * 1024
    }
}

/** 指数退避（秒）加上 ±20% 随机抖动，避免多条隧道同时重连造成突发。 */
internal fun backoffMillis(seconds: Int): Long {
    val jitter = 0.8 + kotlin.random.Random.nextDouble() * 0.4
    return (seconds * 1_000L * jitter).toLong()
}

internal const val MAX_MANAGED_SESSIONS = 8
internal val AUTO_RECONNECT_DELAYS_SECONDS = intArrayOf(2, 5, 10)
/** Capped backoff for port-forwarding tunnels, which retry until they come back. */
internal val FORWARD_RECONNECT_DELAYS_SECONDS = intArrayOf(2, 5, 10, 30, 60, 120, 300)
internal fun sessionDisplayName(hostName: String, ordinal: Int): String =
    if (ordinal <= 1) hostName else "$hostName $ordinal"

internal fun DisconnectCause.isAutoReconnectEligible(): Boolean = when (this) {
    DisconnectCause.REMOTE_CHANNEL_CLOSED,
    DisconnectCause.TRANSPORT_CLOSED,
    DisconnectCause.KEEPALIVE_TIMEOUT,
    DisconnectCause.READ_ERROR,
    DisconnectCause.WRITE_ERROR -> true
    DisconnectCause.USER,
    DisconnectCause.REMOTE_SHELL_EXIT,
    DisconnectCause.APP_CLOSED,
    DisconnectCause.UNKNOWN -> false
}

internal fun clearCredential(credential: Credential?) {
    when (credential) {
        is Credential.Password -> credential.value.fill('\u0000')
        is Credential.PrivateKey -> {
            credential.bytes.fill(0)
            credential.passphrase?.fill('\u0000')
        }
        null -> Unit
    }
}
