package com.yang136.sshhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yang136.sshhelper.AppContainer
import com.yang136.sshhelper.data.ForwardType
import com.yang136.sshhelper.ssh.ForwardManager
import com.yang136.sshhelper.ssh.ForwardState
import com.yang136.sshhelper.ssh.PortForwardRule
import com.yang136.sshhelper.ssh.validateForwardRule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ForwardViewModel(
    private val container: AppContainer,
    val hostId: Long,
) : ViewModel() {
    val rules: StateFlow<List<PortForwardRule>> = container.forwardManager.rules
        .map { list -> list.filter { it.hostId == hostId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val states: StateFlow<Map<Long, ForwardState>> = container.forwardManager.states
    val hostName: StateFlow<String> = container.hostRepository.hosts
        .map { hosts -> hosts.firstOrNull { it.id == hostId }?.name ?: "主机" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "主机")

    init {
        // Opening the forward center activates a PORT_FORWARD session, which triggers auto-start.
        container.forwardManager.ensureSession(hostId)
    }

    fun save(rule: PortForwardRule) = viewModelScope.launch { container.forwardManager.save(rule) }
    fun delete(id: Long) = viewModelScope.launch { container.forwardManager.delete(id) }
    fun start(id: Long) = viewModelScope.launch { container.forwardManager.start(id) }
    fun stop(id: Long) = viewModelScope.launch { container.forwardManager.stop(id) }
    fun stopAll() = viewModelScope.launch { container.forwardManager.stopAll() }

    companion object {
        fun factory(container: AppContainer, hostId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ForwardViewModel(container, hostId) as T
        }
    }
}

internal fun PortForwardRule.routeText(state: ForwardState?): String {
    val listen = when (state) {
        is ForwardState.Running -> state.actualPort
        else -> listenPort
    }
    return when (type) {
        ForwardType.LOCAL -> "本机 $bindAddress:$listen → $targetHost:$targetPort"
        ForwardType.REMOTE -> "服务器 $bindAddress:$listen ← 本机 $targetHost:$targetPort"
        ForwardType.DYNAMIC -> "本机 $bindAddress:$listen SOCKS5 代理"
    }
}

internal fun ForwardState.label(): String = when (this) {
    ForwardState.Stopped -> "已停止"
    ForwardState.Starting -> "启动中"
    is ForwardState.Running -> "运行中 · $actualPort"
    ForwardState.Reconnecting -> "等待重连"
    ForwardState.WaitingForUnlock -> "等待解锁"
    is ForwardState.Failed -> "失败 · $message"
}

internal fun validateRuleForDialog(rule: PortForwardRule): String? = validateForwardRule(rule)
