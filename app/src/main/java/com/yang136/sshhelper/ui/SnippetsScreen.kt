package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yang136.sshhelper.data.CommandSnippet
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.ui.design.SshCenteredList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(
    snippets: List<CommandSnippet>,
    hosts: List<HostProfile>,
    onSave: (CommandSnippet, (String?) -> Unit) -> Unit,
    onDelete: (CommandSnippet) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<CommandSnippet?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CommandSnippet?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        contentColor = imageAwareContentColor(),
        topBar = {
            TopAppBar(
                title = { Text("快捷命令") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "添加命令") } },
            )
        },
    ) { padding ->
        if (snippets.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("还没有快捷命令", style = MaterialTheme.typography.titleMedium)
                Text("可创建全局命令或主机专属命令", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { adding = true }, modifier = Modifier.padding(top = 16.dp)) { Text("添加命令") }
            }
        } else {
            SshCenteredList(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(snippets, key = CommandSnippet::id) { snippet ->
                    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(snippet.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    hosts.firstOrNull { it.id == snippet.hostId }?.name ?: "全局 · ${snippet.groupName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(snippet.command, fontFamily = FontFamily.Monospace, maxLines = 2)
                            }
                            IconButton(onClick = { editing = snippet }) { Icon(Icons.Default.Edit, "编辑") }
                            IconButton(onClick = { deleting = snippet }) { Icon(Icons.Default.Delete, "删除") }
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        SnippetEditorDialog(
            initial = editing ?: CommandSnippet(title = "", command = ""),
            hosts = hosts,
            onDismiss = { adding = false; editing = null },
            onSave = { snippet, result ->
                onSave(snippet) { error ->
                    result(error)
                    if (error == null) { adding = false; editing = null }
                }
            },
        )
    }
    deleting?.let { snippet ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除快捷命令？") },
            text = { Text("将删除“${snippet.title}”。") },
            confirmButton = { TextButton(onClick = { onDelete(snippet); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SnippetEditorDialog(
    initial: CommandSnippet,
    hosts: List<HostProfile>,
    onDismiss: () -> Unit,
    onSave: (CommandSnippet, (String?) -> Unit) -> Unit,
) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var command by remember(initial.id) { mutableStateOf(initial.command) }
    var group by remember(initial.id) { mutableStateOf(initial.groupName) }
    var hostId by remember(initial.id) { mutableStateOf(initial.hostId) }
    var immediate by remember(initial.id) { mutableStateOf(initial.executeImmediately) }
    var sortOrder by remember(initial.id) { mutableStateOf(initial.sortOrder.toString()) }
    var hostMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "添加快捷命令" else "编辑快捷命令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it; error = null }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
                OutlinedTextField(group, { group = it }, Modifier.fillMaxWidth(), label = { Text("分组") }, singleLine = true)
                OutlinedTextField(
                    sortOrder,
                    { sortOrder = it.filter { character -> character.isDigit() || character == '-' } },
                    Modifier.fillMaxWidth(),
                    label = { Text("排序值（小的在前）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(command, { command = it; if (it.contains('\n')) immediate = false }, Modifier.fillMaxWidth(), label = { Text("命令") }, minLines = 3, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = hostId == null, onClick = { hostId = null }, label = { Text("全局") })
                    FilterChip(selected = hostId != null, onClick = { if (hosts.isNotEmpty()) hostMenu = true }, label = { Text(hosts.firstOrNull { it.id == hostId }?.name ?: "选择主机") })
                    DropdownMenu(expanded = hostMenu, onDismissRequest = { hostMenu = false }) {
                        hosts.forEach { host -> DropdownMenuItem(text = { Text(host.name) }, onClick = { hostId = host.id; hostMenu = false }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(immediate, { immediate = it }, enabled = !command.contains('\n'))
                    Text("确认后立即执行（单行命令）")
                }
                Text("变量：\${host}  \${user}  \${port}  \${profile}  \${input:名称}", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(title = title, command = command, groupName = group, hostId = hostId, executeImmediately = immediate, sortOrder = sortOrder.toIntOrNull() ?: 0)) { error = it }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
