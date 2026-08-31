package com.yang136.sshhelper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yang136.sshhelper.ui.design.SshCenteredList
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshTopAppBar

private data class NetworkTool(val title: String, val summary: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun NetworkToolsScreen(
    onNetworkDiagnostics: () -> Unit,
    onLanDiscovery: () -> Unit,
    onPortScanner: () -> Unit,
    onDiagnosticLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tools = listOf(
        NetworkTool("网络诊断", "DNS、TCP 延迟、网络接口与 SSH Banner", Icons.Default.NetworkCheck, onNetworkDiagnostics),
        NetworkTool("局域网发现", "发现设备、服务、mDNS、SSDP 与 ARP 信息", Icons.Default.Devices, onLanDiscovery),
        NetworkTool("Port Scanner", "单目标 TCP Connect Scan、Banner 与服务识别", Icons.Default.Radar, onPortScanner),
        NetworkTool("诊断记录", "查看并导出连接、扫描和断连时间线", Icons.AutoMirrored.Filled.FactCheck, onDiagnosticLogs),
    )
    Scaffold(
        modifier = modifier,
        containerColor = imageAwareScaffoldColor(),
        contentColor = imageAwareContentColor(),
        topBar = { SshTopAppBar("工具", subtitle = "网络探测与连接诊断") },
    ) { padding ->
        SshCenteredList(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SshSectionHeader("网络工具", summary = "${tools.size}") }
            items(tools, key = NetworkTool::title) { tool ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = tool.onClick),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(tool.title, fontWeight = FontWeight.SemiBold)
                            Text(tool.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
