package com.yang136.sshhelper.ui

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.security.VaultState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditorScreen(hostId: Long, onUnlockVault: ((() -> Unit) -> Unit), onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SshHelperApplication
    val vm: HostEditorViewModel = viewModel(factory = HostEditorViewModel.factory(app.container, hostId))
    val state by vm.state.collectAsStateWithLifecycle()
    val vaultState by app.container.credentialVault.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestBack = {
        if (state.isDirty) confirmDiscard = true else onBack()
    }
    BackHandler(onBack = requestBack)
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "private_key"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取私钥")
                require(bytes.size <= 1024 * 1024) { "私钥文件不能超过 1MB" }
                vm.setPrivateKey(name, bytes)
            }.onFailure { vm.update { current -> current.copy(error = it.message ?: "无法读取私钥") } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (hostId == 0L) "添加主机" else "编辑主机") },
                navigationIcon = { IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(state.name, { vm.update { s -> s.copy(name = it) } }, Modifier.fillMaxWidth(), label = { Text("连接名称") }, singleLine = true)
            OutlinedTextField(state.hostname, { vm.update { s -> s.copy(hostname = it) } }, Modifier.fillMaxWidth(), label = { Text("服务器地址") }, placeholder = { Text("192.168.1.10 或 example.com") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(state.port, { vm.update { s -> s.copy(port = it.filter(Char::isDigit)) } }, Modifier.weight(.35f), label = { Text("端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(state.username, { vm.update { s -> s.copy(username = it) } }, Modifier.weight(.65f), label = { Text("用户名") }, singleLine = true)
            }
            Text("认证方式", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = state.authType == AuthType.PASSWORD, onClick = { vm.update { it.copy(authType = AuthType.PASSWORD) } }, label = { Text("密码") })
                FilterChip(selected = state.authType == AuthType.PRIVATE_KEY, onClick = { vm.update { it.copy(authType = AuthType.PRIVATE_KEY) } }, label = { Text("私钥") }, leadingIcon = { Icon(Icons.Default.Key, null) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.rememberCredential, { value -> vm.update { it.copy(rememberCredential = value) } })
                Text("使用 Android Keystore 安全保存凭据")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.autoReconnect, { value -> vm.update { it.copy(autoReconnect = value) } })
                Column {
                    Text("连接意外断开时自动重连")
                    Text("最多尝试 3 次；认证失败不会自动重试", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val allHosts = vm.hosts.collectAsStateWithLifecycle().value
            RouteSection(
                state = state,
                hosts = allHosts,
                onJumpChange = { id -> vm.update { it.copy(jumpHostId = id) } },
            )
            if (state.rememberCredential && state.authType == AuthType.PASSWORD) {
                OutlinedTextField(state.password, { vm.update { s -> s.copy(password = it) } }, Modifier.fillMaxWidth(), label = { Text(if (hostId == 0L) "密码" else "新密码（留空则不修改）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            if (state.authType == AuthType.PRIVATE_KEY) {
                Button(onClick = { keyPicker.launch(arrayOf("application/*", "text/*")) }) {
                    Icon(Icons.Default.Key, null)
                    Text(if (state.privateKeyName == null) " 选择私钥文件" else " ${state.privateKeyName}")
                }
                if (state.rememberCredential) {
                    OutlinedTextField(state.passphrase, { vm.update { s -> s.copy(passphrase = it) } }, Modifier.fillMaxWidth(), label = { Text("私钥口令（可选）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val save: () -> Unit = { scope.launch { if (vm.save() != null) onBack() }; Unit }
                    if (state.rememberCredential && vaultState == VaultState.Locked) onUnlockVault(save) else save()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("保存") }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前修改尚未保存，返回后将丢失这些内容。") },
            confirmButton = { TextButton(onClick = onBack) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
    }
}

@Composable
private fun RouteSection(
    state: EditorState,
    hosts: List<HostProfile>,
    onJumpChange: (Long?) -> Unit,
) {
    val jump = hosts.firstOrNull { it.id == state.jumpHostId }
    var expanded by remember { mutableStateOf(false) }
    var jumpMenuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Route, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("连接路由", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Text(
                        if (jump != null) "经 ${jump.name} 连接" else "直连目标机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开")
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.jumpHostId != null,
                            onCheckedChange = { enabled -> onJumpChange(if (enabled) state.jumpHostId else null) },
                        )
                        Text("通过跳板机连接", Modifier.padding(start = 10.dp))
                    }
                    if (state.jumpHostId != null) {
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { jumpMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    jump?.let { "${it.name}（${it.username}@${it.hostname}）" } ?: "选择跳板机",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DropdownMenu(expanded = jumpMenuOpen, onDismissRequest = { jumpMenuOpen = false }) {
                                hosts.filter { it.id != state.id && it.jumpHostId == null }.forEach { host ->
                                    DropdownMenuItem(
                                        text = { Text("${host.name}（${host.username}@${host.hostname}）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { onJumpChange(host.id); jumpMenuOpen = false },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("不使用跳板机") },
                                    onClick = { onJumpChange(null); jumpMenuOpen = false },
                                )
                            }
                        }
                        Text(
                            "手机 → ${jump?.name ?: "跳板机"} → ${state.name.ifBlank { "目标机" }}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "仅支持一层跳板；跳板机与目标机分别验证身份与主机指纹。跳板凭据保存在同一个保险库中，连接时按顺序提示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
