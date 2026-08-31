package com.yang136.sshhelper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.scanner.FingerprintConfidence
import com.yang136.sshhelper.scanner.PortProbeResult
import com.yang136.sshhelper.ui.design.SshCenteredList
import com.yang136.sshhelper.ui.design.SshInlineBanner
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusBadge
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar

const val PORT_SCANNER_ROUTE_PATTERN = "port-scanner?target={target}&networkId={networkId}&full={full}"

fun portScannerRoute(
    target: String = "",
    networkId: String = "",
    fullScan: Boolean = false,
): String = "port-scanner?target=${Uri.encode(target)}&networkId=${Uri.encode(networkId)}&full=$fullScan"

@Composable
fun PortScannerScreen(
    initialTarget: String = "",
    initialNetworkId: String = "",
    preselectFullScan: Boolean = false,
    hosts: List<HostProfile>,
    onSsh: (name: String, address: String, port: Int, existingHostId: Long?) -> Unit,
    onDiagnosticLogs: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as SshHelperApplication
    val vm: PortScannerViewModel = viewModel(
        factory = PortScannerViewModel.factory(
            container = app.container,
            initialTarget = initialTarget,
            initialNetworkId = initialNetworkId,
            preselectFullScan = preselectFullScan,
        ),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var networkMenu by remember { mutableStateOf(false) }
    var addressMenu by remember { mutableStateOf(false) }
    var confirmAllPorts by remember { mutableStateOf(false) }
    var pendingWebUrl by remember { mutableStateOf<String?>(null) }
    var resultFilter by remember { mutableStateOf(PortResultFilter.ALL) }
    var resultSort by remember { mutableStateOf(PortResultSort.PORT) }
    var sortMenu by remember { mutableStateOf(false) }
    val visibleResults = remember(state.openPorts, resultFilter, resultSort) {
        state.openPorts
            .filter { resultFilter.accepts(it) }
            .let { results ->
                when (resultSort) {
                    PortResultSort.PORT -> results.sortedBy(PortProbeResult::port)
                    PortResultSort.SERVICE -> results.sortedWith(compareBy({ it.fingerprint?.service ?: "ZZZ" }, PortProbeResult::port))
                    PortResultSort.LATENCY -> results.sortedBy { it.latencyMillis ?: Double.MAX_VALUE }
                }
            }
    }
    val requestBack = { if (state.status == PortScannerStatus.SCANNING) vm.cancelScan(); onBack() }
    BackHandler(onBack = requestBack)

    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        contentColor = imageAwareContentColor(),
        topBar = {
            SshTopAppBar(
                "Port Scanner",
                subtitle = when {
                    state.rescanningPort != null -> "正在重新探测 TCP ${state.rescanningPort}"
                    state.status == PortScannerStatus.SCANNING -> "正在扫描 ${state.completed}/${state.total}"
                    else -> "TCP Connect · Banner · 服务识别"
                },
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
                        if (state.rescanningPort != null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { if (state.total == 0) 0f else state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedButton(onClick = vm::cancelScan, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Cancel, null); Text(" 取消扫描") }
                    } else {
                        Button(onClick = vm::startScan, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) { Text("开始扫描") }
                    }
                }
            }
            state.error?.let {
                item {
                    SshInlineBanner(
                        if (state.status == PortScannerStatus.ERROR) "扫描失败" else "扫描提示",
                        it,
                        tone = if (state.status == PortScannerStatus.ERROR) SshStatusTone.ERROR else SshStatusTone.WARNING,
                    )
                }
            }
            if (state.completed > 0 || state.summary != null) {
                item { SshSectionHeader("扫描结果", summary = "开放 ${state.openPorts.size} · 已完成 ${state.completed}/${state.total}") }
                item {
                    Text("拒绝 ${state.refused} · 超时/过滤 ${state.timeoutFiltered} · 不可达 ${state.unreachable} · 异常 ${state.errors}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.openPorts.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PortResultFilter.entries.forEach { filter ->
                                FilterChip(
                                    selected = resultFilter == filter,
                                    onClick = { resultFilter = filter },
                                    label = { Text(filter.label) },
                                )
                            }
                        }
                        Box {
                            OutlinedButton(onClick = { sortMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("排序：${resultSort.label}", Modifier.weight(1f))
                                Icon(Icons.Default.ExpandMore, null)
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                PortResultSort.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text(sort.label) },
                                        onClick = { resultSort = sort; sortMenu = false },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = vm::rescanOpenPorts,
                                enabled = state.status != PortScannerStatus.SCANNING,
                                modifier = Modifier.weight(1f),
                            ) { Text("重扫开放端口") }
                            OutlinedButton(
                                onClick = onDiagnosticLogs,
                                enabled = state.status != PortScannerStatus.SCANNING,
                                modifier = Modifier.weight(1f),
                            ) { Text("诊断记录") }
                        }
                    }
                }
            }
            items(visibleResults, key = PortProbeResult::port) { result ->
                val target = state.targetInput.trim().ifBlank { result.address }
                val existingHost = hosts.firstOrNull { host ->
                    host.port == result.port && (
                        host.hostname.trim().equals(target, ignoreCase = true) ||
                            host.hostname.trim().equals(result.address, ignoreCase = true)
                        )
                }
                PortResultCard(
                    result = result,
                    target = target,
                    existingHost = existingHost,
                    scanning = state.status == PortScannerStatus.SCANNING,
                    onOpenWeb = { pendingWebUrl = portScanWebUrl(target, result) },
                    onSsh = {
                        onSsh(
                            existingHost?.name ?: target,
                            target,
                            result.port,
                            existingHost?.id,
                        )
                    },
                    onCopyEndpoint = { copyText(context, "端点", endpointText(result.address, result.port)) },
                    onCopyBanner = { banner -> copyText(context, "Banner", banner) },
                    onRescan = { vm.rescanPort(result.port) },
                )
            }
            if (state.openPorts.isNotEmpty() && visibleResults.isEmpty()) {
                item { SshInlineBanner("没有匹配结果", "调整服务筛选后可查看其他开放端口", tone = SshStatusTone.WARNING) }
            }
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
    pendingWebUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingWebUrl = null },
            title = { Text("在外部浏览器打开？") },
            text = { Text(url, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = {
                    pendingWebUrl = null
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { Toast.makeText(context, "没有可打开该地址的应用", Toast.LENGTH_SHORT).show() }
                }) {
                    Icon(Icons.Default.OpenInBrowser, null)
                    Text(" 打开")
                }
            },
            dismissButton = { TextButton(onClick = { pendingWebUrl = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PortResultCard(
    result: PortProbeResult,
    target: String,
    existingHost: HostProfile?,
    scanning: Boolean,
    onOpenWeb: () -> Unit,
    onSsh: () -> Unit,
    onCopyEndpoint: () -> Unit,
    onCopyBanner: (String) -> Unit,
    onRescan: () -> Unit,
) {
    val fingerprint = result.fingerprint
    val service = fingerprint?.service.orEmpty().uppercase()
    val isWeb = service == "HTTP" || service == "HTTPS"
    val isSsh = service == "SSH"
    var expanded by remember(result.port) { mutableStateOf(false) }
    Card(
        modifier = Modifier.clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Router, null, tint = MaterialTheme.colorScheme.primary)
                Text("TCP ${result.port}", Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                SshStatusBadge(fingerprint?.service ?: "OPEN", when (fingerprint?.confidence) {
                    FingerprintConfidence.HIGH -> SshStatusTone.CONNECTED
                    FingerprintConfidence.MEDIUM -> SshStatusTone.WARNING
                    else -> SshStatusTone.WAITING
                })
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "收起详情" else "展开详情",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text("连接 ${"%.1f".format(result.latencyMillis ?: 0.0)} ms · ${fingerprint?.evidence ?: "端口开放"}", style = MaterialTheme.typography.bodySmall)
            if (fingerprint?.tlsUnverified == true) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary); Text(" TLS 证书仅用于识别，未验证信任", style = MaterialTheme.typography.labelSmall) }
            }
            if (expanded) {
                Text(endpointText(result.address, result.port), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                listOfNotNull(fingerprint?.product, fingerprint?.version).takeIf { it.isNotEmpty() }?.let { parts ->
                    Text("产品：${parts.joinToString(" ")}", style = MaterialTheme.typography.bodySmall)
                }
                fingerprint?.banner?.takeIf(String::isNotBlank)?.let { banner ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            banner,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 12,
                        )
                    }
                }
                if (isWeb) {
                    OutlinedButton(onClick = onOpenWeb, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInBrowser, null)
                        Text(" 打开 Web")
                    }
                }
                if (isSsh) {
                    OutlinedButton(onClick = onSsh, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Terminal, null)
                        Text(if (existingHost == null) " 添加 SSH 主机" else " 打开已添加主机")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onCopyEndpoint) {
                        Icon(Icons.Default.ContentCopy, null)
                        Text(" 复制端点")
                    }
                    fingerprint?.banner?.takeIf(String::isNotBlank)?.let { banner ->
                        TextButton(onClick = { onCopyBanner(banner) }) { Text("复制 Banner") }
                    }
                    TextButton(onClick = onRescan, enabled = !scanning) {
                        Icon(Icons.Default.Refresh, null)
                        Text(" 重新探测")
                    }
                }
                Text("扫描目标：$target", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private enum class PortResultFilter(val label: String) {
    ALL("全部"),
    SSH("SSH"),
    WEB("Web"),
    OTHER("其他");

    fun accepts(result: PortProbeResult): Boolean {
        val service = result.fingerprint?.service.orEmpty().uppercase()
        return when (this) {
            ALL -> true
            SSH -> service == "SSH"
            WEB -> service == "HTTP" || service == "HTTPS"
            OTHER -> service != "SSH" && service != "HTTP" && service != "HTTPS"
        }
    }
}

private enum class PortResultSort(val label: String) {
    PORT("端口"),
    SERVICE("服务"),
    LATENCY("延迟"),
}

private fun portScanWebUrl(target: String, result: PortProbeResult): String {
    val scheme = if (result.fingerprint?.service.equals("HTTPS", ignoreCase = true)) "https" else "http"
    val host = target.trim().removePrefix("[").removeSuffix("]").ifBlank { result.address }
    return "$scheme://${if (':' in host) "[$host]" else host}:${result.port}/"
}

private fun endpointText(address: String, port: Int): String =
    "${if (':' in address) "[$address]" else address}:$port"

private fun copyText(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
}
