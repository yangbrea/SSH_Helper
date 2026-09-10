package com.yang136.sshhelper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.MultiplexerSessionState
import com.yang136.sshhelper.ssh.SessionFeature
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.SessionKind
import com.yang136.sshhelper.ui.design.SshActionTile
import com.yang136.sshhelper.ui.design.SshCenteredList
import com.yang136.sshhelper.ui.design.SshEmptyState
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusBadge
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar

@Composable
fun HostWorkspaceScreen(
    host: HostProfile,
    sessions: List<ManagedSessionState>,
    onNewSession: (HostProfile, SessionKind) -> SessionId?,
    onOpenTerminal: (SessionId) -> Unit,
    onOpenFiles: (SessionId) -> Unit,
    onReconnect: (SessionId) -> Unit,
    onRenameSession: (SessionId, String) -> Unit,
    onForwards: (Long) -> Unit,
    onDiagnostics: (Long) -> Unit,
    onEdit: (HostProfile) -> Unit,
    onCloseSession: (SessionId) -> Unit,
    onBack: () -> Unit,
    createSession: Boolean = false,
) {
    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        contentColor = imageAwareContentColor(),
        topBar = {
            SshTopAppBar(
                title = host.name,
                subtitle = "${host.username}@${host.hostname}:${host.port}",
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { onEdit(host) }) { Icon(Icons.Default.Edit, "编辑主机") } },
            )
        },
    ) { padding ->
        HostWorkspacePane(
            host = host,
            sessions = sessions,
            onNewSession = onNewSession,
            onOpenTerminal = onOpenTerminal,
            onOpenFiles = onOpenFiles,
            onReconnect = onReconnect,
            onRenameSession = onRenameSession,
            onForwards = onForwards,
            onDiagnostics = onDiagnostics,
            onCloseSession = onCloseSession,
            createSession = createSession,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * 主机工作区（无 Scaffold 包装）：独立路由与横屏 master-detail 右栏共用。
 * 以会话为主体：三个主动作 + 可展开会话列表。
 */
@Composable
internal fun HostWorkspacePane(
    host: HostProfile,
    sessions: List<ManagedSessionState>,
    onNewSession: (HostProfile, SessionKind) -> SessionId?,
    onOpenTerminal: (SessionId) -> Unit,
    onOpenFiles: (SessionId) -> Unit,
    onReconnect: (SessionId) -> Unit,
    onRenameSession: (SessionId, String) -> Unit,
    onForwards: (Long) -> Unit,
    onDiagnostics: (Long) -> Unit,
    onCloseSession: (SessionId) -> Unit,
    modifier: Modifier = Modifier,
    createSession: Boolean = false,
) {
    val app = LocalContext.current.applicationContext as SshHelperApplication
    val transfers by app.container.transferManager.jobs.collectAsStateWithLifecycle()
    val rules by app.container.forwardManager.rules.collectAsStateWithLifecycle()
    val forwardStates by app.container.forwardManager.states.collectAsStateWithLifecycle()
    val roots by app.container.documentAccessManager.roots.collectAsStateWithLifecycle()
    val state = buildHostWorkspaceUiState(host, sessions, transfers, rules, forwardStates, roots.map { it.hostId }.toSet())

    // 同一时间只展开一个会话；用 String 保存便于跨页面返回恢复。
    var expandedSessionId by rememberSaveable(host.id) { mutableStateOf<String?>(null) }
    var autoCreateConsumed by rememberSaveable(host.id) { mutableStateOf(false) }
    var sessionLimitReached by remember { mutableStateOf(false) }
    var renameSession by remember { mutableStateOf<ManagedSessionState?>(null) }
    var closeSession by remember { mutableStateOf<ManagedSessionState?>(null) }

    LaunchedEffect(state.sessions.map { it.id.value }) {
        if (expandedSessionId != null && state.sessions.none { it.id.value == expandedSessionId }) {
            expandedSessionId = null
        }
    }

    fun toggleExpand(sessionId: SessionId) {
        expandedSessionId = if (expandedSessionId == sessionId.value) null else sessionId.value
    }

    var showNewSessionKindDialog by remember { mutableStateOf(false) }

    fun performCreateSession(kind: SessionKind) {
        val id = onNewSession(host, kind)
        if (id == null) {
            sessionLimitReached = true
        } else {
            expandedSessionId = id.value
        }
    }

    LaunchedEffect(Unit) {
        if (createSession && !autoCreateConsumed) {
            autoCreateConsumed = true
            // 快捷“新建会话”默认直接创建普通 SSH 会话；需要 tmux 时从工作区按钮选择。
            performCreateSession(SessionKind.SSH)
        }
    }

    HostWorkspaceContent(
        host = host,
        state = state,
        expandedSessionId = expandedSessionId,
        onExpandSession = { id -> toggleExpand(id) },
        onCreateSession = { showNewSessionKindDialog = true },
        onOpenTerminal = onOpenTerminal,
        onOpenFiles = onOpenFiles,
        onReconnect = onReconnect,
        onRename = { renameSession = it },
        onClose = { closeSession = it },
        onForwards = onForwards,
        onDiagnostics = onDiagnostics,
        modifier = modifier,
    )

    if (showNewSessionKindDialog) {
        SessionKindPickerDialog(
            onDismiss = { showNewSessionKindDialog = false },
            onConfirm = { kind ->
                showNewSessionKindDialog = false
                performCreateSession(kind)
            },
        )
    }

    if (sessionLimitReached) AlertDialog(
        onDismissRequest = { sessionLimitReached = false },
        title = { Text("已达到会话上限") },
        text = { Text("最多可同时保留 8 个会话，请先关闭一个会话。") },
        confirmButton = { TextButton(onClick = { sessionLimitReached = false }) { Text("知道了") } },
    )

    renameSession?.let { session ->
        RenameSessionDialog(
            session = session,
            onDismiss = { renameSession = null },
            onConfirm = { name ->
                onRenameSession(session.id, name)
                renameSession = null
            },
        )
    }

    closeSession?.let { session ->
        val activeForwardCount = app.container.forwardManager.activeForwardCount(session.id)
        AlertDialog(
            onDismissRequest = { closeSession = null },
            title = { Text("关闭会话？") },
            text = {
                if (activeForwardCount > 0) {
                    Text("“${session.displayName}”正承载 $activeForwardCount 条转发，关闭也会停止这些转发。确认关闭并断开该会话？")
                } else {
                    Text("将断开并关闭“${session.displayName}”。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCloseSession(session.id)
                    closeSession = null
                }) {
                    Text(if (activeForwardCount > 0) "关闭并停止转发" else "断开并关闭", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { closeSession = null }) { Text("取消") } },
        )
    }
}

/** 主机工作区列表内容：三个动作 + 会话列表 + 系统集成摘要。 */
@Composable
internal fun HostWorkspaceContent(
    host: HostProfile,
    state: HostWorkspaceUiState,
    expandedSessionId: String?,
    onExpandSession: (SessionId) -> Unit,
    onCreateSession: () -> Unit,
    onOpenTerminal: (SessionId) -> Unit,
    onOpenFiles: (SessionId) -> Unit,
    onReconnect: (SessionId) -> Unit,
    onRename: (ManagedSessionState) -> Unit,
    onClose: (ManagedSessionState) -> Unit,
    onForwards: (Long) -> Unit,
    onDiagnostics: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SshCenteredList(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = structuralSurfaceColor(MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("CONNECTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                            Text(
                                "${host.username}@${host.hostname}:${host.port}",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(routeSummary(host), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val status = state.sessions.firstOrNull()?.connection?.presentation() ?: ("离线" to SshStatusTone.OFFLINE)
                        SshStatusBadge(status.first, status.second)
                    }
                    Button(onClick = onCreateSession, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Text("新建会话", Modifier.padding(start = 8.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SshActionTile(Icons.Default.Public, "端口转发", { onForwards(host.id) }, Modifier.weight(1f))
                        SshActionTile(Icons.Default.NetworkCheck, "连接诊断", { onDiagnostics(host.id) }, Modifier.weight(1f))
                    }
                }
            }
        }

        item { SshSectionHeader("已有会话", summary = "${state.sessions.size}") }
        if (state.sessions.isEmpty()) {
            item { SshEmptyState(Icons.Default.Add, "暂无会话", "点击上方“新建会话”开始连接", Modifier.fillMaxWidth()) }
        } else items(state.sessions, key = { it.id.value }) { session ->
            SessionCard(
                session = session,
                expanded = session.id.value == expandedSessionId,
                onExpand = { onExpandSession(session.id) },
                onOpenTerminal = { onOpenTerminal(session.id) },
                onOpenFiles = { onOpenFiles(session.id) },
                onReconnect = { onReconnect(session.id) },
                onRename = { onRename(session) },
                onClose = { onClose(session) },
            )
        }

        item { SshSectionHeader("系统集成") }
        item {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = structuralSurfaceColor(MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryRow("系统文件访问", if (state.documentAuthorized) "已授权" else "未授权", if (state.documentAuthorized) SshStatusTone.CONNECTED else SshStatusTone.OFFLINE)
                    SummaryRow("传输", if (state.activeTransfers > 0) "${state.activeTransfers} 个进行中" else "无进行中任务", if (state.activeTransfers > 0) SshStatusTone.CONNECTING else SshStatusTone.OFFLINE)
                    SummaryRow("隧道", "${state.runningForwards}/${state.forwardingRules} 运行中", if (state.runningForwards > 0) SshStatusTone.CONNECTED else SshStatusTone.OFFLINE)
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: ManagedSessionState,
    expanded: Boolean,
    onExpand: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onReconnect: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    val pureForward = isPureForward(session)
    val isPersistent = session.kind != SessionKind.SSH
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = structuralSurfaceColor(MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when {
                        pureForward -> Icons.Default.Public
                        isPersistent -> Icons.Default.Terminal
                        SessionFeature.SFTP in session.features -> Icons.Default.Folder
                        else -> Icons.Default.Terminal
                    },
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(session.displayName, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pureForward) {
                            SshStatusBadge("仅转发", SshStatusTone.CONNECTED)
                        } else if (isPersistent) {
                            SshStatusBadge(session.kind.name.lowercase(), SshStatusTone.CONNECTED)
                            if (session.remoteSessionName != null) {
                                SshStatusBadge(
                                    if (session.multiplexerState is MultiplexerSessionState.Active) session.remoteSessionName
                                    else "${session.remoteSessionName} · 未附加",
                                    SshStatusTone.WAITING,
                                )
                            }
                        } else {
                            if (SessionFeature.SHELL in session.features) SshStatusBadge("终端", SshStatusTone.CONNECTED)
                            if (SessionFeature.SFTP in session.features) SshStatusBadge("文件", SshStatusTone.CONNECTED)
                            if (SessionFeature.PORT_FORWARD in session.features) SshStatusBadge("转发", SshStatusTone.CONNECTING)
                        }
                    }
                }
                val status = session.connection.presentation()
                SshStatusBadge(status.first, status.second)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    if (pureForward) {
                        Text(
                            "该会话用于端口转发，不能直接打开终端或文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    } else if (isPersistent) {
                        Text(
                            "${session.kind.name.uppercase()} 持久会话仅提供终端；文件系统请使用普通 SSH 会话。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        OutlinedButton(onClick = onOpenTerminal, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Terminal, null, Modifier.size(18.dp))
                            Text("打开终端", Modifier.padding(start = 6.dp))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onOpenFiles, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                                Text("文件系统", Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(onClick = onOpenTerminal, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Terminal, null, Modifier.size(18.dp))
                                Text("终端", Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 连接建立中（含自动重连的每次尝试）禁用，避免重入发起第二条 transport。
                        TextButton(
                            onClick = onReconnect,
                            enabled = session.connection !is ConnectionState.Connecting,
                        ) { Text("重新连接") }
                        TextButton(onClick = onRename) { Text("重命名") }
                        TextButton(onClick = onClose) { Text("关闭会话", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameSessionDialog(
    session: ManagedSessionState,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(session.id) { mutableStateOf(session.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                singleLine = true,
                label = { Text("会话名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SummaryRow(label: String, value: String, tone: SshStatusTone) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        SshStatusBadge(value, tone)
    }
}

private fun isPureForward(session: ManagedSessionState): Boolean =
    session.features == setOf(SessionFeature.PORT_FORWARD)

private fun routeSummary(host: HostProfile): String = buildList {
    add(if (host.jumpHostId == null) "直连" else "经跳板机")
    host.proxyType?.let { add("${it.name} 代理") }
}.joinToString(" · ")
