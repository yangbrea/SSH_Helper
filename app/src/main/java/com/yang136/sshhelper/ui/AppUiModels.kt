package com.yang136.sshhelper.ui

import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.TransferStatus
import com.yang136.sshhelper.sftp.TransferJob
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.ForwardState
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.PortForwardRule
import com.yang136.sshhelper.ui.design.SshStatusTone

enum class AppDestination(val id: String, val label: String) {
    HOSTS("hosts", "主机"),
    ACTIVITY("activity", "活动"),
    TOOLS("tools", "工具"),
    SETTINGS("settings", "设置"),
    ;

    companion object {
        fun fromId(id: String?): AppDestination = entries.firstOrNull { it.id == id } ?: HOSTS
    }
}

data class HostWorkspaceUiState(
    val host: HostProfile,
    val sessions: List<ManagedSessionState>,
    val activeTransfers: Int,
    val forwardingRules: Int,
    val runningForwards: Int,
    val documentAuthorized: Boolean,
)

internal fun hostSessions(hostId: Long, sessions: List<ManagedSessionState>): List<ManagedSessionState> =
    sessions.filter { it.profile.id == hostId }.sortedWith(
        compareByDescending<ManagedSessionState> { connectionPriority(it.connection) }
            .thenBy { it.displayName.lowercase() }
            .thenBy { it.id.value },
    )

internal fun buildHostWorkspaceUiState(
    host: HostProfile,
    sessions: List<ManagedSessionState>,
    transfers: List<TransferJob>,
    rules: List<PortForwardRule>,
    forwardStates: Map<Long, ForwardState>,
    authorizedHostIds: Set<Long>,
): HostWorkspaceUiState {
    val hostRules = rules.filter { it.hostId == host.id }
    return HostWorkspaceUiState(
        host = host,
        sessions = hostSessions(host.id, sessions),
        activeTransfers = transfers.count { it.hostId == host.id && it.status.isActiveTransfer() },
        forwardingRules = hostRules.size,
        runningForwards = hostRules.count { forwardStates[it.id] is ForwardState.Running },
        documentAuthorized = host.id in authorizedHostIds,
    )
}

enum class ActivityAttentionKind { WRITEBACK, TRANSFER, UNLOCK, TUNNEL }

data class ActivityAttention(
    val kind: ActivityAttentionKind,
    val title: String,
    val count: Int,
    val priority: Int,
)

data class ActivityUiState(
    val attention: List<ActivityAttention>,
    val sessions: List<ManagedSessionState>,
    val activeTransfers: List<TransferJob>,
    val recentTransfers: List<TransferJob>,
    val activeForwards: List<Pair<PortForwardRule, ForwardState>>,
)

internal fun buildActivityUiState(
    sessions: List<ManagedSessionState>,
    transfers: List<TransferJob>,
    rules: List<PortForwardRule>,
    forwardStates: Map<Long, ForwardState>,
    failedWritebacks: Int,
): ActivityUiState {
    val failedTransfers = transfers.count { it.status == TransferStatus.FAILED }
    val waitingUnlock = sessions.count { it.needsVaultUnlock }
    val failedTunnels = forwardStates.values.count { it is ForwardState.Failed }
    val attention = buildList {
        if (failedWritebacks > 0) add(ActivityAttention(ActivityAttentionKind.WRITEBACK, "文件写回待处理", failedWritebacks, 4))
        if (failedTransfers > 0) add(ActivityAttention(ActivityAttentionKind.TRANSFER, "传输失败", failedTransfers, 3))
        if (waitingUnlock > 0) add(ActivityAttention(ActivityAttentionKind.UNLOCK, "等待解锁", waitingUnlock, 2))
        if (failedTunnels > 0) add(ActivityAttention(ActivityAttentionKind.TUNNEL, "隧道异常", failedTunnels, 3))
    }.sortedWith(compareByDescending<ActivityAttention> { it.priority }.thenBy { it.kind.ordinal })
    val active = transfers.filter { it.status.isActiveTransfer() }.sortedByDescending(TransferJob::updatedAt)
    val recent = transfers.filter { it.status in setOf(TransferStatus.COMPLETED, TransferStatus.FAILED) }
        .sortedByDescending(TransferJob::updatedAt).take(5)
    val forwards = rules.mapNotNull { rule ->
        forwardStates[rule.id]?.takeUnless { it is ForwardState.Stopped }?.let { rule to it }
    }.sortedBy { it.first.name.lowercase() }
    return ActivityUiState(attention, hostSessionsAcrossHosts(sessions), active, recent, forwards)
}

private fun hostSessionsAcrossHosts(sessions: List<ManagedSessionState>) = sessions.sortedWith(
    compareByDescending<ManagedSessionState> { connectionPriority(it.connection) }
        .thenBy { it.profile.name.lowercase() }
        .thenBy { it.displayName.lowercase() },
)

private fun connectionPriority(state: ConnectionState): Int = when (state) {
    is ConnectionState.Connected -> 4
    ConnectionState.Connecting -> 3
    is ConnectionState.Error -> 2
    is ConnectionState.Disconnected -> 1
    ConnectionState.Idle -> 0
}

internal fun ConnectionState.presentation(): Pair<String, SshStatusTone> = when (this) {
    is ConnectionState.Connected -> "在线" to SshStatusTone.CONNECTED
    ConnectionState.Connecting -> "连接中" to SshStatusTone.CONNECTING
    is ConnectionState.Error -> "失败" to SshStatusTone.ERROR
    is ConnectionState.Disconnected -> "离线" to SshStatusTone.OFFLINE
    ConnectionState.Idle -> "等待" to SshStatusTone.WAITING
}

internal fun TransferStatus.isActiveTransfer(): Boolean = this in setOf(
    TransferStatus.QUEUED,
    TransferStatus.RUNNING,
    TransferStatus.PAUSED,
    TransferStatus.WAITING_NETWORK,
    TransferStatus.WAITING_UNLOCK,
)
