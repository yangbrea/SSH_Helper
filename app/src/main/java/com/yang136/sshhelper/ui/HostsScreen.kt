package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.security.VaultState
import com.yang136.sshhelper.data.TransferStatus
import androidx.compose.ui.platform.LocalContext

/** 主页的两个入口模式:终端 / 文件管理。 */
enum class HomeMode { TERMINAL, FILES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    onAdd: () -> Unit,
    onEdit: (HostProfile) -> Unit,
    onConnect: (HostProfile) -> Boolean,
    onFiles: (HostProfile) -> Boolean,
    onForwards: (Long) -> Unit,
    sessions: List<ManagedSessionState>,
    onCloseHostSessions: (Long, () -> Unit) -> Unit,
    onSettings: () -> Unit,
    onSnippets: () -> Unit,
    vaultState: VaultState,
    onVaultClick: () -> Unit,
    onExit: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as SshHelperApplication
    val vm: HostsViewModel = viewModel(factory = HostsViewModel.factory(app.container))
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val transfers by app.container.transferManager.jobs.collectAsStateWithLifecycle()
    val deleteError by vm.deleteError.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var mode by rememberSaveable { mutableStateOf(HomeMode.TERMINAL) }
    var deleting by remember { mutableStateOf<HostProfile?>(null) }
    var hostMenu by remember { mutableStateOf<Long?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var sessionLimitReached by remember { mutableStateOf(false) }

    BackHandler { confirmExit = true }
    LaunchedEffect(deleteError) { deleteError?.let { snackbar.showSnackbar(it); vm.clearDeleteError() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SSH Helper", fontWeight = FontWeight.SemiBold)
                        Text(
                            when (mode) {
                                HomeMode.TERMINAL -> "选择主机打开终端"
                                HomeMode.FILES -> "选择主机管理文件"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onVaultClick) {
                        Icon(
                            when (vaultState) {
                                VaultState.Locked -> Icons.Default.Lock
                                is VaultState.Unlocked -> Icons.Default.LockOpen
                                is VaultState.Unavailable -> Icons.Default.Warning
                                VaultState.Disabled -> Icons.Default.LockOpen
                            },
                            when (vaultState) {
                                VaultState.Locked -> "解锁凭据保险库"
                                is VaultState.Unlocked -> "锁定凭据保险库"
                                is VaultState.Unavailable -> "保险库不可用"
                                VaultState.Disabled -> "保险库未启用"
                            },
                        )
                    }
                    IconButton(onClick = onSnippets) { Icon(Icons.Default.Terminal, "快捷命令") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = mode == HomeMode.TERMINAL,
                    onClick = { mode = HomeMode.TERMINAL },
                    icon = { Icon(Icons.Default.Terminal, null) },
                    label = { Text("终端") },
                )
                NavigationBarItem(
                    selected = mode == HomeMode.FILES,
                    onClick = { mode = HomeMode.FILES },
                    icon = { Icon(Icons.Default.Folder, null) },
                    label = { Text("文件管理") },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("添加主机") })
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有服务器", style = MaterialTheme.typography.titleMedium)
                    Text("添加 SSH 主机后即可开始连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(hosts, key = { it.id }) { host ->
                    val primary = if (mode == HomeMode.TERMINAL) {
                        { if (!onConnect(host)) sessionLimitReached = true }
                    } else {
                        { if (!onFiles(host)) sessionLimitReached = true }
                    }
                    Card(
                        onClick = primary,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (host.authType == AuthType.PASSWORD) Icons.Default.Lock else Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(host.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                Text("${host.username}@${host.hostname}:${host.port}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (mode == HomeMode.TERMINAL) {
                                // 从终端入口直接切到文件管理并打开该主机
                                IconButton(onClick = {
                                    mode = HomeMode.FILES
                                    if (!onFiles(host)) sessionLimitReached = true
                                }) { Icon(Icons.Default.Folder, "文件管理") }
                            }
                            Box {
                                IconButton(onClick = { hostMenu = host.id }) { Icon(Icons.Default.MoreVert, "更多操作") }
                                DropdownMenu(expanded = hostMenu == host.id, onDismissRequest = { hostMenu = null }) {
                                    DropdownMenuItem(
                                        text = { Text("端口转发") },
                                        leadingIcon = { Icon(Icons.Default.Public, null) },
                                        onClick = { hostMenu = null; onForwards(host.id) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("编辑") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = { hostMenu = null; onEdit(host) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = { hostMenu = null; deleting = host },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { host ->
        val activeCount = sessions.count { it.profile.id == host.id }
        val transferCount = transfers.count { it.hostId == host.id && it.status in activeTransferStates }
        val dependentCount = hosts.count { it.jumpHostId == host.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除主机？") },
            text = {
                val reasons = buildList {
                    if (dependentCount > 0) add("有 $dependentCount 台主机使用它作为跳板机，必须先解除引用")
                    if (activeCount > 0) add("先断开 $activeCount 个活动会话")
                    if (transferCount > 0) add("取消 $transferCount 个传输任务")
                }
                Text(
                    if (reasons.isEmpty()) "将删除“${host.name}”及其保存的加密凭据。"
                    else "将${reasons.joinToString("，")}，再删除“${host.name}”及其凭据。",
                )
            },
            confirmButton = { TextButton(onClick = {
                if (activeCount == 0 && dependentCount == 0) vm.delete(host) else if (dependentCount == 0) onCloseHostSessions(host.id) { vm.delete(host) }
                deleting = null
            }, enabled = dependentCount == 0) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出 SSH Helper？") },
            text = { Text(if (sessions.isEmpty()) "确认退出应用吗？" else "退出将断开 ${sessions.size} 个活动 SSH 会话。") },
            confirmButton = { TextButton(onClick = onExit) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("取消") } },
        )
    }

    if (sessionLimitReached) {
        AlertDialog(
            onDismissRequest = { sessionLimitReached = false },
            title = { Text("已达到会话上限") },
            text = { Text("最多可同时保留 8 个会话，请返回终端并关闭一个会话。") },
            confirmButton = { TextButton(onClick = { sessionLimitReached = false }) { Text("知道了") } },
        )
    }
}

private val activeTransferStates = setOf(
    TransferStatus.QUEUED, TransferStatus.RUNNING, TransferStatus.PAUSED,
    TransferStatus.WAITING_NETWORK, TransferStatus.WAITING_UNLOCK,
)
