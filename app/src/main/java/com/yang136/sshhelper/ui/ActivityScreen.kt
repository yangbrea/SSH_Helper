package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.TransferStatus
import com.yang136.sshhelper.ssh.ForwardState
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.SessionFeature
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ui.design.SshEmptyState
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusBadge
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar
import java.io.File

@Composable
fun ActivityScreen(
    hosts: List<HostProfile>,
    sessions: List<ManagedSessionState>,
    onOpenSession: (SessionId) -> Unit,
    onOpenHost: (Long) -> Unit,
    onOpenForwards: (Long) -> Unit,
    onOpenDocuments: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as SshHelperApplication
    val transfers by app.container.transferManager.jobs.collectAsStateWithLifecycle()
    val rules by app.container.forwardManager.rules.collectAsStateWithLifecycle()
    val forwardStates by app.container.forwardManager.states.collectAsStateWithLifecycle()
    val writebacks by app.container.documentAccessManager.writebacks.collectAsStateWithLifecycle()
    val state = buildActivityUiState(sessions, transfers, rules, forwardStates, writebacks.size)
    val hostNames = hosts.associate { it.id to it.name }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier,
        topBar = { SshTopAppBar("活动", subtitle = "会话、传输与隧道的实时状态") },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.attention.isNotEmpty()) {
                item { SshSectionHeader("需要处理", summary = "${state.attention.sumOf { it.count }}") }
                items(state.attention, key = { it.kind }) { attention ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (attention.kind == ActivityAttentionKind.WRITEBACK) onOpenDocuments()
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(attention.title, fontWeight = FontWeight.SemiBold)
                                Text("${attention.count} 项", style = MaterialTheme.typography.bodySmall)
                            }
                            SshStatusBadge("处理", SshStatusTone.ERROR)
                        }
                    }
                }
            }
            if (state.sessions.isNotEmpty()) {
                item { SshSectionHeader("活动会话", summary = "${state.sessions.size}") }
                items(state.sessions, key = { it.id.value }) { session ->
                    ActivityRow(
                        icon = if (SessionFeature.SFTP in session.features) Icons.Default.Folder else Icons.Default.Terminal,
                        title = session.displayName,
                        summary = "${session.profile.name} · ${session.connection.presentation().first}",
                        badge = session.connection.presentation(),
                        onClick = { onOpenSession(session.id) },
                    )
                }
            }
            if (state.activeForwards.isNotEmpty()) {
                item { SshSectionHeader("运行中的转发", summary = "${state.activeForwards.size}") }
                items(state.activeForwards, key = { it.first.id }) { (rule, runtime) ->
                    ActivityRow(Icons.Default.Sync, rule.name, hostNames[rule.hostId] ?: "主机 ${rule.hostId}", runtime.presentation()) { onOpenForwards(rule.hostId) }
                }
            }
            if (state.activeTransfers.isNotEmpty()) {
                item { SshSectionHeader("进行中的传输", summary = "${state.activeTransfers.size}") }
                items(state.activeTransfers, key = { it.id }) { transfer ->
                    ActivityRow(
                        Icons.Default.Sync,
                        File(transfer.source).name.ifBlank { transfer.source },
                        "${hostNames[transfer.hostId] ?: "主机 ${transfer.hostId}"} · ${(transfer.progress * 100).toInt()}%",
                        transfer.status.presentation(),
                    ) { onOpenHost(transfer.hostId) }
                }
            }
            if (state.recentTransfers.isNotEmpty()) {
                item { SshSectionHeader("最近传输", summary = "最近 ${state.recentTransfers.size} 条") }
                items(state.recentTransfers, key = { "recent-${it.id}" }) { transfer ->
                    ActivityRow(Icons.Default.Folder, File(transfer.source).name.ifBlank { transfer.source }, hostNames[transfer.hostId] ?: "主机 ${transfer.hostId}", transfer.status.presentation()) { onOpenHost(transfer.hostId) }
                }
            }
            if (state.attention.isEmpty() && state.sessions.isEmpty() && state.activeForwards.isEmpty() && state.activeTransfers.isEmpty() && state.recentTransfers.isEmpty()) {
                item { SshEmptyState(Icons.Default.Timeline, "当前没有活动", "连接主机、传输文件或启动端口转发后会显示在这里") }
            }
        }
    }
}

@Composable
private fun ActivityRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, badge: Pair<String, SshStatusTone>, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SshStatusBadge(badge.first, badge.second)
        }
    }
}

private fun ForwardState.presentation() = when (this) {
    is ForwardState.Running -> "运行中" to SshStatusTone.CONNECTED
    ForwardState.Starting, ForwardState.Reconnecting -> "连接中" to SshStatusTone.CONNECTING
    ForwardState.WaitingForUnlock -> "等待解锁" to SshStatusTone.WAITING
    is ForwardState.Failed -> "失败" to SshStatusTone.ERROR
    ForwardState.Stopped -> "已停止" to SshStatusTone.OFFLINE
}

private fun TransferStatus.presentation() = when (this) {
    TransferStatus.RUNNING -> "传输中" to SshStatusTone.CONNECTING
    TransferStatus.QUEUED, TransferStatus.PAUSED, TransferStatus.WAITING_NETWORK, TransferStatus.WAITING_UNLOCK -> "等待" to SshStatusTone.WAITING
    TransferStatus.COMPLETED -> "完成" to SshStatusTone.CONNECTED
    TransferStatus.FAILED -> "失败" to SshStatusTone.ERROR
    TransferStatus.CANCELLED -> "已取消" to SshStatusTone.OFFLINE
}
