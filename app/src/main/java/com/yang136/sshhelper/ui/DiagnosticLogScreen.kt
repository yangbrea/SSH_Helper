package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.yang136.sshhelper.diagnosticlog.DiagnosticEvent
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventLevel
import com.yang136.sshhelper.diagnosticlog.DiagnosticTrace
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceStatus
import com.yang136.sshhelper.ui.design.SshCenteredList
import com.yang136.sshhelper.ui.design.SshEmptyState
import com.yang136.sshhelper.ui.design.SshInlineBanner
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusBadge
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun DiagnosticLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SshHelperApplication
    val vm: DiagnosticLogViewModel = viewModel(factory = DiagnosticLogViewModel.factory(app.container))
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val selected = state.selectedTrace
    val requestBack = { if (selected != null) vm.closeDetail() else onBack() }
    BackHandler(onBack = requestBack)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { vm.exportSelected(it) } ?: error("无法创建导出文件")
            }.onFailure { exportError = it.message ?: "导出失败" }
        }
    }

    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        contentColor = imageAwareContentColor(),
        topBar = {
            SshTopAppBar(
                title = selected?.target ?: "诊断记录",
                subtitle = selected?.let { "${it.source.displayName()} · ${it.status.displayName()}" } ?: "保留 30 天 · 最多 500 次",
                navigationIcon = { IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (selected != null) {
                        IconButton(onClick = { confirmExport = true }) { Icon(Icons.Default.Download, "导出") }
                        IconButton(onClick = vm::deleteSelected) { Icon(Icons.Default.Delete, "删除") }
                    } else if (state.traces.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, "清空") }
                    }
                },
            )
        },
    ) { padding ->
        SshCenteredList(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            exportError?.let { item { SshInlineBanner("导出失败", it, tone = SshStatusTone.ERROR) } }
            if (selected == null) {
                item { OutlinedTextField(state.query, vm::updateQuery, label = { Text("搜索目标、来源或结果") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                if (state.visibleTraces.isEmpty()) item { SshEmptyState(Icons.AutoMirrored.Filled.FactCheck, "暂无诊断记录", "建立 SSH 连接或运行 Port Scanner 后会在这里生成时间线") }
                items(state.visibleTraces, key = DiagnosticTrace::id) { trace -> TraceCard(trace) { vm.open(trace) } }
            } else {
                item { SshSectionHeader("事件时间线", summary = "${state.events.size}") }
                item {
                    Text("目标地址、IP、用户名和主机指纹可能出现在记录中；凭据与终端内容不会记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.events, key = DiagnosticEvent::sequence) { event -> EventCard(event) }
            }
        }
    }
    if (confirmExport && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("导出诊断记录？") },
            text = { Text("导出文件可能包含主机名、IP、用户名、算法和主机指纹，但不会包含密码、私钥或终端内容。") },
            confirmButton = { TextButton(onClick = { confirmExport = false; exporter.launch("ssh-helper-diagnostic-${selected.startedAt}.json") }) { Text("选择位置") } },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部诊断记录？") },
            text = { Text("此操作不可撤销，不会影响主机、凭据或其他设置。") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearAll() }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TraceCard(trace: DiagnosticTrace, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(trace.target ?: trace.source.displayName(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${trace.source.displayName()} · ${formatTraceTime(trace.startedAt)}", style = MaterialTheme.typography.bodySmall)
                trace.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            SshStatusBadge(trace.status.displayName(), trace.status.tone())
        }
    }
}

@Composable
private fun EventCard(event: DiagnosticEvent) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("+${event.elapsedMillis} ms", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                SshStatusBadge(event.stage.name, when (event.level) {
                    DiagnosticEventLevel.ERROR -> SshStatusTone.ERROR
                    DiagnosticEventLevel.WARNING -> SshStatusTone.WARNING
                    else -> SshStatusTone.CONNECTED
                })
            }
            Text(event.message, style = MaterialTheme.typography.bodyMedium)
            Text(listOfNotNull(event.hop?.name, event.code).joinToString(" · "), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.details.isNotEmpty()) Text(event.details.entries.joinToString(" · ") { "${it.key}=${it.value}" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun com.yang136.sshhelper.diagnosticlog.DiagnosticTraceSource.displayName() = when (this) {
    com.yang136.sshhelper.diagnosticlog.DiagnosticTraceSource.SSH_CONNECTION -> "SSH 连接"
    com.yang136.sshhelper.diagnosticlog.DiagnosticTraceSource.NETWORK_DIAGNOSTIC -> "网络诊断"
    com.yang136.sshhelper.diagnosticlog.DiagnosticTraceSource.PORT_SCAN -> "Port Scanner"
}

private fun DiagnosticTraceStatus.displayName() = when (this) {
    DiagnosticTraceStatus.RUNNING -> "运行中"; DiagnosticTraceStatus.SUCCEEDED -> "完成"
    DiagnosticTraceStatus.FAILED -> "失败"; DiagnosticTraceStatus.CANCELLED -> "已取消"; DiagnosticTraceStatus.ABORTED -> "已中止"
}

private fun DiagnosticTraceStatus.tone() = when (this) {
    DiagnosticTraceStatus.SUCCEEDED -> SshStatusTone.CONNECTED
    DiagnosticTraceStatus.RUNNING -> SshStatusTone.CONNECTING
    DiagnosticTraceStatus.FAILED -> SshStatusTone.ERROR
    DiagnosticTraceStatus.CANCELLED, DiagnosticTraceStatus.ABORTED -> SshStatusTone.WARNING
}

private val traceTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private fun formatTraceTime(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(traceTimeFormatter)
