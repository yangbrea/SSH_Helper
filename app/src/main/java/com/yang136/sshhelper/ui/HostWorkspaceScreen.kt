package com.yang136.sshhelper.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.SessionFeature
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ui.design.SshActionTile
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusBadge
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar

@Composable
fun HostWorkspaceScreen(
    host: HostProfile,
    sessions: List<ManagedSessionState>,
    onTerminal: (HostProfile) -> Boolean,
    onFiles: (HostProfile) -> Boolean,
    onForwards: (Long) -> Unit,
    onEdit: (HostProfile) -> Unit,
    onOpenSession: (SessionId) -> Unit,
    onCloseSession: (SessionId) -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as SshHelperApplication
    val transfers by app.container.transferManager.jobs.collectAsStateWithLifecycle()
    val rules by app.container.forwardManager.rules.collectAsStateWithLifecycle()
    val forwardStates by app.container.forwardManager.states.collectAsStateWithLifecycle()
    val roots by app.container.documentAccessManager.roots.collectAsStateWithLifecycle()
    val state = buildHostWorkspaceUiState(host, sessions, transfers, rules, forwardStates, roots.map { it.hostId }.toSet())
    var sessionLimitReached by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        topBar = {
            SshTopAppBar(
                title = host.name,
                subtitle = "${host.username}@${host.hostname}:${host.port}",
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { onEdit(host) }) { Icon(Icons.Default.Edit, "编辑主机") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f))) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("连接工作区", style = MaterialTheme.typography.titleLarge)
                                Text(routeSummary(host), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val status = state.sessions.firstOrNull()?.connection?.presentation() ?: ("离线" to SshStatusTone.OFFLINE)
                            SshStatusBadge(status.first, status.second)
                        }
                        Button(onClick = { if (!onTerminal(host)) sessionLimitReached = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Terminal, null)
                            Text("打开终端", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SshActionTile(Icons.Default.Folder, "文件", { if (!onFiles(host)) sessionLimitReached = true }, Modifier.weight(1f))
                    SshActionTile(Icons.Default.Public, "端口转发", { onForwards(host.id) }, Modifier.weight(1f))
                }
            }
            item { SshSectionHeader("系统集成") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryRow("系统文件访问", if (state.documentAuthorized) "已授权" else "未授权", if (state.documentAuthorized) SshStatusTone.CONNECTED else SshStatusTone.OFFLINE)
                        SummaryRow("传输", if (state.activeTransfers > 0) "${state.activeTransfers} 个进行中" else "无进行中任务", if (state.activeTransfers > 0) SshStatusTone.CONNECTING else SshStatusTone.OFFLINE)
                        SummaryRow("隧道", "${state.runningForwards}/${state.forwardingRules} 运行中", if (state.runningForwards > 0) SshStatusTone.CONNECTED else SshStatusTone.OFFLINE)
                    }
                }
            }
            item { SshSectionHeader("已有会话", summary = "${state.sessions.size}") }
            if (state.sessions.isEmpty()) item { Text("暂无会话", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else items(state.sessions, key = { it.id.value }) { session ->
                Card(onClick = { onOpenSession(session.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (SessionFeature.SFTP in session.features) Icons.Default.Folder else Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(session.displayName, fontWeight = FontWeight.Medium)
                            Text(session.features.joinToString(" · ") { it.label() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val status = session.connection.presentation()
                        SshStatusBadge(status.first, status.second)
                        IconButton(onClick = { onCloseSession(session.id) }) { Icon(Icons.Default.Close, "关闭会话") }
                    }
                }
            }
        }
    }
    if (sessionLimitReached) AlertDialog(
        onDismissRequest = { sessionLimitReached = false }, title = { Text("已达到会话上限") },
        text = { Text("最多可同时保留 8 个会话，请先关闭一个会话。") },
        confirmButton = { TextButton(onClick = { sessionLimitReached = false }) { Text("知道了") } },
    )
}

@Composable
private fun SummaryRow(label: String, value: String, tone: SshStatusTone) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        SshStatusBadge(value, tone)
    }
}

private fun routeSummary(host: HostProfile): String = buildList {
    add(if (host.jumpHostId == null) "直连" else "经跳板机")
    host.proxyType?.let { add("${it.name} 代理") }
}.joinToString(" · ")

private fun SessionFeature.label() = when (this) {
    SessionFeature.SHELL -> "终端"
    SessionFeature.SFTP -> "文件"
    SessionFeature.PORT_FORWARD -> "转发"
}
