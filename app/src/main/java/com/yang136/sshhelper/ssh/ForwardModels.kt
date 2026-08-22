package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.ForwardType
import com.yang136.sshhelper.data.PortForwardRuleEntity

/** Persisted forwarding rule configuration. Runtime ports and states are never stored. */
data class PortForwardRule(
    val id: Long = 0,
    val hostId: Long,
    val name: String,
    val type: ForwardType,
    val bindAddress: String,
    val listenPort: Int,
    val targetHost: String? = null,
    val targetPort: Int? = null,
    val autoStart: Boolean = false,
)

fun PortForwardRuleEntity.toModel() = PortForwardRule(
    id, hostId, name, type, bindAddress, listenPort, targetHost, targetPort, autoStart,
)

fun PortForwardRule.toEntity() = PortForwardRuleEntity(
    id, hostId, name, type, bindAddress, listenPort, targetHost, targetPort, autoStart,
)

fun PortForwardRule.toRequest() = ForwardRequest(type, bindAddress, listenPort, targetHost, targetPort)

/** Runtime state of one rule; kept in memory only. */
sealed interface ForwardState {
    data object Stopped : ForwardState
    data object Starting : ForwardState
    data class Running(val actualPort: Int) : ForwardState
    data object Reconnecting : ForwardState
    data object WaitingForUnlock : ForwardState
    data class Failed(val message: String) : ForwardState
}

fun ForwardState.isActive(): Boolean =
    this is ForwardState.Running || this is ForwardState.Starting || this is ForwardState.Reconnecting

fun ForwardType.displayName(): String = when (this) {
    ForwardType.LOCAL -> "本地 -L"
    ForwardType.REMOTE -> "远程 -R"
    ForwardType.DYNAMIC -> "动态 -D"
}

fun validateForwardRule(rule: PortForwardRule): String? {
    if (rule.name.isBlank()) return "请输入规则名称"
    if (rule.name.length > 40) return "规则名称不能超过 40 个字符"
    when (rule.type) {
        ForwardType.LOCAL, ForwardType.DYNAMIC -> {
            if (rule.listenPort != 0 && rule.listenPort !in 1024..65535) {
                return "监听端口必须在 1024–65535 之间（0 表示自动分配）"
            }
            if (rule.type == ForwardType.DYNAMIC && rule.bindAddress != "127.0.0.1") {
                return "动态代理只允许监听回环地址，不提供无认证的局域网公开代理"
            }
        }
        ForwardType.REMOTE -> {
            if (rule.listenPort !in 1..65535) return "服务器监听端口必须在 1–65535 之间"
        }
    }
    if (rule.type != ForwardType.DYNAMIC) {
        if (rule.targetHost.isNullOrBlank()) return "请输入目标主机"
        if (rule.targetPort == null || rule.targetPort !in 1..65535) return "目标端口必须在 1–65535 之间"
    }
    return null
}

/** A single forwarding request handed to the SSH transport. */
data class ForwardRequest(
    val type: ForwardType,
    val bindAddress: String,
    val listenPort: Int,
    val targetHost: String?,
    val targetPort: Int?,
)

/** Active registration on a live SSH transport; [close] deregisters it. */
interface ForwardHandle {
    val actualListenPort: Int
    fun close()
}

/** Sessions that can host port forwardings on their final target transport. */
interface PortForwardCapableSession {
    suspend fun registerForward(request: ForwardRequest): ForwardHandle
}
