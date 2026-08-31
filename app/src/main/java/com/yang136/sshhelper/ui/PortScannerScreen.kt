package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.scanner.FingerprintConfidence
import com.yang136.sshhelper.scanner.PortProbeResult
import com.yang136.sshhelper.ui.design.SshCenteredList
import com.yang136.sshhelper.ui.design.SshInlineBanner
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusBadge
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar

@Composable
fun PortScannerScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SshHelperApplication
    val vm: PortScannerViewModel = viewModel(factory = PortScannerViewModel.factory(app.container))
    val state by vm.state.collectAsStateWithLifecycle()
    var networkMenu by remember { mutableStateOf(false) }
    var addressMenu by remember { mutableStateOf(false) }
    var confirmAllPorts by remember { mutableStateOf(false) }
    val requestBack = { if (state.status == PortScannerStatus.SCANNING) vm.cancelScan(); onBack() }
    BackHandler(onBack = requestBack)

    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        contentColor = imageAwareContentColor(),
        topBar = {
            SshTopAppBar(
                "Port Scanner",
                subtitle = if (state.status == PortScannerStatus.SCANNING) "正在扫描 ${state.completed}/${state.total}" else "TCP Connect · Banner · 服务识别",
                navigationIcon = { IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (state.status != PortScannerStatus.SCANNING) IconButton(onClick = vm::refreshNetworks) { Icon(Icons.Default.Refresh, "刷新网络") }
                },
            )
        },
    ) { padding ->
        SshCenteredList(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SshSectionHeader("扫描设置", summary = "单目标直连") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { networkMenu = true }, enabled = state.status != PortScannerStatus.SCANNING, modifier = Modifier.fillMaxWidth()) {
                        Text(state.networks.firstOrNull { it.id == state.selectedNetworkId }?.label ?: "选择网络", Modifier.weight(1f))
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = networkMenu, onDismissRequest = { networkMenu = false }) {
                        state.networks.forEach { network -> DropdownMenuItem(text = { Text(network.label) }, onClick = { vm.selectNetwork(network.id); networkMenu = false }) }
                    }
                    OutlinedTextField(state.targetInput, vm::updateTarget, label = { Text("目标域名 / IPv4 / IPv6") }, singleLine = true, enabled = state.status != PortScannerStatus.SCANNING, modifier = Modifier.fillMaxWidth())
                    if (state.resolvedAddresses.size > 1) {
                        OutlinedButton(onClick = { addressMenu = true }, enabled = state.status != PortScannerStatus.SCANNING, modifier = Modifier.fillMaxWidth()) {
                            Text(state.selectedAddress ?: "选择解析地址", Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                            Icon(Icons.Default.ExpandMore, null)
                        }
                        DropdownMenu(expanded = addressMenu, onDismissRequest = { addressMenu = false }) {
                            state.resolvedAddresses.forEach { address -> DropdownMenuItem(text = { Text(address, fontFamily = FontFamily.Monospace) }, onClick = { vm.selectAddress(address); addressMenu = false }) }
                        }
                    }
                    OutlinedTextField(state.portsInput, vm::updatePorts, label = { Text("端口列表 / 范围") }, supportingText = { Text("示例：22,80,443,8000-8100") }, enabled = state.status != PortScannerStatus.SCANNING, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = vm::useCommonPorts, enabled = state.status != PortScannerStatus.SCANNING) { Text("常见端口") }
                        TextButton(onClick = { confirmAllPorts = true }, enabled = state.status != PortScannerStatus.SCANNING) { Text("全端口") }
                    }
                    if (state.status == PortScannerStatus.SCANNING) {
                        LinearProgressIndicator(progress = { if (state.total == 0) 0f else state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = vm::cancelScan, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Cancel, null); Text(" 取消扫描") }
                    } else {
                        Button(onClick = vm::startScan, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) { Text("开始扫描") }
                    }
                }
            }
            state.error?.let { item { SshInlineBanner("扫描失败", it, tone = SshStatusTone.ERROR) } }
            if (state.completed > 0 || state.summary != null) {
                item { SshSectionHeader("扫描结果", summary = "开放 ${state.openPorts.size} · 已完成 ${state.completed}/${state.total}") }
                item {
                    Text("拒绝 ${state.refused} · 超时/过滤 ${state.timeoutFiltered} · 不可达 ${state.unreachable} · 异常 ${state.errors}", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.openPorts, key = PortProbeResult::port) { result -> PortResultCard(result) }
            if (state.status == PortScannerStatus.COMPLETED && state.openPorts.isEmpty()) {
                item { SshInlineBanner("扫描完成", "没有发现开放的 TCP 端口", tone = SshStatusTone.WARNING) }
            }
        }
    }
    if (confirmAllPorts) {
        AlertDialog(
            onDismissRequest = { confirmAllPorts = false },
            title = { Text("扫描全部 65535 个端口？") },
            text = { Text("全端口扫描可能持续数分钟并明显耗电。扫描只在当前页面前台运行，可随时取消。") },
            confirmButton = { TextButton(onClick = { vm.useAllPorts(); confirmAllPorts = false }) { Text("使用全端口") } },
            dismissButton = { TextButton(onClick = { confirmAllPorts = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PortResultCard(result: PortProbeResult) {
    val fingerprint = result.fingerprint
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Router, null, tint = MaterialTheme.colorScheme.primary)
                Text("TCP ${result.port}", Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                SshStatusBadge(fingerprint?.service ?: "OPEN", when (fingerprint?.confidence) {
                    FingerprintConfidence.HIGH -> SshStatusTone.CONNECTED
                    FingerprintConfidence.MEDIUM -> SshStatusTone.WARNING
                    else -> SshStatusTone.WAITING
                })
            }
            Text("连接 ${"%.1f".format(result.latencyMillis ?: 0.0)} ms · ${fingerprint?.evidence ?: "端口开放"}", style = MaterialTheme.typography.bodySmall)
            fingerprint?.banner?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 8) }
            if (fingerprint?.tlsUnverified == true) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary); Text(" TLS 证书仅用于识别，未验证信任", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
