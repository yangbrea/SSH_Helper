package com.yang136.sshhelper.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yang136.sshhelper.data.AppDatabase
import com.yang136.sshhelper.data.HostRepository
import com.yang136.sshhelper.data.PortForwardRuleEntity
import com.yang136.sshhelper.forward.ForwardService
import com.yang136.sshhelper.security.CredentialVault
import com.yang136.sshhelper.security.VaultState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 进程被系统回收后，用于恢复"用户希望转发继续运行"意图的持久化存储。 */
private val Context.forwardRuntimeDataStore by preferencesDataStore(name = "forward_runtime")

/** 持久化格式：规则 ID 集合 ↔ 字符串集合（DataStore stringSet）。 */
internal fun encodeDesiredRuleIds(ids: Set<Long>): Set<String> = ids.map { it.toString() }.toSet()

internal fun decodeDesiredRuleIds(encoded: Set<String>): Set<Long> = encoded.mapNotNull { it.toLongOrNull() }.toSet()

interface ForwardManager {
    val rules: StateFlow<List<PortForwardRule>>
    val states: StateFlow<Map<Long, ForwardState>>

    suspend fun save(rule: PortForwardRule): Long
    suspend fun delete(id: Long)
    suspend fun start(id: Long)
    suspend fun stop(id: Long)
    suspend fun stopAll()
    fun ensureSession(hostId: Long)

    /** 当前有活动转发（Running/Starting/Reconnecting）所绑定的会话 ID 集合。 */
    fun activeForwardSessionIds(): Set<SessionId>
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
    /** 创建中租约：会话已创建但规则绑定尚未完成的会话，回收扫描必须跳过。 */
    private val creatingSessions = ConcurrentHashMap.newKeySet<SessionId>()
    /**
     * 首次连接失败阻断：value 为 "auth"（认证/私钥/主机密钥错误，仅用户显式重试或
     * 保险库解锁可解除）或 "transient"（超时/网络类，网络恢复事件也可解除）。
     * 防止 reconcile 反复创建失败会话直至占满 MAX_MANAGED_SESSIONS。
     */
    private val failureGate = ForwardFailureGate()
    /** 逐规则互斥：startRule / stop / reconcile / restore 对同一规则的操作必须原子。 */
    private val ruleMutexes = ConcurrentHashMap<Long, Mutex>()
    /** 每主机创建互斥：防止恢复与 reconcile 为同一主机并发创建多个 SSH 会话。 */
    private val hostCreateMutexes = ConcurrentHashMap<Long, Mutex>()
    /** 前台服务启动去抖：只在"无活跃 ↔ 有活跃"边界调用 start/stop。 */
    private val serviceWanted = AtomicBoolean(false)

    private suspend fun <T> withRuleLock(ruleId: Long, block: suspend () -> T): T =
        ruleMutexes.getOrPut(ruleId) { Mutex() }.withLock { block() }

    private suspend fun <T> withHostCreateLock(hostId: Long, block: suspend () -> T): T =
        hostCreateMutexes.getOrPut(hostId) { Mutex() }.withLock { block() }

    private suspend fun readPersistedRuleIds(): Set<Long> =
        decodeDesiredRuleIds(context.forwardRuntimeDataStore.data.first()[DESIRED_RULE_IDS] ?: emptySet())

    /**
     * 复用该主机上已在途/已连接的会话（Connected / Connecting / Idle），
     * 避免为同一主机创建第二个转发会话。
     */
    private fun inFlightSessionFor(hostId: Long): ManagedSessionState? =
        sessions.sessions.value.firstOrNull { state ->
            state.profile.id == hostId &&
                (state.connection is ConnectionState.Connected ||
                    state.connection is ConnectionState.Connecting ||
                    state.connection == ConnectionState.Idle)
        }

    override val rules: StateFlow<List<PortForwardRule>> = dao.observeAll()
        .map { list -> list.map(PortForwardRuleEntity::toModel) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val states: StateFlow<Map<Long, ForwardState>> = mutableStates.asStateFlow()

    init {
        scope.launch { sessions.sessions.collect { reconcile(it) } }
        // 进程被系统回收后重建：按持久化的规则 ID 集合恢复用户意图。
        // 凭据不足（保险库锁定）时规则进入"等待解锁"，不假装恢复。
        scope.launch { restoreDesiredRules() }
        // 保险库锁定期间 ensureSession 会直接返回；解锁后重试恢复。
        scope.launch {
            var previous: VaultState? = null
            vault.state.collect { state ->
                val unlocked = state is VaultState.Unlocked || state == VaultState.Disabled
                if (unlocked && previous == VaultState.Locked) restoreDesiredRules()
                previous = state
            }
        }
        // 网络恢复事件：解除"首次连接失败"的 transient 阻断并主动重试。
        // （认证类失败仅由用户显式重试或解锁解除，避免触发服务器账号锁定。）
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = unblockTransientForwardFailures()
            })
        }
    }

    /**
     * 进程重建后的恢复入口：读取持久化的 desired 规则 ID 集合，
     * 等待 Room 首次真实查询（StateFlow 初始空列表不代表数据库为空），
     * 然后**逐条**在规则锁内启动——不经过 autoStart 扫描，因此：
     * - 手动启动的非 autoStart 规则可恢复；
     * - 已停止（未持久化）的 autoStart 规则不会复活。
     */
    private suspend fun restoreDesiredRules() {
        val persisted = readPersistedRuleIds()
        if (persisted.isEmpty()) {
            // 无恢复意图：进程重建后系统可能经 START_STICKY 重启了前台服务，
            // 这里无条件停掉，避免"0 条转发"却长期持有 WakeLock。
            serviceWanted.set(false)
            runCatching { ForwardService.stop(context) }
            return
        }
        val allRules = database.portForwardRuleDao().observeAll().first().map(PortForwardRuleEntity::toModel)
        val valid = allRules.filter { it.id in persisted }
        if (valid.size != persisted.size) {
            // 清理已删除规则的持久化残留。
            runCatching {
                context.forwardRuntimeDataStore.edit {
                    it[DESIRED_RULE_IDS] = encodeDesiredRuleIds(valid.map { rule -> rule.id }.toSet())
                }
            }
        }
        if (valid.isEmpty()) {
            // 持久化集合全部对应已删除规则：没有可恢复的转发，停掉误启动的服务，
            // 避免"0 条转发"却长期持有 WakeLock。
            serviceWanted.set(false)
            runCatching { ForwardService.stop(context) }
            return
        }
        ensureForegroundServiceStarted()
        valid.forEach { rule ->
            withRuleLock(rule.id) {
                // 锁内复核持久化意图：读取快照之后用户可能已点击停止。
                if (rule.id !in readPersistedRuleIds()) return@withRuleLock
                failureGate.allowExplicitRetry(rule.id) // 解锁/恢复是显式重试事件
                desired[rule.id] = true
                startRuleLocked(rule)
            }
        }
    }

    private suspend fun persistDesired(ruleId: Long, desired: Boolean) {
        runCatching {
            context.forwardRuntimeDataStore.edit { prefs ->
                val current = decodeDesiredRuleIds(prefs[DESIRED_RULE_IDS] ?: emptySet())
                prefs[DESIRED_RULE_IDS] = encodeDesiredRuleIds(
                    if (desired) current + ruleId else current - ruleId,
                )
            }
        }
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

    override suspend fun start(id: Long) = withRuleLock(id) {
        failureGate.allowExplicitRetry(id) // 用户显式重试：解除失败阻断
        desired[id] = true
        persistDesired(id, true)
        // 用 DAO 加载规则，避免冷启动时 rules.value 仍是初始空列表。
        val rule = dao.get(id)?.toModel() ?: return@withRuleLock
        startRuleLocked(rule)
    }

    override suspend fun stop(id: Long) = withRuleLock(id) {
        desired[id] = false
        failureGate.allowExplicitRetry(id)
        persistDesired(id, false)
        bindings.remove(id)
        closeHandle(id)
        mutableStates.update { it + (id to ForwardState.Stopped) }
        releaseCreatedSession(id)
        updateForegroundService()
    }

    override suspend fun stopAll() {
        // 先原子清空持久化意图：即使后续停止失败，恢复协程也不会再把隧道拉起。
        runCatching { context.forwardRuntimeDataStore.edit { it[DESIRED_RULE_IDS] = emptySet() } }
        // 冷进程下 rules.value 可能是 stateIn 的初始空列表：等待 DAO 首次真实加载，
        // 再对全部现存规则执行停止。
        val all = database.portForwardRuleDao().observeAll().first().map(PortForwardRuleEntity::toModel)
        all.forEach { stop(it.id) }
        // 处理内存中可能存在但已被删除的残留 desired 规则。
        desired.keys.toList().forEach { id -> if (all.none { it.id == id }) stop(id) }
    }

    override fun ensureSession(hostId: Long) {
        scope.launch {
            val outcome = withHostCreateLock(hostId) {
                val existing = inFlightSessionFor(hostId)
                when {
                    existing != null -> existing.id to false // 复用已有会话
                    vault.state.value == VaultState.Locked -> null
                    else -> hostRepository.getHost(hostId)?.let { profile ->
                        sessions.create(profile, SessionFeature.PORT_FORWARD)?.let { it to true }
                    }
                }
            } ?: return@launch
            val (sessionId, owned) = outcome
            // 绑定在主机锁外执行，避免 主机锁→规则锁 与 reconcile 的 规则锁→主机锁 死锁。
            if (owned) creatingSessions.add(sessionId)
            try {
                if (owned) createdSessions.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }
                bindingsOfAutoRules(sessionId, hostId)
            } finally {
                if (owned) creatingSessions.remove(sessionId)
            }
        }
    }

    /**
     * 打开转发中心时的 autoStart 扫描：同步（挂起）登记绑定并启动，规则锁内完成
     * desired/bindings 变更，避免与 stop() 竞态；调用方必须保证会话已有
     * createdSessions 记录（自建会话）或为已有会话（复用，`?.add` 为空操作）。
     */
    private suspend fun bindingsOfAutoRules(sessionId: SessionId, hostId: Long) {
        val auto = rules.value.filter { it.hostId == hostId && it.autoStart && desired[it.id] != false }
        auto.forEach { rule ->
            withRuleLock(rule.id) {
                if (bindings[rule.id] == null && desired[rule.id] != false) {
                    bindings[rule.id] = sessionId
                    desired[rule.id] = true
                    // 记录到自动创建会话的占用集合，防止回收扫描误关（P0）。
                    createdSessions[sessionId]?.add(rule.id)
                    persistDesired(rule.id, true)
                    startRuleLocked(rule)
                }
            }
        }
    }

    private suspend fun reconcile(sessionList: List<ManagedSessionState>) {
        val liveSessions = sessionList.associateBy { it.id }
        val currentRules = rules.value

        for (rule in currentRules) {
            val ruleId = rule.id
            withRuleLock(ruleId) {
                val bound = bindings[ruleId]
                if (handles.containsKey(ruleId)) {
                    val connection = bound?.let { liveSessions[it]?.connection }
                    if (connection !is ConnectionState.Connected) {
                        closeHandle(ruleId)
                        if (connection != null) setState(ruleId, ForwardState.Reconnecting)
                        else handleBoundSessionGone(ruleId)
                    }
                    return@withRuleLock
                }
                if (desired[ruleId] != true) return@withRuleLock

                if (bound == null) {
                    // No session resolved yet — create/reuse one for this host.
                    // 必须调用不带锁的变体：本函数已持有规则 Mutex，而 Kotlin Mutex
                    // 不可重入，调用 startRule() 会永久挂死（P0）。
                    startRuleLocked(rule)
                    return@withRuleLock
                }
                if (bound !in liveSessions) {
                    // The session this rule ran on was closed by the user: stop for good,
                    // never silently restart forwarding on a brand-new session.
                    handleBoundSessionGone(ruleId)
                    return@withRuleLock
                }
                when (val connection = liveSessions[bound]!!.connection) {
                    is ConnectionState.Connected -> startRuleLocked(rule)
                    is ConnectionState.Connecting -> setState(ruleId, ForwardState.Starting)
                    is ConnectionState.Disconnected, is ConnectionState.Error -> setState(ruleId, ForwardState.Reconnecting)
                    else -> Unit
                }
            }
        }

        // Reclaim sessions we auto-created for forwarding once no desired rule uses them.
        for ((sessionId, ruleIds) in createdSessions.toList()) {
            // 创建中租约：绑定尚未完成的会话绝不能回收。
            if (sessionId in creatingSessions) continue
            if (ruleIds.none { id ->
                    (desired[id] == true && bindings[id] == sessionId) ||
                        // 规则已 desired 但尚未绑定（创建→绑定的瞬时窗口）：保守保留。
                        (desired[id] == true && bindings[id] == null)
                }
            ) {
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
        failureGate.allowExplicitRetry(ruleId)
        persistDesired(ruleId, false)
        bindings.remove(ruleId)
        closeHandle(ruleId)
        mutableStates.update { it + (ruleId to ForwardState.Stopped) }
    }

    private suspend fun startRule(rule: PortForwardRule) = withRuleLock(rule.id) {
        startRuleLocked(rule)
    }

    /** 调用方必须已持有 [rule.id] 的规则锁（[startRule] 或 [reconcile]）。 */
    private suspend fun startRuleLocked(rule: PortForwardRule) {
        // 锁内重新校验：stop() 可能已把 desired 置 false 或已 closeHandle，
        // 旧的启动协程不得把句柄写回，否则会出现"已停止却仍在转发"。
        if (handles.containsKey(rule.id)) return
        if (desired[rule.id] != true) return
        // 首次连接失败阻断：等待用户重试 / 保险库解锁 / 网络恢复事件，不再自动重建。
        if (failureGate.isBlocked(rule.id)) return
        setState(rule.id, ForwardState.Starting)
        val sessionId = bindings[rule.id] ?: resolveSession(rule.hostId, rule.id)?.also { bound ->
            bindings[rule.id] = bound
        } ?: run {
            setState(rule.id, if (vault.state.value == VaultState.Locked) ForwardState.WaitingForUnlock else ForwardState.Failed("无法建立 SSH 会话"))
            return
        }
        // 复用在途（Idle/Connecting）会话时先不注册：等 reconcile 在会话就绪后再启动，
        // 避免在未连接会话上立即 registerForward 报"连接不可用"。
        if (sessions.sessions.value.firstOrNull { it.id == sessionId }?.connection !is ConnectionState.Connected) {
            setState(rule.id, ForwardState.Starting)
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
            if (error is kotlinx.coroutines.CancellationException) throw error
            setState(rule.id, ForwardState.Failed(error.message ?: "启动转发失败"))
        }
    }

    private suspend fun resolveSession(hostId: Long, ruleId: Long): SessionId? = withHostCreateLock(hostId) {
        // 复用该主机已在途/已连接的会话（含终端会话），避免双会话；
        // 同时登记占用，防止回收扫描误关（P0）。
        inFlightSessionFor(hostId)?.let { existing ->
            bindings[ruleId] = existing.id
            createdSessions[existing.id]?.add(ruleId)
            return@withHostCreateLock existing.id
        }

        if (vault.state.value == VaultState.Locked) return@withHostCreateLock null
        val profile = hostRepository.getHost(hostId) ?: return@withHostCreateLock null
        val created = sessions.create(profile, SessionFeature.PORT_FORWARD) ?: return@withHostCreateLock null
        // 创建中租约 + 立即登记占用规则：回收扫描在等待连接期间看到空集合会误关会话（P0）。
        creatingSessions.add(created)
        try {
            createdSessions.getOrPut(created) { ConcurrentHashMap.newKeySet() }.add(ruleId)
        } finally {
            creatingSessions.remove(created)
        }
        repeat(150) {
            val state = sessions.sessions.value.firstOrNull { it.id == created }
            when (state?.connection) {
                is ConnectionState.Connected -> return@withHostCreateLock created
                is ConnectionState.Error -> {
                    // 首次连接失败：回收会话槽位并阻断自动重试，避免 reconcile
                    // 反复创建失败会话直至占满 MAX_MANAGED_SESSIONS。
                    val message = state.connection.message
                    discardFailedSession(created, ruleId, classifyForwardFailure(message))
                    setState(ruleId, ForwardState.Failed("首次连接失败：${message.take(60)}"))
                    return@withHostCreateLock null
                }
                else -> if (state?.needsVaultUnlock == true) {
                    // 等待解锁：保留会话与规则（解锁后恢复协程会继续），不阻断。
                    return@withHostCreateLock null
                }
            }
            delay(100)
        }
        // 轮询超时：会话可能停滞在 Connecting；回收并阻断（transient，网络事件可解除）。
        discardFailedSession(created, ruleId, ForwardFailureKind.TRANSIENT)
        setState(ruleId, ForwardState.Failed("连接超时，已停止自动重试"))
        null
    }

    /** 丢弃失败的创建会话：解除占用并关闭会话回收槽位，按类别阻断自动重试。 */
    private fun discardFailedSession(sessionId: SessionId, ruleId: Long, kind: ForwardFailureKind) {
        createdSessions.remove(sessionId)
        bindings.entries.filter { it.value == sessionId }.forEach { (id, _) ->
            bindings.remove(id)
            if (id != ruleId) failureGate.block(id, ForwardFailureKind.TRANSIENT)
        }
        scope.launch { sessions.close(sessionId) }
        failureGate.block(ruleId, kind)
    }

    /** 网络恢复事件：解除 transient 类失败阻断并主动重试（auth 类需用户显式操作）。 */
    private fun unblockTransientForwardFailures() {
        failureGate.allowNetworkRetry().forEach { id ->
            scope.launch {
                val rule = dao.get(id)?.toModel() ?: return@launch
                startRule(rule)
            }
        }
    }

    private fun closeHandle(ruleId: Long) {
        val handle = handles.remove(ruleId) ?: return
        // 句柄注销可能抛 JSchException（例如 JSch 已随会话断开自行卸载转发）。
        // 该异常绝不允许逃出协程作用域，否则未捕获异常会终止整个进程。
        runCatching { handle.close() }
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

    private suspend fun setState(ruleId: Long, state: ForwardState) {
        mutableStates.update { it + (ruleId to state) }
        updateForegroundService()
    }

    /**
     * 在"无活跃 ↔ 有活跃"边界启停前台服务，避免每次状态变化都重复调用
     * startForegroundService()（Android 12+ 后台启动限制会抛异常，此处统一兜底）。
     */
    private fun updateForegroundService() {
        val active = mutableStates.value.values.any { it.isActive() }
        if (active) ensureForegroundServiceStarted() else stopForegroundServiceIfWanted()
    }

    /** 当前有活动转发（Running/Starting/Reconnecting）所绑定的会话 ID 集合。 */
    override fun activeForwardSessionIds(): Set<SessionId> =
        mutableStates.value.entries
            .filter { (ruleId, state) -> state.isActive() }
            .mapNotNull { (ruleId, _) -> bindings[ruleId] }
            .toSet()

    private fun ensureForegroundServiceStarted() {
        if (serviceWanted.compareAndSet(false, true)) {
            // 启动失败（如后台 FGS 限制）：清标志，下次状态变化会重试。
            if (!runCatching { ForwardService.start(context) }.isSuccess) {
                serviceWanted.set(false)
            }
        }
    }

    private fun stopForegroundServiceIfWanted() {
        if (serviceWanted.compareAndSet(true, false)) {
            runCatching { ForwardService.stop(context) }
        }
    }

    private companion object {
        val DESIRED_RULE_IDS = stringSetPreferencesKey("desired_rule_ids")
    }
}
