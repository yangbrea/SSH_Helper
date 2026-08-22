package com.yang136.sshhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.ForwardType
import com.yang136.sshhelper.ssh.ForwardState
import com.yang136.sshhelper.ssh.PortForwardRule
import com.yang136.sshhelper.ssh.displayName
import com.yang136.sshhelper.ssh.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardScreen(hostId: Long, onBack: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as SshHelperApplication
    val vm: ForwardViewModel = viewModel(
        key = "forward-$hostId",
        factory = ForwardViewModel.factory(app.container, hostId),
    )
    val rules by vm.rules.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val hostName by vm.hostName.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PortForwardRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PortForwardRule?>(null) }

    val running = rules.filter { states[it.id]?.isActive() == true }
    val stopped = rules.filter { states[it.id]?.isActive() != true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("端口转发", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(hostName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (running.isNotEmpty()) {
                        TextButton(onClick = vm::stopAll) { Text("全部停止") }
                    }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { editing = null; showEditor = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("添加规则") },
            )
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Public, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有转发规则", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "把远端内网服务映射到本机、反向打通，或把 SSH 变成 SOCKS5 代理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Text("点击右下角“添加规则”开始", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (running.isNotEmpty()) {
                    item { SectionHeader("运行中", running.size) }
                    items(running, key = PortForwardRule::id) { rule ->
                        ForwardRuleCard(
                            rule = rule,
                            state = states[rule.id] ?: ForwardState.Stopped,
                            onStart = { vm.start(rule.id) },
                            onStop = { vm.stop(rule.id) },
                            onDelete = { deleting = rule },
                        )
                    }
                }
                if (stopped.isNotEmpty()) {
                    item { SectionHeader("已停止", stopped.size, topPadding = if (running.isEmpty()) 0 else 10) }
                    items(stopped, key = PortForwardRule::id) { rule ->
                        ForwardRuleCard(
                            rule = rule,
                            state = states[rule.id] ?: ForwardState.Stopped,
                            onStart = { vm.start(rule.id) },
                            onStop = { vm.stop(rule.id) },
                            onDelete = { deleting = rule },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        RuleEditorDialog(
            hostId = hostId,
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { rule -> vm.save(rule); showEditor = false },
        )
    }
    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除转发规则？") },
            text = { Text("将停止并删除“${rule.name}”。") },
            confirmButton = { TextButton(onClick = { vm.delete(rule.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, topPadding: Int = 0) {
    Row(
        Modifier.fillMaxWidth().padding(top = topPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ForwardRuleCard(
    rule: PortForwardRule,
    state: ForwardState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    val stateColor = when (state) {
        is ForwardState.Running -> MaterialTheme.colorScheme.primary
        ForwardState.Starting, ForwardState.Reconnecting -> MaterialTheme.colorScheme.tertiary
        is ForwardState.Failed -> MaterialTheme.colorScheme.error
        ForwardState.WaitingForUnlock -> MaterialTheme.colorScheme.tertiary
        ForwardState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (rule.type) {
                        ForwardType.LOCAL -> Icons.Default.ArrowDownward
                        ForwardType.REMOTE -> Icons.Default.ArrowUpward
                        ForwardType.DYNAMIC -> Icons.Default.Public
                    },
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(rule.name, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rule.type.displayName(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.width(8.dp))
                Surface(shape = MaterialTheme.shapes.small, color = stateColor.copy(alpha = .14f)) {
                    Text(state.label(), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = stateColor)
                }
            }
            Text(rule.routeText(state), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state is ForwardState.Running || state is ForwardState.Starting) {
                    FilledTonalButton(onClick = onStop) { Icon(Icons.Default.Stop, null, Modifier.size(16.dp)); Text("停止", Modifier.padding(start = 6.dp)) }
                } else {
                    FilledTonalButton(onClick = onStart, enabled = state !is ForwardState.Reconnecting) { Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)); Text("启动", Modifier.padding(start = 6.dp)) }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除规则", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun RuleEditorDialog(
    hostId: Long,
    initial: PortForwardRule?,
    onDismiss: () -> Unit,
    onSave: (PortForwardRule) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ForwardType.LOCAL) }
    var bindAddress by remember { mutableStateOf(initial?.bindAddress ?: "127.0.0.1") }
    var listenPort by remember { mutableStateOf(initial?.listenPort?.toString() ?: "") }
    var targetHost by remember { mutableStateOf(initial?.targetHost ?: "") }
    var targetPort by remember { mutableStateOf(initial?.targetPort?.toString() ?: "") }
    var autoStart by remember { mutableStateOf(initial?.autoStart ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bindMenuOpen by remember { mutableStateOf(false) }

    val bindOptions = when (type) {
        ForwardType.LOCAL -> listOf("127.0.0.1" to "仅本机（安全）", "0.0.0.0" to "局域网（不安全）")
        ForwardType.REMOTE -> listOf("127.0.0.1" to "服务器回环", "0.0.0.0" to "所有接口（受 GatewayPorts 限制）")
        ForwardType.DYNAMIC -> listOf("127.0.0.1" to "仅本机（强制）")
    }
    val bindHint = bindOptions.firstOrNull { it.first == bindAddress }?.second ?: bindOptions.first().second

    val hint = when (type) {
        ForwardType.LOCAL -> "手机访问本机端口，数据经 SSH 加密到达远端内网服务"
        ForwardType.REMOTE -> "服务器上的程序可经隧道访问手机侧可达的服务"
        ForwardType.DYNAMIC -> "手机上的其他应用可把本机端口设为 SOCKS5 代理"
    }

    fun confirm() {
        val rule = PortForwardRule(
            id = initial?.id ?: 0,
            hostId = hostId,
            name = name.trim(),
            type = type,
            bindAddress = bindAddress,
            listenPort = listenPort.toIntOrNull() ?: if (type == ForwardType.LOCAL || type == ForwardType.DYNAMIC) 0 else -1,
            targetHost = targetHost.trim().takeIf(String::isNotBlank),
            targetPort = targetPort.toIntOrNull(),
            autoStart = autoStart,
        )
        validateRuleForDialog(rule)?.let { error = it; return@confirm }
        onSave(rule)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加转发规则" else "编辑转发规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("规则名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForwardType.entries.forEach { item ->
                        FilterChip(selected = type == item, onClick = {
                            type = item
                            bindAddress = when (item) {
                                ForwardType.LOCAL, ForwardType.REMOTE -> "127.0.0.1"
                                ForwardType.DYNAMIC -> "127.0.0.1"
                            }
                        }, label = { Text(item.displayName()) })
                    }
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { bindMenuOpen = true }, Modifier.fillMaxWidth()) {
                            Text("监听：$bindAddress · $bindHint", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(bindMenuOpen, { bindMenuOpen = false }) {
                            bindOptions.forEach { (address, label) ->
                                DropdownMenuItem(
                                    text = { Text("$address · $label", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { bindAddress = address; bindMenuOpen = false },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    listenPort,
                    { listenPort = it.filter(Char::isDigit).take(5) },
                    Modifier.fillMaxWidth(),
                    label = { Text(if (type == ForwardType.REMOTE) "服务器监听端口" else "本机监听端口") },
                    placeholder = { if (type != ForwardType.REMOTE) Text("0 = 自动分配") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (type != ForwardType.DYNAMIC) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(targetHost, { targetHost = it }, Modifier.weight(.6f), label = { Text("目标主机") }, singleLine = true)
                        OutlinedTextField(targetPort, { targetPort = it.filter(Char::isDigit).take(5) }, Modifier.weight(.4f), label = { Text("目标端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(autoStart, { autoStart = it })
                    Text("进入转发中心时自动启动", Modifier.padding(start = 10.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = ::confirm) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
