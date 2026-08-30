package com.yang136.sshhelper.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.discovery.ClassificationConfidence
import com.yang136.sshhelper.discovery.DeviceKind
import com.yang136.sshhelper.discovery.DiscoveredDevice
import com.yang136.sshhelper.discovery.DiscoveredService
import com.yang136.sshhelper.discovery.DiscoverySource
import com.yang136.sshhelper.discovery.DiscoveryStatus
import com.yang136.sshhelper.discovery.ScanMode
import com.yang136.sshhelper.discovery.ServiceKind
import com.yang136.sshhelper.discovery.SshConfidence
import com.yang136.sshhelper.ui.design.SshCenteredList
import com.yang136.sshhelper.ui.design.SshEmptyState
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusBadge
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar

@Composable
fun LanDiscoveryScreen(
    hosts: List<HostProfile>,
    onSelect: (name: String, address: String, port: Int, existingHostId: Long?) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as SshHelperApplication
    val vm: DiscoveryViewModel = viewModel(factory = DiscoveryViewModel.factory(app.container))
    val state by vm.state.collectAsStateWithLifecycle()
    var networkMenu by remember { mutableStateOf(false) }
    var pendingWebUrl by remember { mutableStateOf<String?>(null) }
    val requestBack = { vm.cancelScan(); onBack() }
    BackHandler(onBack = requestBack)

    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        contentColor = imageAwareContentColor(),
        topBar = {
            SshTopAppBar(
                title = if (state.mode == ScanMode.SSH) "发现 SSH 主机" else "发现局域网设备",
                subtitle = when (state.status) {
                    DiscoveryStatus.SCANNING -> "正在探测 ${state.completedProbes}/${state.totalProbes}"
                    DiscoveryStatus.COMPLETED -> "发现 ${state.devices.size} 台设备"
                    else -> if (state.mode == ScanMode.SSH) {
                        "TCP · SSH Banner · mDNS · ARP"
                    } else {
                        "自适应 TCP · mDNS · SSDP · ARP"
                    }
                },
                navigationIcon = {
                    IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (state.status != DiscoveryStatus.SCANNING) {
                        IconButton(onClick = vm::refreshNetworks) { Icon(Icons.Default.Refresh, "刷新网络") }
                    }
                },
            )
        },
    ) { padding ->
        SshCenteredList(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SshSectionHeader("扫描设置", summary = "最多 1024 个 IPv4 地址") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.mode == ScanMode.SSH,
                            onClick = { vm.selectMode(ScanMode.SSH) },
                            label = { Text("SSH") },
                            enabled = state.status != DiscoveryStatus.SCANNING,
                        )
                        FilterChip(
                            selected = state.mode == ScanMode.GENERAL,
                            onClick = { vm.selectMode(ScanMode.GENERAL) },
                            label = { Text("通用设备") },
                            enabled = state.status != DiscoveryStatus.SCANNING,
                        )
                    }
                    val selected = state.networks.firstOrNull { it.id == state.selectedNetworkId }
                    if (state.networks.size > 1) {
                        Box {
                            OutlinedButton(
                                onClick = { networkMenu = true },
                                enabled = state.status != DiscoveryStatus.SCANNING,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Router, null)
                                Text(
                                    selected?.let { "  ${it.label} · ${it.ipv4Address}/${it.prefixLength}" } ?: "  选择局域网",
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(Icons.Default.ExpandMore, null)
                            }
                            DropdownMenu(expanded = networkMenu, onDismissRequest = { networkMenu = false }) {
                                state.networks.forEach { network ->
                                    DropdownMenuItem(
                                        text = { Text("${network.label} · ${network.ipv4Address}/${network.prefixLength}") },
                                        onClick = { networkMenu = false; vm.selectNetwork(network.id) },
                                    )
                                }
                            }
                        }
                    } else if (selected != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Router, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(selected.label, fontWeight = FontWeight.Medium)
                                    Text("${selected.ipv4Address}/${selected.prefixLength} · ${selected.interfaceName}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.cidrInput,
                        onValueChange = vm::updateCidr,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("IPv4 CIDR") },
                        placeholder = { Text("192.168.1.0/24") },
                        enabled = state.status != DiscoveryStatus.SCANNING,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.portsInput,
                        onValueChange = vm::updatePorts,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state.mode == ScanMode.SSH) "SSH 端口" else "额外第一阶段端口（可选）") },
                        supportingText = {
                            Text(
                                if (state.mode == ScanMode.SSH) {
                                    "最多 16 个；mDNS 广播端口会自动探测"
                                } else {
                                    "最多 4 个；固定常用端口和广播端口无需填写"
                                },
                            )
                        },
                        enabled = state.status != DiscoveryStatus.SCANNING,
                        singleLine = true,
                    )
                    if (state.status == DiscoveryStatus.SCANNING) {
                        LinearProgressIndicator(
                            progress = { if (state.totalProbes == 0) 0f else state.completedProbes.toFloat() / state.totalProbes },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = vm::cancelScan, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Cancel, null)
                            Text(" 取消扫描")
                        }
                    } else {
                        Button(
                            onClick = vm::startScan,
                            enabled = state.networks.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Search, null)
                            Text(if (state.status == DiscoveryStatus.COMPLETED) " 重新扫描" else " 开始扫描")
                        }
                    }
                }
            }
            state.error?.let { error -> item { MessageCard(error, error = true) } }
            state.notice?.let { notice -> item { MessageCard(notice, error = false) } }
            if (state.devices.isNotEmpty()) {
                item { SshSectionHeader("发现结果", summary = "${state.devices.size}") }
                items(state.devices, key = { "${it.networkId}-${it.address}" }) { device ->
                    DiscoveryDeviceCard(
                        device = device,
                        mode = state.mode,
                        hosts = hosts,
                        onSelect = onSelect,
                        onOpenDetails = { vm.openDetails(device.address) },
                    )
                }
            } else if (state.status in setOf(DiscoveryStatus.COMPLETED, DiscoveryStatus.CANCELLED, DiscoveryStatus.NO_NETWORK)) {
                item {
                    Box(Modifier.fillParentMaxHeight(.35f), contentAlignment = Alignment.Center) {
                        SshEmptyState(
                            icon = Icons.Default.Devices,
                            title = if (state.status == DiscoveryStatus.NO_NETWORK) {
                                "没有可扫描的局域网"
                            } else if (state.mode == ScanMode.SSH) {
                                "未发现 SSH 主机"
                            } else {
                                "未发现局域网设备"
                            },
                            description = if (state.status == DiscoveryStatus.CANCELLED) {
                                "扫描已取消，可调整范围后重试"
                            } else if (state.mode == ScanMode.SSH) {
                                "确认设备在线、SSH 已启动，或添加自定义端口"
                            } else {
                                "静默且不响应 TCP、mDNS、SSDP 的设备可能无法发现"
                            },
                        )
                    }
                }
            }
        }
    }

    state.selectedDevice?.let { device ->
        DeviceDetailsSheet(
            device = device,
            hosts = hosts,
            loading = state.detailLoading,
            error = state.detailError,
            onDismiss = vm::closeDetails,
            onSelectSsh = onSelect,
            onRequestWeb = { pendingWebUrl = it },
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
                }) { Text("打开") }
            },
            dismissButton = { TextButton(onClick = { pendingWebUrl = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DiscoveryDeviceCard(
    device: DiscoveredDevice,
    mode: ScanMode,
    hosts: List<HostProfile>,
    onSelect: (String, String, Int, Long?) -> Unit,
    onOpenDetails: () -> Unit,
) {
    val modifier = if (mode == ScanMode.GENERAL) Modifier.clickable(onClick = onOpenDetails) else Modifier
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .72f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(device.displayName ?: device.address, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (device.displayName != null) Text(device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                if (mode == ScanMode.SSH) {
                    val best = device.bestConfidence
                    SshStatusBadge(sshConfidenceLabel(best), if (best == SshConfidence.PORT_OPEN) SshStatusTone.WARNING else SshStatusTone.CONNECTED)
                } else {
                    SshStatusBadge(
                        "${deviceKindLabel(device.classification.kind)} · ${shortConfidenceLabel(device.classification.confidence)}",
                        classificationTone(device.classification.confidence),
                    )
                }
            }
            if (mode == ScanMode.GENERAL) {
                Text(
                    if (device.services.isEmpty()) "ARP 缓存 · 在线状态未确认" else serviceSummary(device),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DiscoveryMetadata(device)
            if (mode == ScanMode.SSH) {
                device.endpoints.values.sortedBy(DiscoveredService::port).forEach { endpoint ->
                    SshEndpointAction(device, endpoint, hosts, onSelect)
                }
            } else {
                Text("点击查看服务与操作", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailsSheet(
    device: DiscoveredDevice,
    hosts: List<HostProfile>,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSelectSsh: (String, String, Int, Long?) -> Unit,
    onRequestWeb: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(device.displayName ?: device.address, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(device.address, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SshStatusBadge(deviceKindLabel(device.classification.kind), classificationTone(device.classification.confidence))
                Text(confidenceLabel(device.classification.confidence), style = MaterialTheme.typography.labelMedium)
            }
            DiscoveryMetadata(device)
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("正在读取受限的 UPnP 设备描述…")
                }
            }
            error?.let { MessageCard(it, error = true) }
            device.description?.let { description ->
                val details = listOfNotNull(
                    description.manufacturer?.let { "厂商：$it" },
                    description.modelName?.let { "型号：$it${description.modelNumber?.let { number -> " $number" }.orEmpty()}" },
                    description.deviceType?.let { "UPnP 类型：$it" },
                )
                details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            SshSectionHeader("发现的服务", summary = "${device.services.size}")
            if (device.services.isEmpty()) {
                Text("仅存在 ARP 缓存记录，设备当前是否在线尚未确认。", style = MaterialTheme.typography.bodySmall)
            }
            device.services.values.sortedWith(compareBy(DiscoveredService::port, DiscoveredService::kind)).forEach { service ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${service.kind.name} · ${device.address}:${service.port}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        service.banner?.raw?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (service.kind == ServiceKind.SSH) {
                            val existing = findExistingHost(device, service, hosts)
                            TextButton(onClick = {
                                onSelectSsh(device.displayName ?: device.address, device.address, service.port, existing?.id)
                            }) { Text(if (existing == null) "添加 SSH 主机" else "打开已添加主机") }
                        }
                        webUrlFor(device.address, service)?.let { url ->
                            TextButton(onClick = { onRequestWeb(url) }) {
                                Icon(Icons.Default.Language, null)
                                Text(" 打开 Web")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryMetadata(device: DiscoveredDevice) {
    Text(
        "来源：" + device.sources.sortedBy(DiscoverySource::ordinal).joinToString(" · ", transform = ::sourceLabel),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (device.macAddress != null) {
        Text(
            listOfNotNull("MAC ${device.macAddress}", device.vendor).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SshEndpointAction(
    device: DiscoveredDevice,
    endpoint: DiscoveredService,
    hosts: List<HostProfile>,
    onSelect: (String, String, Int, Long?) -> Unit,
) {
    val existing = findExistingHost(device, endpoint, hosts)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            onSelect(device.displayName ?: device.address, device.address, endpoint.port, existing?.id)
        },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${device.address}:${endpoint.port}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                endpoint.banner?.raw?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(if (existing == null) "添加" else "已添加", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}

private fun findExistingHost(device: DiscoveredDevice, service: DiscoveredService, hosts: List<HostProfile>): HostProfile? =
    hosts.firstOrNull { host ->
        host.port == service.port && (
            host.hostname.trim().equals(device.address, ignoreCase = true) ||
                device.mdnsName?.let { host.hostname.trim().equals(it, ignoreCase = true) } == true
            )
    }

private fun serviceSummary(device: DiscoveredDevice): String = device.services.values
    .sortedWith(compareBy(DiscoveredService::port, DiscoveredService::kind))
    .joinToString(" · ") { "${it.kind.name}:${it.port}" }

private fun sourceLabel(source: DiscoverySource): String = when (source) {
    DiscoverySource.TCP -> "TCP"
    DiscoverySource.MDNS -> "mDNS"
    DiscoverySource.SSDP -> "SSDP"
    DiscoverySource.ARP -> "ARP"
    DiscoverySource.DEVICE_DESCRIPTION -> "设备描述"
}

private fun sshConfidenceLabel(value: SshConfidence): String = when (value) {
    SshConfidence.BANNER_CONFIRMED -> "SSH 已确认"
    SshConfidence.MDNS_ADVERTISED -> "mDNS SSH"
    SshConfidence.PORT_OPEN -> "端口开放"
}

private fun deviceKindLabel(value: DeviceKind): String = when (value) {
    DeviceKind.ROUTER -> "路由器"
    DeviceKind.COMPUTER -> "电脑"
    DeviceKind.PRINTER -> "打印机"
    DeviceKind.MEDIA_DEVICE -> "媒体设备"
    DeviceKind.IOT -> "IoT"
    DeviceKind.UNKNOWN -> "未知设备"
}

private fun confidenceLabel(value: ClassificationConfidence): String = when (value) {
    ClassificationConfidence.HIGH -> "分类置信度：高"
    ClassificationConfidence.MEDIUM -> "分类置信度：中"
    ClassificationConfidence.LOW -> "分类置信度：低"
}

private fun shortConfidenceLabel(value: ClassificationConfidence): String = when (value) {
    ClassificationConfidence.HIGH -> "高"
    ClassificationConfidence.MEDIUM -> "中"
    ClassificationConfidence.LOW -> "低"
}

private fun classificationTone(value: ClassificationConfidence): SshStatusTone = when (value) {
    ClassificationConfidence.HIGH -> SshStatusTone.CONNECTED
    ClassificationConfidence.MEDIUM -> SshStatusTone.WARNING
    ClassificationConfidence.LOW -> SshStatusTone.WAITING
}

@Composable
private fun MessageCard(message: String, error: Boolean) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (error) Icons.Default.Warning else Icons.Default.Router,
                null,
                tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(message, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
