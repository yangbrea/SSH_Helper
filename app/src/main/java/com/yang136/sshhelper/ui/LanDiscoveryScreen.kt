package com.yang136.sshhelper.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.yang136.sshhelper.discovery.DiscoveredSshDevice
import com.yang136.sshhelper.discovery.DiscoverySource
import com.yang136.sshhelper.discovery.DiscoveryStatus
import com.yang136.sshhelper.discovery.SshConfidence
import com.yang136.sshhelper.discovery.SshEndpoint
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
    val app = LocalContext.current.applicationContext as SshHelperApplication
    val vm: DiscoveryViewModel = viewModel(factory = DiscoveryViewModel.factory(app.container))
    val state by vm.state.collectAsStateWithLifecycle()
    var networkMenu by remember { mutableStateOf(false) }
    val requestBack = { vm.cancelScan(); onBack() }
    BackHandler(onBack = requestBack)

    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        topBar = {
            SshTopAppBar(
                title = "发现 SSH 主机",
                subtitle = when (state.status) {
                    DiscoveryStatus.SCANNING -> "正在探测 ${state.completedProbes}/${state.totalProbes}"
                    DiscoveryStatus.COMPLETED -> "发现 ${state.devices.size} 台候选主机"
                    else -> "TCP · SSH Banner · mDNS · ARP"
                },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (state.status != DiscoveryStatus.SCANNING) {
                        IconButton(onClick = vm::refreshNetworks) { Icon(Icons.Default.Refresh, "刷新网络") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SshSectionHeader("扫描设置", summary = "最多 1024 个地址 · 16 个端口")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val selected = state.networks.firstOrNull { it.id == state.selectedNetworkId }
                    if (state.networks.size > 1) {
                        Box {
                            OutlinedButton(onClick = { networkMenu = true }, modifier = Modifier.fillMaxWidth()) {
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
                        label = { Text("SSH 端口") },
                        supportingText = { Text("逗号或空格分隔；mDNS 广播端口会自动探测") },
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
            state.error?.let { error ->
                item { MessageCard(error, error = true) }
            }
            state.notice?.let { notice ->
                item { MessageCard(notice, error = false) }
            }
            if (state.devices.isNotEmpty()) {
                item { SshSectionHeader("发现结果", summary = "${state.devices.size}") }
                items(state.devices, key = { "${it.networkId}-${it.address}" }) { device ->
                    DiscoveryDeviceCard(device, hosts, onSelect)
                }
            } else if (state.status in setOf(DiscoveryStatus.COMPLETED, DiscoveryStatus.CANCELLED, DiscoveryStatus.NO_NETWORK)) {
                item {
                    Box(Modifier.fillParentMaxHeight(.35f), contentAlignment = Alignment.Center) {
                        SshEmptyState(
                            icon = Icons.Default.Devices,
                            title = if (state.status == DiscoveryStatus.NO_NETWORK) "没有可扫描的局域网" else "未发现 SSH 主机",
                            description = if (state.status == DiscoveryStatus.CANCELLED) "扫描已取消，可调整范围后重试" else "确认设备在线、SSH 已启动，或添加自定义端口",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryDeviceCard(
    device: DiscoveredSshDevice,
    hosts: List<HostProfile>,
    onSelect: (String, String, Int, Long?) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .72f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(device.displayName ?: device.address, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (device.displayName != null) Text(device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                val best = device.bestConfidence
                SshStatusBadge(
                    label = when (best) {
                        SshConfidence.BANNER_CONFIRMED -> "SSH 已确认"
                        SshConfidence.MDNS_ADVERTISED -> "mDNS SSH"
                        SshConfidence.PORT_OPEN -> "端口开放"
                    },
                    tone = if (best == SshConfidence.PORT_OPEN) SshStatusTone.WARNING else SshStatusTone.CONNECTED,
                )
            }
            Text(
                "来源：" + device.sources.sortedBy(DiscoverySource::ordinal).joinToString(" · ") {
                    when (it) {
                        DiscoverySource.TCP -> "TCP"
                        DiscoverySource.MDNS -> "mDNS"
                        DiscoverySource.SSDP -> "SSDP"
                        DiscoverySource.ARP -> "ARP"
                        DiscoverySource.DEVICE_DESCRIPTION -> "设备描述"
                    }
                },
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
            device.endpoints.values.sortedBy(SshEndpoint::port).forEach { endpoint ->
                val existing = hosts.firstOrNull { host ->
                    host.port == endpoint.port && host.hostname.trim().equals(device.address, ignoreCase = true)
                }
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
        }
    }
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
