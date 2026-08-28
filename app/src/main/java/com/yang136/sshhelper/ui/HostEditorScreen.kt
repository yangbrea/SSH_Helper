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
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.ui.text.font.FontFamily
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
    val generatedKey by vm.generatedKey.collectAsStateWithLifecycle()
    val documentAccessEnabled by vm.documentAccessEnabled.collectAsStateWithLifecycle()
    val vaultState by app.container.credentialVault.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDiscard by remember { mutableStateOf(false) }
    var showGenerateKeyDialog by remember { mutableStateOf(false) }
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
            ProxySection(
                proxyType = state.proxyType,
                proxyHost = state.proxyHost,
                proxyPort = state.proxyPort,
                proxyUsername = state.proxyUsername,
                proxyPassword = state.proxyPassword,
                onChange = { update -> vm.update(update) },
            )
            if (state.rememberCredential && state.authType == AuthType.PASSWORD) {
                OutlinedTextField(state.password, { vm.update { s -> s.copy(password = it) } }, Modifier.fillMaxWidth(), label = { Text(if (hostId == 0L) "密码" else "新密码（留空则不修改）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            if (state.authType == AuthType.PRIVATE_KEY) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { keyPicker.launch(arrayOf("application/*", "text/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Key, null)
                        Text(if (state.privateKeyName == null) " 选择私钥文件" else " ${state.privateKeyName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { showGenerateKeyDialog = true }, modifier = Modifier.weight(1f)) {
                        Text("生成密钥对")
                    }
                }
                if (state.rememberCredential) {
                    OutlinedTextField(state.passphrase, { vm.update { s -> s.copy(passphrase = it) } }, Modifier.fillMaxWidth(), label = { Text("私钥口令（可选）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("在系统文件管理器中显示", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                hostId == 0L -> "请先保存主机，再单独授权其 SFTP 用户主目录"
                                state.isDirty -> "请先保存当前修改，再更改系统文件访问授权"
                                !state.rememberCredential -> "需先启用并保存登录凭据"
                                else -> "授权独立于应用保险库；设备锁定时不可访问，关闭后立即撤销"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = documentAccessEnabled,
                        enabled = hostId != 0L && !state.isDirty && state.rememberCredential,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                vm.setDocumentAccess(false)
                            } else {
                                val grant: () -> Unit = { vm.setDocumentAccess(true); Unit }
                                if (vaultState == VaultState.Locked) onUnlockVault(grant) else grant()
                            }
                        },
                    )
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

    if (showGenerateKeyDialog) {
        AlertDialog(
            onDismissRequest = { showGenerateKeyDialog = false },
            title = { Text("生成 ed25519 密钥对？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将在本机生成密钥对。私钥只保存在本应用，保存主机时由凭据保险库加密；公钥需要你手动添加到服务器。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showGenerateKeyDialog = false
                    vm.generateKeyPair()
                }) { Text("生成") }
            },
            dismissButton = { TextButton(onClick = { showGenerateKeyDialog = false }) { Text("取消") } },
        )
    }

    generatedKey?.let { key ->
        AlertDialog(
            onDismissRequest = { vm.clearGeneratedKey() },
            title = { Text("公钥已生成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("把下面这行添加到服务器的 ~/.ssh/authorized_keys：")
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            key.publicKey,
                            Modifier.padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("指纹 ${key.fingerprint}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("私钥已作为本主机的认证凭据，保存主机时加密存入保险库。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SSH public key", key.publicKey))
                    vm.clearGeneratedKey()
                }) { Text("复制公钥") }
            },
            dismissButton = { TextButton(onClick = { vm.clearGeneratedKey() }) { Text("完成") } },
        )
    }
}

@Composable
private fun ProxySection(
    proxyType: com.yang136.sshhelper.data.ProxyType?,
    proxyHost: String,
    proxyPort: String,
    proxyUsername: String,
    proxyPassword: String,
    onChange: ((EditorState) -> EditorState) -> Unit,
) {
    val enabled = proxyType != null
    var expanded by remember { mutableStateOf(false) }

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
                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("网络代理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Text(
                        when (proxyType) {
                            null -> "直连"
                            com.yang136.sshhelper.data.ProxyType.HTTP -> "HTTP 代理 · $proxyHost:$proxyPort"
                            com.yang136.sshhelper.data.ProxyType.SOCKS5 -> "SOCKS5 代理 · $proxyHost:$proxyPort"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开")
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            null to "直连",
                            com.yang136.sshhelper.data.ProxyType.HTTP to "HTTP",
                            com.yang136.sshhelper.data.ProxyType.SOCKS5 to "SOCKS5",
                        ).forEach { (type, label) ->
                            FilterChip(
                                selected = proxyType == type,
                                onClick = { onChange { it.copy(proxyType = type) } },
                                label = { Text(label) },
                            )
                        }
                    }
                    if (enabled) {
                        OutlinedTextField(proxyHost, { onChange { s -> s.copy(proxyHost = it) } }, Modifier.fillMaxWidth(), label = { Text("代理服务器地址") }, placeholder = { Text("proxy.example.com 或 192.168.1.1") }, singleLine = true)
                        OutlinedTextField(proxyPort, { onChange { s -> s.copy(proxyPort = it.filter(Char::isDigit).take(5)) } }, Modifier.fillMaxWidth(), label = { Text("代理端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(proxyUsername, { onChange { s -> s.copy(proxyUsername = it) } }, Modifier.fillMaxWidth(), label = { Text("代理用户名（可选）") }, singleLine = true)
                        if (proxyUsername.isNotBlank()) {
                            OutlinedTextField(proxyPassword, { onChange { s -> s.copy(proxyPassword = it) } }, Modifier.fillMaxWidth(), label = { Text("代理密码（可选，保存时加密）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        }
                        Text("代理作用于手机到本主机（及跳板机）的直连段；已建立的跳板隧道内部不会再次套用代理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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
