package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.security.VaultState
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.DEFAULT_EXTRA_KEYS
import com.yang136.sshhelper.settings.DEFAULT_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.ExtraKeyId
import com.yang136.sshhelper.settings.MAX_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.MIN_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.ui.design.PreferenceAction
import com.yang136.sshhelper.ui.design.PreferenceGroup
import com.yang136.sshhelper.ui.design.PreferenceSwitch
import com.yang136.sshhelper.ui.design.SshInlineBanner
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SettingsDestination(val id: String, val title: String, val icon: ImageVector) {
    APPEARANCE("appearance", "外观与主题", Icons.Default.Palette),
    TERMINAL("terminal", "终端", Icons.Default.Terminal),
    AI("ai", "AI 助手", Icons.Default.AutoAwesome),
    CONNECTIONS("connections", "连接与后台", Icons.Default.NotificationsActive),
    SECURITY("security", "安全与凭据", Icons.Default.Security),
    DOCUMENTS("documents", "系统文件访问", Icons.Default.Storage),
    ABOUT("about", "关于", Icons.Default.Info),
    ;

    companion object {
        fun fromId(id: String?): SettingsDestination? = entries.firstOrNull { it.id == id }
    }
}

internal fun settingsSummary(
    destination: SettingsDestination,
    settings: AppSettings,
    vault: String,
    documentRoots: Int,
    writebacks: Int,
): String = when (destination) {
    SettingsDestination.APPEARANCE -> "${settings.themeMode.displayName()} · ${settings.themePreset.displayName()}"
    SettingsDestination.TERMINAL -> "${settings.terminalFontSize} px · ${settings.extraKeys.size} 个控制键"
    SettingsDestination.AI -> if (settings.aiApiKey.isBlank()) "未配置 API Key" else "${settings.aiModel} · 已配置"
    SettingsDestination.CONNECTIONS -> if (settings.forwardReconnectAfterLock) "允许活动隧道锁屏后重连" else "锁屏后等待解锁"
    SettingsDestination.SECURITY -> vault
    SettingsDestination.DOCUMENTS -> "$documentRoots 台主机已授权${if (writebacks > 0) " · $writebacks 个待恢复" else ""}"
    SettingsDestination.ABOUT -> "版本、功能与开源许可"
}

internal data class AiSettingsDraft(val baseUrl: String, val apiKey: String, val model: String) {
    fun isDirty(settings: AppSettings): Boolean =
        baseUrl != settings.aiBaseUrl || apiKey != settings.aiApiKey || model != settings.aiModel

    companion object {
        fun from(settings: AppSettings) = AiSettingsDraft(settings.aiBaseUrl, settings.aiApiKey, settings.aiModel)
    }
}

private data class ThemePreview(val preset: ThemePreset, val background: Color, val accent: Color)
private val themePreviews = listOf(
    ThemePreview(ThemePreset.OCEAN, Color(0xFF07131F), Color(0xFF22D3EE)),
    ThemePreview(ThemePreset.EMERALD, Color(0xFF06130E), Color(0xFF35E07F)),
    ThemePreview(ThemePreset.AMBER, Color(0xFF171007), Color(0xFFFFB84D)),
    ThemePreview(ThemePreset.VIOLET, Color(0xFF100A1D), Color(0xFFB388FF)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onThemePresetChange: (ThemePreset) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onExtraKeysChange: (List<ExtraKeyId>) -> Unit,
    onAiBaseUrlChange: (String) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiSendContextChange: (Boolean) -> Unit,
    onAiShowBubbleChange: (Boolean) -> Unit,
    onForwardReconnectAfterLockChange: (Boolean) -> Unit,
    vaultState: VaultState,
    canAuthenticate: Boolean,
    onEnableVault: () -> Unit,
    onUnlockVault: () -> Unit,
    onDisableVault: () -> Unit,
    onLockVault: () -> Unit,
    onClearUnavailableVault: () -> Unit,
    onBack: () -> Unit,
    initialDestination: SettingsDestination? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as SshHelperApplication
    val roots by app.container.documentAccessManager.roots.collectAsStateWithLifecycle()
    val writebacks by app.container.documentAccessManager.writebacks.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(initialDestination?.id) }
    val selected = SettingsDestination.fromId(selectedId)
    var confirmVaultReset by remember { mutableStateOf(false) }
    var confirmDiscardAi by remember { mutableStateOf(false) }
    var aiBaseUrl by rememberSaveable { mutableStateOf(settings.aiBaseUrl) }
    var aiApiKey by rememberSaveable { mutableStateOf(settings.aiApiKey) }
    var aiModel by rememberSaveable { mutableStateOf(settings.aiModel) }
    val aiDirty = aiBaseUrl != settings.aiBaseUrl || aiApiKey != settings.aiApiKey || aiModel != settings.aiModel

    LaunchedEffect(settings.aiBaseUrl, settings.aiApiKey, settings.aiModel) {
        if (!aiDirty) {
            aiBaseUrl = settings.aiBaseUrl
            aiApiKey = settings.aiApiKey
            aiModel = settings.aiModel
        }
    }

    fun leavePage() {
        if (selected == SettingsDestination.AI && aiDirty) confirmDiscardAi = true else selectedId = null
    }
    val handleBack = {
        if (selected == null) onBack() else leavePage()
    }
    BackHandler(onBack = handleBack)

    Scaffold(
        topBar = {
            SshTopAppBar(
                title = selected?.title ?: "设置",
                subtitle = selected?.let { "SSH Helper 偏好设置" },
                navigationIcon = { IconButton(onClick = handleBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (selected == SettingsDestination.AI && aiDirty) {
                        TextButton(onClick = {
                            onAiBaseUrlChange(aiBaseUrl)
                            onAiApiKeyChange(aiApiKey)
                            onAiModelChange(aiModel)
                        }) { Text("保存") }
                    }
                },
            )
        },
    ) { padding ->
        when (selected) {
            null -> SettingsHome(
                settings,
                vaultState.displayName(),
                roots.size,
                writebacks.size,
                Modifier.padding(padding),
            ) { selectedId = it.id }
            SettingsDestination.APPEARANCE -> AppearanceSettings(settings, onThemeModeChange, onThemePresetChange, Modifier.padding(padding))
            SettingsDestination.TERMINAL -> TerminalSettings(settings, onFontSizeChange, onExtraKeysChange, Modifier.padding(padding))
            SettingsDestination.AI -> AiSettings(settings, aiBaseUrl, { aiBaseUrl = it }, aiApiKey, { aiApiKey = it }, aiModel, { aiModel = it }, onAiSendContextChange, onAiShowBubbleChange, Modifier.padding(padding))
            SettingsDestination.CONNECTIONS -> ConnectionsSettings(settings, onForwardReconnectAfterLockChange, Modifier.padding(padding))
            SettingsDestination.SECURITY -> SecuritySettings(vaultState, canAuthenticate, onEnableVault, onUnlockVault, onDisableVault, onLockVault, { confirmVaultReset = true }, Modifier.padding(padding))
            SettingsDestination.DOCUMENTS -> DocumentsSettings(roots.size, writebacks.size, Modifier.padding(padding))
            SettingsDestination.ABOUT -> AboutSettings(Modifier.padding(padding))
        }
    }

    if (confirmDiscardAi) AlertDialog(
        onDismissRequest = { confirmDiscardAi = false },
        title = { Text("放弃 AI 配置修改？") },
        text = { Text("服务地址、API Key 或模型尚未保存。") },
        confirmButton = { TextButton(onClick = {
            aiBaseUrl = settings.aiBaseUrl; aiApiKey = settings.aiApiKey; aiModel = settings.aiModel
            confirmDiscardAi = false; selectedId = null
        }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscardAi = false }) { Text("继续编辑") } },
    )
    if (confirmVaultReset) AlertDialog(
        onDismissRequest = { confirmVaultReset = false },
        title = { Text("清除所有已保存凭据？") },
        text = { Text("此操作会永久删除全部已保存的密码和私钥，但保留主机配置与主机指纹。") },
        confirmButton = { TextButton(onClick = { confirmVaultReset = false; onClearUnavailableVault() }) { Text("永久清除") } },
        dismissButton = { TextButton(onClick = { confirmVaultReset = false }) { Text("取消") } },
    )
}

@Composable
private fun SettingsPage(modifier: Modifier = Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun SettingsHome(settings: AppSettings, vault: String, roots: Int, writebacks: Int, modifier: Modifier, onDestination: (SettingsDestination) -> Unit) {
    SettingsPage(modifier) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
                Text("控制你的工作环境", style = MaterialTheme.typography.headlineSmall)
                Text("外观、连接、安全与工具都集中在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsDestination.entries.forEach { destination ->
            item(destination.id) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    PreferenceAction(destination.icon, destination.title, settingsSummary(destination, settings, vault, roots, writebacks), { onDestination(destination) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettings(settings: AppSettings, onMode: (ThemeMode) -> Unit, onPreset: (ThemePreset) -> Unit, modifier: Modifier) {
    SettingsPage(modifier) {
        item { SshSectionHeader("显示模式", summary = settings.themeMode.displayName()) }
        item {
            PreferenceGroup {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { onMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        ) { Text(mode.displayName()) }
                    }
                }
            }
        }
        item { SshSectionHeader("品牌主题", summary = settings.themePreset.displayName()) }
        themePreviews.forEach { preview ->
            item(preview.preset.name) {
                val selected = settings.themePreset == preview.preset
                Card(
                    onClick = { onPreset(preview.preset) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).background(preview.background, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(22.dp).background(preview.accent, MaterialTheme.shapes.small))
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(preview.preset.displayName(), fontWeight = FontWeight.SemiBold)
                            Text("同时更新应用界面与终端配色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalSettings(settings: AppSettings, onFontSize: (Int) -> Unit, onKeys: (List<ExtraKeyId>) -> Unit, modifier: Modifier) {
    SettingsPage(modifier) {
        item { SshSectionHeader("终端字体", summary = "${settings.terminalFontSize} px") }
        item {
            PreferenceGroup {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("字号", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    TextButton(onClick = { onFontSize(DEFAULT_TERMINAL_FONT_SIZE) }) { Text("恢复默认") }
                }
                Slider(settings.terminalFontSize.toFloat(), { onFontSize(it.roundToInt()) }, valueRange = MIN_TERMINAL_FONT_SIZE.toFloat()..MAX_TERMINAL_FONT_SIZE.toFloat(), steps = MAX_TERMINAL_FONT_SIZE - MIN_TERMINAL_FONT_SIZE - 1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("$MIN_TERMINAL_FONT_SIZE"); Text("$MAX_TERMINAL_FONT_SIZE") }
            }
        }
        item { SshSectionHeader("控制键", summary = "长按拖动排序") }
        item {
            PreferenceGroup {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExtraKeyId.entries.forEach { key ->
                        FilterChip(key in settings.extraKeys, { onKeys(if (key in settings.extraKeys) settings.extraKeys - key else settings.extraKeys + key) }, { Text(key.label) })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                settings.extraKeys.forEachIndexed { index, key -> ReorderableKeyRow(index, key, settings.extraKeys, onKeys) }
                if (settings.extraKeys.isEmpty()) TextButton(onClick = { onKeys(DEFAULT_EXTRA_KEYS) }) { Text("恢复默认控制键") }
            }
        }
    }
}

@Composable
private fun ReorderableKeyRow(index: Int, key: ExtraKeyId, keys: List<ExtraKeyId>, onKeys: (List<ExtraKeyId>) -> Unit) {
    var dragged by remember { mutableFloatStateOf(0f) }
    Row(Modifier.fillMaxWidth().pointerInput(index, keys) {
        detectDragGesturesAfterLongPress(
            onDragEnd = { dragged = 0f },
            onDragCancel = { dragged = 0f },
        ) { change, amount ->
            change.consume(); dragged += amount.y
            if (abs(dragged) > 36f) {
                val target = if (dragged > 0) index + 1 else index - 1
                if (target in keys.indices) onKeys(keys.swap(index, target))
                dragged = 0f
            }
        }
    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.DragHandle, "长按拖动")
        Text(key.label, Modifier.weight(1f).padding(horizontal = 12.dp), fontFamily = FontFamily.Monospace)
        IconButton(onClick = { onKeys(keys - key) }) { Icon(Icons.Default.Close, "移除") }
    }
}

@Composable
private fun AiSettings(settings: AppSettings, baseUrl: String, onBaseUrl: (String) -> Unit, apiKey: String, onApiKey: (String) -> Unit, model: String, onModel: (String) -> Unit, onContext: (Boolean) -> Unit, onBubble: (Boolean) -> Unit, modifier: Modifier) {
    var showKey by remember { mutableStateOf(false) }
    SettingsPage(modifier) {
        item { SshInlineBanner("数据边界", "终端上下文会发送到你配置的 AI 服务；API Key 当前保存在本机 DataStore。", tone = SshStatusTone.WARNING) }
        item {
            PreferenceGroup {
                PreferenceSwitch("显示终端 AI 气泡", "在终端页面提供会话级助手入口", settings.aiShowBubble, onBubble)
                HorizontalDivider()
                PreferenceSwitch("发送最近终端输出", "最多发送最近约 6KB 输出作为上下文", settings.aiSendContext, onContext)
            }
        }
        item {
            PreferenceGroup {
                OutlinedTextField(baseUrl, onBaseUrl, Modifier.fillMaxWidth(), label = { Text("服务地址") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(apiKey, onApiKey, Modifier.fillMaxWidth(), label = { Text("API Key") }, singleLine = true, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "隐藏" else "显示") } })
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(model, onModel, Modifier.fillMaxWidth(), label = { Text("模型") }, singleLine = true)
            }
        }
    }
}

@Composable
private fun ConnectionsSettings(settings: AppSettings, onReconnect: (Boolean) -> Unit, modifier: Modifier) {
    SettingsPage(modifier) {
        item { BackgroundConnectionCard() }
        item {
            PreferenceGroup {
                PreferenceSwitch("锁屏后允许活动隧道自动重连", "凭据仅在隧道生命周期内保存在内存中", settings.forwardReconnectAfterLock, onReconnect)
            }
        }
    }
}

@Composable
private fun SecuritySettings(vault: VaultState, canAuthenticate: Boolean, onEnable: () -> Unit, onUnlock: () -> Unit, onDisable: () -> Unit, onLock: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    SettingsPage(modifier) {
        item { SshSectionHeader("凭据保险库", summary = vault.displayName()) }
        item {
            PreferenceGroup {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("设备级凭据保护", fontWeight = FontWeight.SemiBold)
                        Text("使用生物识别或系统锁屏保护密码和私钥", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (vault) {
                        VaultState.Disabled -> Button(onClick = onEnable, enabled = canAuthenticate) { Text("启用") }
                        VaultState.Locked -> Button(onClick = onUnlock, enabled = canAuthenticate) { Text("解锁") }
                        is VaultState.Unlocked -> OutlinedButton(onClick = onLock) { Text("锁定") }
                        is VaultState.Unavailable -> Button(onClick = onClear) { Text("清除") }
                    }
                }
                if (vault !is VaultState.Disabled) {
                    HorizontalDivider()
                    TextButton(onClick = onDisable, enabled = canAuthenticate) { Text("关闭保险库") }
                }
            }
        }
    }
}

@Composable
private fun DocumentsSettings(roots: Int, writebacks: Int, modifier: Modifier) {
    SettingsPage(modifier) {
        item { SshInlineBanner("系统文件根目录", "$roots 台主机已授权；授权独立于应用保险库，设备锁定时停止访问。", tone = if (writebacks > 0) SshStatusTone.WARNING else SshStatusTone.CONNECTED) }
        item { DocumentWritebackCard() }
        if (writebacks == 0) item { PreferenceGroup { Text("没有待处理的写回文件", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable
private fun AboutSettings(modifier: Modifier) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0.0" }
    SettingsPage(modifier) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Security, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text("SSH Helper", style = MaterialTheme.typography.headlineSmall)
                Text("v$version · 简体中文 Android SSH 客户端", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SshSectionHeader("能力") }
        listOf(
            Icons.Default.Terminal to ("终端与会话" to "多会话、快捷命令、搜索和 AI 助手"),
            Icons.Default.Storage to ("文件与传输" to "SFTP、系统文件访问和安全写回"),
            Icons.Default.Security to ("连接与安全" to "保险库、主机指纹、跳板机与代理"),
        ).forEach { (icon, text) -> item(text.first) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { PreferenceAction(icon, text.first, text.second, {}) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) } } } }
        item { PreferenceGroup { Text("第三方组件", fontWeight = FontWeight.SemiBold); Text("xterm.js · JSch · Bouncy Castle · CommonMark", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) } }
    }
}

private fun VaultState.displayName(): String = when (this) {
    VaultState.Disabled -> "未启用"
    VaultState.Locked -> "已锁定"
    is VaultState.Unlocked -> "已解锁"
    is VaultState.Unavailable -> "不可用 · $reason"
}

internal fun ThemeMode.displayName(): String = when (this) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }
internal fun ThemePreset.displayName(): String = when (this) { ThemePreset.OCEAN -> "深海蓝"; ThemePreset.EMERALD -> "矩阵绿"; ThemePreset.AMBER -> "经典琥珀"; ThemePreset.VIOLET -> "星云紫" }
private fun <T> List<T>.swap(first: Int, second: Int): List<T> = toMutableList().apply { val value = this[first]; this[first] = this[second]; this[second] = value }
