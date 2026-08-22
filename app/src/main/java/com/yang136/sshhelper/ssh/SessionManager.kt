package com.yang136.sshhelper.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.HostRepository
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.security.CredentialVault
import com.yang136.sshhelper.security.VaultLockedException
import com.yang136.sshhelper.security.VaultState
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
) : SessionManager {
    private val applicationContext = context.applicationContext
    private val managerJob = SupervisorJob()
    private val scope = CoroutineScope(managerJob + Dispatchers.IO)
    private val runtimes = linkedMapOf<SessionId, RuntimeSession>()
    private val hostCache = mutableMapOf<Long, HostProfile>()
    private val nextOrdinal = mutableMapOf<Long, Int>()
    private val mutableSessions = MutableStateFlow<List<ManagedSessionState>>(emptyList())
    override val sessions: StateFlow<List<ManagedSessionState>> = mutableSessions.asStateFlow()

    init {
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
                            clearRouteCredentials(runtime)
                            update(runtime) { it.copy(needsVaultUnlock = it.profile.rememberCredential) }
                        }
                        is VaultState.Unlocked, VaultState.Disabled -> restoreVaultCredentials()
                        is VaultState.Unavailable -> synchronized(runtimes) { runtimes.values.toList() }.forEach { runtime ->
                            update(runtime) { it.copy(needsVaultUnlock = true) }
                        }
                    }
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

    override suspend fun connect(id: SessionId, credential: Credential, remember: Boolean) {
        val runtime = runtime(id) ?: return clearCredential(credential)
        runtime.reconnectJob?.cancel()
        runtime.userDisconnected = false
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
        runtime.ssh.connect(SshRoute(runtime.state.value.profile, runtime.state.value.jumpProfile), routeCredentials)
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
        runtime(id)?.let { runtime -> update(runtime) { it.copy(features = it.features + feature) } }
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
                    runtime.state.value.profile.autoReconnect && !runtime.userDisconnected &&
                    runtime.reconnectJob?.isActive != true
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
            runtime.ssh.connect(SshRoute(runtime.state.value.profile, jumpSnapshot), stored)
        } finally {
            clearCredential(targetCredential)
            clearCredential(jumpCredential)
        }
    }

    private fun scheduleReconnect(runtime: RuntimeSession) {
        runtime.reconnectJob = runtime.scope.launch {
            for ((index, seconds) in AUTO_RECONNECT_DELAYS_SECONDS.withIndex()) {
                update(runtime) { it.copy(reconnectAttempt = index + 1) }
                delay(seconds * 1_000L)
                while (!networkAvailable()) delay(1_000)
                val routeCredentials = runtime.routeCredentials ?: break
                runtime.ssh.connect(SshRoute(runtime.state.value.profile, runtime.state.value.jumpProfile), routeCredentials)
                if (runtime.ssh.state.value is ConnectionState.Connected) {
                    runtime.ssh.resize(runtime.columns, runtime.rows)
                    appendLocal(runtime, "\r\n\u001b[33m—— 已自动重连；旧 Shell 上下文已丢失 ——\u001b[0m\r\n")
                    update(runtime) { it.copy(reconnectAttempt = null) }
                    return@launch
                }
                val error = (runtime.ssh.state.value as? ConnectionState.Error)?.message.orEmpty()
                if (error.contains("认证") || error.contains("私钥") || error.contains("主机")) break
            }
            update(runtime) { it.copy(reconnectAttempt = null) }
        }
    }

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
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun appendLocal(runtime: RuntimeSession, text: String) {
        runtime.publish(text.encodeToByteArray())
    }

    private fun update(runtime: RuntimeSession, transform: (ManagedSessionState) -> ManagedSessionState) {
        runtime.mutableState.value = transform(runtime.mutableState.value)
        publishSessions()
    }

    private fun publishSessions() {
        mutableSessions.value = synchronized(runtimes) { runtimes.values.map { it.state.value } }
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
        var userDisconnected = false
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

internal const val MAX_MANAGED_SESSIONS = 8
internal val AUTO_RECONNECT_DELAYS_SECONDS = intArrayOf(2, 5, 10)
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
