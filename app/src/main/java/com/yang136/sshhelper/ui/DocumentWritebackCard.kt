package com.yang136.sshhelper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.DocumentWritebackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DocumentWritebackCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as SshHelperApplication
    val manager = app.container.documentAccessManager
    val items by manager.writebacks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<DocumentWritebackEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val item = pendingExport
        pendingExport = null
        if (uri != null && item != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("无法创建导出文件")
                    manager.exportWriteback(item.id, output)
                }
            }.onFailure { error = "导出失败：${it.message ?: "未知错误"}" }
        }
    }
    if (items.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("待处理的系统文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("远端冲突或网络失败时不会覆盖服务器；请逐项重试、导出本地副本或丢弃。", style = MaterialTheme.typography.bodySmall)
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.remotePath, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(item.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { manager.retryWriteback(item.id) }
                                    .onFailure { error = "重试失败：${it.message ?: "未知错误"}" }
                            }
                        }) { Text("重试") }
                        OutlinedButton(onClick = {
                            pendingExport = item
                            export.launch(item.remotePath.substringAfterLast('/').ifBlank { "sshhelper-recovery" })
                        }) { Text("导出") }
                        OutlinedButton(onClick = { scope.launch { manager.discardWriteback(item.id) } }) { Text("丢弃") }
                    }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
