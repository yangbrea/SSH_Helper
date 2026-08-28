package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.security.VaultState
import com.yang136.sshhelper.ssh.*
import com.yang136.sshhelper.ui.design.*

@Composable
fun HostsScreen(
    onAdd: () -> Unit,
    onEdit: (HostProfile) -> Unit,
    onOpenHost: (HostProfile) -> Unit,
    onConnect: (HostProfile) -> Boolean,
    onForwards: (Long) -> Unit,
    sessions: List<ManagedSessionState>,
    onOpenSession: (SessionId) -> Unit,
    onCloseSession: (SessionId) -> Unit,
    onCloseHostSessions: (Long, () -> Unit) -> Unit,
    onSnippets: () -> Unit,
    vaultState: VaultState,
    onVaultClick: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as SshHelperApplication
    val vm: HostsViewModel = viewModel(factory = HostsViewModel.factory(app.container))
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val transfers by app.container.transferManager.jobs.collectAsStateWithLifecycle()
    val deleteError by vm.deleteError.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var sessionsExpanded by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<HostProfile?>(null) }
    var hostMenu by remember { mutableStateOf<Long?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var sessionLimitReached by remember { mutableStateOf(false) }
    var closingSession by remember { mutableStateOf<ManagedSessionState?>(null) }
    val visibleHosts = remember(hosts, query) {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) hosts else hosts.filter {
            it.name.lowercase().contains(needle) || it.hostname.lowercase().contains(needle) || it.username.lowercase().contains(needle)
        }
    }

    BackHandler { confirmExit = true }
    LaunchedEffect(deleteError) { deleteError?.let { snackbar.showSnackbar(it); vm.clearDeleteError() } }

    Scaffold(
        modifier = modifier,
        containerColor = imageAwareScaffoldColor(),
        topBar = {
            SshTopAppBar(
                title = "主机",
                subtitle = "${hosts.size} 台主机 · ${sessions.size} 个活动会话",
                actions = {
                    IconButton(onClick = onVaultClick) { Icon(vaultIcon(vaultState), vaultDescription(vaultState)) }
                    IconButton(onClick = onSnippets) { Icon(Icons.Default.Terminal, "快捷命令") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("添加主机") }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("搜索名称、地址或用户") })
            }
            if (sessions.isNotEmpty()) {
                item {
                    SshSectionHeader(
                        title = "活动会话",
                        summary = "${sessions.size}/8",
                        onClick = { sessionsExpanded = !sessionsExpanded },
                        expanded = sessionsExpanded,
                    )
                }
                if (sessionsExpanded) {
                    items(sessions.take(3), key = { "session-${it.id.value}" }) { session ->
                        Card(onClick = { onOpenSession(session.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .42f))) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (SessionFeature.SFTP in session.features) Icons.Default.Folder else Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(session.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(session.profile.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val presentation = session.connection.presentation()
                                SshStatusBadge(presentation.first, presentation.second)
                                IconButton(onClick = { closingSession = session }) { Icon(Icons.Default.Close, "关闭会话") }
                            }
                        }
                    }
                }
            }
            item { SshSectionHeader(if (query.isBlank()) "所有主机" else "搜索结果", summary = "${visibleHosts.size}") }
            if (visibleHosts.isEmpty()) {
                item { Box(Modifier.fillParentMaxHeight(.55f), contentAlignment = Alignment.Center) {
                    SshEmptyState(Icons.Default.Computer, if (hosts.isEmpty()) "还没有主机" else "没有匹配的主机", if (hosts.isEmpty()) "添加 SSH 主机后即可开始连接" else "尝试缩短关键词或检查拼写")
                } }
            } else items(visibleHosts, key = { it.id }) { host ->
                SshHostCard(onClick = { onOpenHost(host) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (host.authType == AuthType.PASSWORD) Icons.Default.Lock else Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(host.name, style = MaterialTheme.typography.titleMedium)
                            Text("${host.username}@${host.hostname}:${host.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            host.jumpHostId?.let { Text("经跳板机连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                        }
                        IconButton(onClick = { if (!onConnect(host)) sessionLimitReached = true }) { Icon(Icons.Default.Terminal, "新建终端") }
                        Box {
                            IconButton(onClick = { hostMenu = host.id }) { Icon(Icons.Default.MoreVert, "更多操作") }
                            DropdownMenu(expanded = hostMenu == host.id, onDismissRequest = { hostMenu = null }) {
                                DropdownMenuItem(text = { Text("端口转发") }, leadingIcon = { Icon(Icons.Default.Public, null) }, onClick = { hostMenu = null; onForwards(host.id) })
                                DropdownMenuItem(text = { Text("编辑") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { hostMenu = null; onEdit(host) })
                                DropdownMenuItem(text = { Text("删除") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { hostMenu = null; deleting = host })
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { host ->
        val activeCount = sessions.count { it.profile.id == host.id }
        val transferCount = transfers.count { it.hostId == host.id && it.status.isActiveTransfer() }
        val dependentCount = hosts.count { it.jumpHostId == host.id }
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除主机？") }, text = {
            val reasons = buildList {
                if (dependentCount > 0) add("有 $dependentCount 台主机使用它作为跳板机")
                if (activeCount > 0) add("将断开 $activeCount 个活动会话")
                if (transferCount > 0) add("将取消 $transferCount 个传输任务")
            }
            Text(if (reasons.isEmpty()) "将删除“${host.name}”及其保存的加密凭据。" else reasons.joinToString("；") + "。")
        }, confirmButton = { TextButton(onClick = {
            if (activeCount == 0 && dependentCount == 0) vm.delete(host) else if (dependentCount == 0) onCloseHostSessions(host.id) { vm.delete(host) }
            deleting = null
        }, enabled = dependentCount == 0) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("退出 SSH Helper？") },
        text = { Text(if (sessions.isEmpty()) "确认退出应用吗？" else "退出将断开 ${sessions.size} 个活动 SSH 会话。") },
        confirmButton = { TextButton(onClick = onExit) { Text("退出") } }, dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("取消") } })
    if (sessionLimitReached) AlertDialog(onDismissRequest = { sessionLimitReached = false }, title = { Text("已达到会话上限") },
        text = { Text("最多可同时保留 8 个会话，请先关闭一个会话。") }, confirmButton = { TextButton(onClick = { sessionLimitReached = false }) { Text("知道了") } })
    closingSession?.let { session -> AlertDialog(onDismissRequest = { closingSession = null }, title = { Text("关闭会话？") },
        text = { Text("将断开并关闭“${session.displayName}”。") }, confirmButton = { TextButton(onClick = { onCloseSession(session.id); closingSession = null }) { Text("断开并关闭") } },
        dismissButton = { TextButton(onClick = { closingSession = null }) { Text("取消") } }) }
}

private fun vaultIcon(state: VaultState) = when (state) {
    VaultState.Locked -> Icons.Default.Lock
    is VaultState.Unlocked, VaultState.Disabled -> Icons.Default.LockOpen
    is VaultState.Unavailable -> Icons.Default.Warning
}

private fun vaultDescription(state: VaultState) = when (state) {
    VaultState.Locked -> "解锁凭据保险库"
    is VaultState.Unlocked -> "锁定凭据保险库"
    is VaultState.Unavailable -> "保险库不可用"
    VaultState.Disabled -> "保险库未启用"
}
