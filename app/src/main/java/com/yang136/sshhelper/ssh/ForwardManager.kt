package com.yang136.sshhelper.ssh

import android.content.Context
import com.yang136.sshhelper.data.AppDatabase
import com.yang136.sshhelper.data.HostRepository
import com.yang136.sshhelper.data.PortForwardRuleEntity
import com.yang136.sshhelper.forward.ForwardService
import com.yang136.sshhelper.security.CredentialVault
import com.yang136.sshhelper.security.VaultState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface ForwardManager {
    val rules: StateFlow<List<PortForwardRule>>
    val states: StateFlow<Map<Long, ForwardState>>

    suspend fun save(rule: PortForwardRule): Long
    suspend fun delete(id: Long)
    suspend fun start(id: Long)
    suspend fun stop(id: Long)
    suspend fun stopAll()
    fun ensureSession(hostId: Long)
}

/**
 * Owns forwarding rule lifecycles. Rules are persisted as configuration; runtime state,
 * bound session ids and the desired-running flag live only in memory. A single reconciler
 * watches the session list: transport failures keep rules desired and re-register them on
 * reconnect, while a closed session stops its rules for good.
 */
class DefaultForwardManager(
    private val context: Context,
    private val database: AppDatabase,
    private val hostRepository: HostRepository,
    private val sessions: SessionManager,
    private val vault: CredentialVault,
) : ForwardManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = database.portForwardRuleDao()
    private val mutableStates = MutableStateFlow<Map<Long, ForwardState>>(emptyMap())
    private val handles = ConcurrentHashMap<Long, ForwardHandle>()
    private val desired = ConcurrentHashMap<Long, Boolean>()
    private val bindings = ConcurrentHashMap<Long, SessionId>()
    private val createdSessions = ConcurrentHashMap<SessionId, MutableSet<Long>>()

    override val rules: StateFlow<List<PortForwardRule>> = dao.observeAll()
        .map { list -> list.map(PortForwardRuleEntity::toModel) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val states: StateFlow<Map<Long, ForwardState>> = mutableStates.asStateFlow()

    init {
        scope.launch { sessions.sessions.collect { reconcile(it) } }
    }

    override suspend fun save(rule: PortForwardRule): Long =
        if (rule.id == 0L) dao.insert(rule.toEntity()) else {
            dao.update(rule.toEntity())
            rule.id
        }

    override suspend fun delete(id: Long) {
        stop(id)
        dao.get(id)?.let { dao.delete(it) }
    }

    override suspend fun start(id: Long) {
        desired[id] = true
        rules.value.firstOrNull { it.id == id }?.let { startRule(it) }
    }

    override suspend fun stop(id: Long) {
        desired[id] = false
        bindings.remove(id)
        closeHandle(id)
        mutableStates.value = mutableStates.value + (id to ForwardState.Stopped)
        releaseCreatedSession(id)
        updateForegroundService()
    }

    override suspend fun stopAll() {
        rules.value.forEach { stop(it.id) }
    }

    override fun ensureSession(hostId: Long) {
        val existing = sessions.sessions.value.firstOrNull {
            it.profile.id == hostId && it.connection is ConnectionState.Connected
        }
        if (existing != null) {
            bindingsOfAutoRules(existing.id, hostId)
            return
        }
        if (vault.state.value == VaultState.Locked) return
        scope.launch {
            val profile = hostRepository.getHost(hostId) ?: return@launch
            val created = sessions.create(profile, SessionFeature.PORT_FORWARD) ?: return@launch
            createdSessions.getOrPut(created) { ConcurrentHashMap.newKeySet() }
            bindingsOfAutoRules(created, hostId)
        }
    }

    private fun bindingsOfAutoRules(sessionId: SessionId, hostId: Long) {
        val auto = rules.value.filter { it.hostId == hostId && it.autoStart && desired[it.id] != false }
        auto.forEach { rule ->
            if (bindings[rule.id] == null) {
                bindings[rule.id] = sessionId
                desired[rule.id] = true
            }
        }
        auto.forEach { rule -> scope.launch { startRule(rule) } }
    }

    private suspend fun reconcile(sessionList: List<ManagedSessionState>) {
        val liveSessions = sessionList.associateBy { it.id }
        val currentRules = rules.value

        for (rule in currentRules) {
            val ruleId = rule.id
            val bound = bindings[ruleId]
            if (handles.containsKey(ruleId)) {
                val connection = bound?.let { liveSessions[it]?.connection }
                if (connection !is ConnectionState.Connected) {
                    closeHandle(ruleId)
                    if (connection != null) setState(ruleId, ForwardState.Reconnecting)
                    else handleBoundSessionGone(ruleId)
                }
                continue
            }
            if (desired[ruleId] != true) continue

            if (bound == null) {
                // No session resolved yet — create/reuse one for this host.
                startRule(rule)
                continue
            }
            if (bound !in liveSessions) {
                // The session this rule ran on was closed by the user: stop for good,
                // never silently restart forwarding on a brand-new session.
                handleBoundSessionGone(ruleId)
                continue
            }
            when (val connection = liveSessions[bound]!!.connection) {
                is ConnectionState.Connected -> startRule(rule)
                is ConnectionState.Connecting -> setState(ruleId, ForwardState.Starting)
                is ConnectionState.Disconnected, is ConnectionState.Error -> setState(ruleId, ForwardState.Reconnecting)
                else -> Unit
            }
        }

        // Reclaim sessions we auto-created for forwarding once no desired rule uses them.
        for ((sessionId, ruleIds) in createdSessions.toList()) {
            if (ruleIds.none { desired[it] == true && bindings[it] == sessionId }) {
                createdSessions.remove(sessionId)
                scope.launch { sessions.close(sessionId) }
            }
        }
        updateForegroundService()
    }

    private suspend fun handleBoundSessionGone(ruleId: Long) {
        // The user closed the session: forwarding stops for good, and an auto-created
        // session may be reclaimed by the sweep above.
        desired[ruleId] = false
        bindings.remove(ruleId)
        closeHandle(ruleId)
        mutableStates.value = mutableStates.value + (ruleId to ForwardState.Stopped)
    }

    private suspend fun startRule(rule: PortForwardRule) {
        if (handles.containsKey(rule.id)) return
        setState(rule.id, ForwardState.Starting)
        val sessionId = bindings[rule.id] ?: resolveSession(rule.hostId)?.also { bound ->
            bindings[rule.id] = bound
        } ?: run {
            setState(rule.id, if (vault.state.value == VaultState.Locked) ForwardState.WaitingForUnlock else ForwardState.Failed("无法建立 SSH 会话"))
            return
        }
        val capable = sessions.forwardSession(sessionId)
        if (capable == null) {
            setState(rule.id, ForwardState.Failed("当前会话不支持端口转发"))
            return
        }
        try {
            val handle = capable.registerForward(rule.toRequest())
            handles[rule.id] = handle
            setState(rule.id, ForwardState.Running(handle.actualListenPort))
        } catch (error: Throwable) {
            setState(rule.id, ForwardState.Failed(error.message ?: "启动转发失败"))
        }
    }

    private suspend fun resolveSession(hostId: Long): SessionId? {
        sessions.sessions.value.firstOrNull {
            it.profile.id == hostId && it.connection is ConnectionState.Connected
        }?.let { return it.id }

        if (vault.state.value == VaultState.Locked) return null
        val profile = hostRepository.getHost(hostId) ?: return null
        val created = sessions.create(profile, SessionFeature.PORT_FORWARD) ?: return null
        createdSessions.getOrPut(created) { ConcurrentHashMap.newKeySet() }
        repeat(150) {
            val state = sessions.sessions.value.firstOrNull { it.id == created }
            when (state?.connection) {
                is ConnectionState.Connected -> return created
                is ConnectionState.Error -> return null
                else -> if (state?.needsVaultUnlock == true) return null
            }
            delay(100)
        }
        return null
    }

    private fun closeHandle(ruleId: Long) {
        handles.remove(ruleId)?.close()
    }

    private fun releaseCreatedSession(ruleId: Long) {
        for ((sessionId, ruleIds) in createdSessions.toList()) {
            if (ruleIds.remove(ruleId)) {
                if (ruleIds.isEmpty()) {
                    createdSessions.remove(sessionId)
                    scope.launch { sessions.close(sessionId) }
                }
                return
            }
        }
    }

    private fun setState(ruleId: Long, state: ForwardState) {
        mutableStates.value = mutableStates.value + (ruleId to state)
        updateForegroundService()
    }

    private fun updateForegroundService() {
        val active = mutableStates.value.values.any { it.isActive() }
        if (active) ForwardService.start(context) else ForwardService.stop(context)
    }
}
