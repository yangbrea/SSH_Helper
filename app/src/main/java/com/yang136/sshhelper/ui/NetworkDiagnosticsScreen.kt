package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.diagnostics.DiagnosticConclusionKind
import com.yang136.sshhelper.diagnostics.DiagnosticSample
import com.yang136.sshhelper.diagnostics.NetworkSnapshot
import com.yang136.sshhelper.ui.design.SshCenteredList
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusBadge
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticsScreen(hostId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SshHelperApplication
    val vm: NetworkDiagnosticsViewModel = viewModel(
        key = "network-diagnostics-$hostId",
        factory = NetworkDiagnosticsViewModel.factory(app.container, hostId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var networkMenu by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        contentColor = imageAwareContentColor(),
        topBar = {
            SshTopAppBar(
                title = "网络诊断",
                subtitle = state.savedTargetName?.let { "主机 · $it" } ?: "网络状态与 TCP/SSH 延迟",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(
                        onClick = vm::refreshNetworks,
                        enabled = state.status != NetworkDiagnosticsStatus.RUNNING,
                    ) { Icon(Icons.Default.Refresh, "刷新网络") }
                },
            )
        },
    ) { padding ->
        SshCenteredList(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SshSectionHeader("当前网络", summary = state.networks.size.toString())
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box {
                            OutlinedButton(
                                onClick = { networkMenu = true },
                                enabled = state.networks.isNotEmpty() && state.status != NetworkDiagnosticsStatus.RUNNING,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val selected = state.networks.firstOrNull { it.id == state.selectedNetworkId }
                                Text(selected?.let { if (it.isDefault) "${it.label}（默认）" else it.label } ?: "没有可用网络")
                            }
                            DropdownMenu(
                                expanded = networkMenu,
                                onDismissRequest = { networkMenu = false },
                            ) {
                                state.networks.forEach { network ->
                                    DropdownMenuItem(
                                        text = { Text(if (network.isDefault) "${network.label}（默认）" else network.label) },
                                        onClick = { networkMenu = false; vm.selectNetwork(network.id) },
                                    )
                                }
                            }
                        }
                        state.snapshot?.let { NetworkSnapshotContent(it) }
                    }
                }
            }

            item { SshSectionHeader("诊断目标") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(state.targetLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        state.routeSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = state.hostnameInput,
                                onValueChange = vm::updateHostname,
                                label = { Text("域名或 IP") },
                                readOnly = state.targetLocked,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = state.portInput,
                                onValueChange = vm::updatePort,
                                label = { Text("端口") },
                                readOnly = state.targetLocked,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(.42f),
                            )
                        }
                        state.routeLimitation?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (state.status == NetworkDiagnosticsStatus.RUNNING) {
                            LinearProgressIndicator(
                                progress = { state.completedSamples / NETWORK_DIAGNOSTIC_SAMPLE_COUNT.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(onClick = vm::cancelTest, modifier = Modifier.fillMaxWidth()) {
                                Text("取消诊断 · ${state.completedSamples}/$NETWORK_DIAGNOSTIC_SAMPLE_COUNT")
                            }
                        } else {
                            Button(onClick = vm::startTest, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.NetworkCheck, null)
                                Text("开始诊断", Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }

            if (state.resolvedAddresses.isNotEmpty() || state.samples.isNotEmpty()) {
                item { SshSectionHeader("测试过程", summary = "${state.completedSamples}/$NETWORK_DIAGNOSTIC_SAMPLE_COUNT") }
                item {
                    state.dnsDurationMillis?.let { duration ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("DNS · ${formatMillis(duration)}", fontWeight = FontWeight.Medium)
                                Text(state.resolvedAddresses.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                items(state.samples, key = DiagnosticSample::index) { sample -> DiagnosticSampleCard(sample) }
            }

            state.report?.let { report ->
                item { SshSectionHeader("汇总") }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryLine("TCP 延迟", listOfNotNull(
                                report.minimumMillis?.let { "最小 ${formatMillis(it)}" },
                                report.averageMillis?.let { "平均 ${formatMillis(it)}" },
                                report.maximumMillis?.let { "最大 ${formatMillis(it)}" },
                            ).joinToString(" · ").ifBlank { "无成功样本" })
                            SummaryLine("连接失败率", "${report.failureRatePercent}%")
                            report.banner?.let { SummaryLine("SSH Banner", it.raw) }
                        }
                    }
                }
            }

            state.conclusion?.let { conclusion ->
                item { SshSectionHeader("诊断结论") }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SshStatusBadge(conclusion.title, conclusion.kind.toTone())
                            Text(conclusion.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkSnapshotContent(snapshot: NetworkSnapshot) {
    val internet = when {
        snapshot.captivePortal -> "需要登录"
        snapshot.validated -> "已验证"
        snapshot.hasInternetCapability -> "未验证"
        else -> "仅本地网络"
    }
    SummaryLine("接口", snapshot.interfaceName ?: "未知")
    SummaryLine("地址", snapshot.addresses.joinToString().ifBlank { "无" })
    SummaryLine("网关", snapshot.gateways.joinToString().ifBlank { "无" })
    SummaryLine("DNS", snapshot.dnsServers.joinToString().ifBlank { "无" })
    SummaryLine("互联网", internet)
    SummaryLine("计费", if (snapshot.metered) "按流量计费" else "不按流量计费")
    snapshot.mtu?.let { SummaryLine("MTU", it.toString()) }
    if (snapshot.privateDnsActive) SummaryLine("私人 DNS", snapshot.privateDnsServerName ?: "自动")
    snapshot.httpProxy?.let { SummaryLine("系统代理", it) }
    if (!snapshot.validated && !snapshot.captivePortal) {
        Text("未验证互联网不代表局域网 SSH 不可用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagnosticSampleCard(sample: DiagnosticSample) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("第 ${sample.index} 次", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            when (sample) {
                is DiagnosticSample.Success -> Column(horizontalAlignment = Alignment.End) {
                    SshStatusBadge(formatMillis(sample.durationMillis), SshStatusTone.CONNECTED)
                    Text(sample.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is DiagnosticSample.Failure -> SshStatusBadge(sample.message, SshStatusTone.ERROR)
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(.32f), fontWeight = FontWeight.Medium)
        Text(value, Modifier.weight(.68f), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatMillis(value: Double): String = String.format(Locale.US, "%.1f ms", value)

private fun DiagnosticConclusionKind.toTone(): SshStatusTone = when (this) {
    DiagnosticConclusionKind.HEALTHY -> SshStatusTone.CONNECTED
    DiagnosticConclusionKind.PARTIAL_FAILURE, DiagnosticConclusionKind.NON_SSH_SERVICE -> SshStatusTone.WARNING
    DiagnosticConclusionKind.NO_NETWORK, DiagnosticConclusionKind.PERMISSION_DENIED,
    DiagnosticConclusionKind.DNS_FAILURE, DiagnosticConclusionKind.TCP_FAILURE -> SshStatusTone.ERROR
}
