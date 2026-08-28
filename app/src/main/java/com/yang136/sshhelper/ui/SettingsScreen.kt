package com.yang136.sshhelper.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.DEFAULT_EXTRA_KEYS
import com.yang136.sshhelper.settings.DEFAULT_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.ExtraKeyId
import com.yang136.sshhelper.settings.MAX_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.MIN_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.security.VaultState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class SettingsTab(
    val label: String,
    val icon: ImageVector,
) {
    APPEARANCE("外观", Icons.Default.Palette),
    TERMINAL("终端", Icons.Default.Terminal),
    AI("AI", Icons.Default.AutoAwesome),
    SECURITY("安全", Icons.Default.Lock),
    ABOUT("关于", Icons.Default.Info),
}

private data class PresetPreview(
    val preset: ThemePreset,
    val title: String,
    val description: String,
    val background: Color,
    val accent: Color,
)

private val presetPreviews = listOf(
    PresetPreview(ThemePreset.OCEAN, "深海蓝", "深蓝黑 · 青绿色", Color(0xFF07131F), Color(0xFF22D3EE)),
    PresetPreview(ThemePreset.EMERALD, "矩阵绿", "墨绿色 · 亮绿色", Color(0xFF06130E), Color(0xFF35E07F)),
    PresetPreview(ThemePreset.AMBER, "经典琥珀", "深棕黑 · 琥珀色", Color(0xFF171007), Color(0xFFFFB84D)),
    PresetPreview(ThemePreset.VIOLET, "星云紫", "深紫色 · 淡紫色", Color(0xFF100A1D), Color(0xFFB388FF)),
)

private data class LicenseInfo(val name: String, val license: String, val url: String)

private val licenses = listOf(
    LicenseInfo("xterm.js", "MIT License", "https://github.com/xtermjs/xterm.js"),
    LicenseInfo("mwiede/jsch", "BSD-style License", "https://github.com/mwiede/jsch"),
    LicenseInfo("Bouncy Castle", "MIT License", "https://www.bouncycastle.org/"),
)

private data class FeatureInfo(
    val icon: ImageVector,
    val title: String,
    val description: String,
)

private val featureGroups = listOf(
    FeatureInfo(Icons.Default.Shield, "连接与安全", "密码/私钥认证、应用内 ed25519 密钥生成、Keystore 加密凭据、主机指纹校验、HTTP/SOCKS5 代理、单层跳板机"),
    FeatureInfo(Icons.Default.Terminal, "终端", "多会话标签页、搜索/选择/复制、自定义控制键、AI 悬浮助手"),
    FeatureInfo(Icons.Default.Folder, "文件与传输", "SFTP 双端浏览、批量任务、断点续传、冲突策略"),
    FeatureInfo(Icons.Default.Public, "隧道", "本地 -L / 远程 -R / 动态 SOCKS5 -D 端口转发"),
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
) {
    var confirmVaultReset by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = selectedTab) { SettingsTab.entries.size }
    BackHandler(onBack = onBack)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { selectedTab = it }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                )
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    divider = {},
                ) {
                    SettingsTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = index == selectedTab,
                            onClick = {
                                selectedTab = index
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = { Icon(tab.icon, null, Modifier.size(18.dp)) },
                            text = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            beyondViewportPageCount = 0,
        ) { page ->
            when (SettingsTab.entries[page]) {
                SettingsTab.APPEARANCE -> AppearanceTab(settings, onThemeModeChange, onThemePresetChange)
                SettingsTab.TERMINAL -> TerminalTab(settings, onFontSizeChange, onExtraKeysChange)
                SettingsTab.AI -> AiTab(settings, showApiKey, { showApiKey = it }, onAiBaseUrlChange, onAiApiKeyChange, onAiModelChange, onAiSendContextChange, onAiShowBubbleChange)
                SettingsTab.SECURITY -> SecurityTab(settings.forwardReconnectAfterLock, onForwardReconnectAfterLockChange, vaultState, canAuthenticate, onEnableVault, onUnlockVault, onDisableVault, onLockVault, onClearUnavailableVault, { confirmVaultReset = true })
                SettingsTab.ABOUT -> AboutTab()
            }
        }
    }
    if (confirmVaultReset) {
        AlertDialog(
            onDismissRequest = { confirmVaultReset = false },
            title = { Text("清除所有已保存凭据？") },
            text = { Text("保险库密钥已经不可用。此操作会永久删除全部已保存的密码、私钥和私钥口令，但保留主机配置与主机指纹。") },
            confirmButton = {
                TextButton(onClick = { confirmVaultReset = false; onClearUnavailableVault() }) { Text("永久清除") }
            },
            dismissButton = { TextButton(onClick = { confirmVaultReset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingsScaffoldList(content: LazyListScope.() -> Unit) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun AppearanceTab(
    settings: AppSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onThemePresetChange: (ThemePreset) -> Unit,
) {
    SettingsScaffoldList {
        item {
            SectionTitle("显示模式", "跟随系统或固定明暗")
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(mode.displayName()) },
                        )
                    }
                }
            }
        }
        item {
            SectionTitle("主题预设", "终端配色方案")
        }
        presetPreviews.forEach { preview ->
            item(key = preview.preset.name) {
                val selected = settings.themePreset == preview.preset
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onThemePresetChange(preview.preset) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(preview.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(22.dp).background(preview.accent))
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(preview.title, fontWeight = FontWeight.Medium)
                            Text(preview.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalTab(
    settings: AppSettings,
    onFontSizeChange: (Int) -> Unit,
    onExtraKeysChange: (List<ExtraKeyId>) -> Unit,
) {
    SettingsScaffoldList {
        item {
            SectionTitle("终端字体", "${settings.terminalFontSize} px")
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("字号", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { onFontSizeChange(DEFAULT_TERMINAL_FONT_SIZE) }) { Text("恢复默认") }
                }
                Slider(
                    value = settings.terminalFontSize.toFloat(),
                    onValueChange = { onFontSizeChange(it.roundToInt()) },
                    valueRange = MIN_TERMINAL_FONT_SIZE.toFloat()..MAX_TERMINAL_FONT_SIZE.toFloat(),
                    steps = MAX_TERMINAL_FONT_SIZE - MIN_TERMINAL_FONT_SIZE - 1,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$MIN_TERMINAL_FONT_SIZE", style = MaterialTheme.typography.labelSmall)
                    Text("$MAX_TERMINAL_FONT_SIZE", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            SectionTitle("终端控制键", "选择并调整显示顺序")
            SectionCard {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ExtraKeyId.entries.forEach { key ->
                        FilterChip(
                            selected = key in settings.extraKeys,
                            onClick = {
                                onExtraKeysChange(
                                    if (key in settings.extraKeys) settings.extraKeys - key else settings.extraKeys + key,
                                )
                            },
                            label = { Text(key.label) },
                        )
                    }
                }
                settings.extraKeys.forEachIndexed { index, key ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${key.label}", Modifier.weight(1f))
                        IconButton(
                            enabled = index > 0,
                            onClick = { onExtraKeysChange(settings.extraKeys.swap(index, index - 1)) },
                        ) { Icon(Icons.Default.ArrowUpward, "上移") }
                        IconButton(
                            enabled = index < settings.extraKeys.lastIndex,
                            onClick = { onExtraKeysChange(settings.extraKeys.swap(index, index + 1)) },
                        ) { Icon(Icons.Default.ArrowDownward, "下移") }
                        IconButton(onClick = { onExtraKeysChange(settings.extraKeys - key) }) { Icon(Icons.Default.Close, "移除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiTab(
    settings: AppSettings,
    showApiKey: Boolean,
    onShowApiKeyChange: (Boolean) -> Unit,
    onAiBaseUrlChange: (String) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiSendContextChange: (Boolean) -> Unit,
    onAiShowBubbleChange: (Boolean) -> Unit,
) {
    SettingsScaffoldList {
        item {
            SectionTitle("AI 助手", "终端页悬浮气泡")
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(settings.aiShowBubble, onAiShowBubbleChange)
                    Column {
                        Text("在终端页显示 AI 悬浮窗")
                        Text("关闭后需要重新开启才会显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("悬浮气泡可结合最近的终端输出，向 AI 提问并获得命令建议。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    settings.aiBaseUrl,
                    onAiBaseUrlChange,
                    Modifier.fillMaxWidth(),
                    label = { Text("服务地址") },
                    placeholder = { Text("https://api.deepseek.com/v1") },
                    singleLine = true,
                )
                OutlinedTextField(
                    settings.aiApiKey,
                    onAiApiKeyChange,
                    Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { onShowApiKeyChange(!showApiKey) }) {
                            Icon(if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showApiKey) "隐藏" else "显示")
                        }
                    },
                )
                OutlinedTextField(
                    settings.aiModel,
                    onAiModelChange,
                    Modifier.fillMaxWidth(),
                    label = { Text("模型") },
                    placeholder = { Text("deepseek-chat") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(settings.aiSendContext, onAiSendContextChange)
                    Column {
                        Text("发送最近终端输出作为上下文")
                        Text("仅发送最近约 6KB 输出；关闭后只发送你的提问", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("API Key 以明文保存在本机 DataStore 中，仅用于请求你配置的服务地址。发送内容会离开本设备，请注意服务商的隐私政策。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SecurityTab(
    forwardReconnectAfterLock: Boolean,
    onForwardReconnectAfterLockChange: (Boolean) -> Unit,
    vaultState: VaultState,
    canAuthenticate: Boolean,
    onEnableVault: () -> Unit,
    onUnlockVault: () -> Unit,
    onDisableVault: () -> Unit,
    onLockVault: () -> Unit,
    onClearUnavailableVault: () -> Unit,
    onRequestClear: () -> Unit,
) {
    SettingsScaffoldList {
        item { BackgroundConnectionCard() }
        item { DocumentWritebackCard() }
        item {
            SectionTitle("安全", "凭据保护")
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text("凭据保险库", fontWeight = FontWeight.Medium)
                        Text(vaultState.displayName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (vaultState) {
                        VaultState.Disabled -> Button(onClick = onEnableVault, enabled = canAuthenticate) { Text("启用") }
                        VaultState.Locked -> Button(onClick = onUnlockVault, enabled = canAuthenticate) { Text("解锁") }
                        is VaultState.Unlocked -> OutlinedButton(onClick = onLockVault) { Text("立即锁定") }
                        is VaultState.Unavailable -> Button(onClick = onRequestClear) { Text("清除凭据") }
                    }
                }
                Text("使用强生物识别或系统 PIN、图案、锁屏密码保护已保存的密码和私钥。应用进入后台超过 5 分钟后自动锁定。", style = MaterialTheme.typography.bodySmall)
                if (!canAuthenticate && vaultState is VaultState.Disabled) {
                    Text("请先在 Android 系统中设置安全锁屏。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (vaultState !is VaultState.Disabled) {
                    OutlinedButton(onClick = onDisableVault, enabled = canAuthenticate) { Text("关闭保险库") }
                }
                if (vaultState is VaultState.Unavailable) {
                    Text("清除后无法恢复已保存的密码和私钥，需要逐台重新录入。主机配置与指纹不会删除。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(forwardReconnectAfterLock, onForwardReconnectAfterLockChange)
                    Column {
                        Text("锁屏后允许活动转发隧道自动重连")
                        Text("开启时已启动隧道的凭据保存在内存中，生命周期与隧道相同；关闭时锁屏后断线需回应用解锁才能恢复", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutTab() {
    val context = LocalContext.current
    val packageInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "1.0.0"
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo?.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo?.versionCode?.toLong()
    }
    var showLicenses by remember { mutableStateOf(false) }

    SettingsScaffoldList {
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Security,
                    "SSH Helper 图标",
                    Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text("SSH Helper", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                ) {
                    Text(
                        "v$versionName${versionCode?.let { " (${it})" }.orEmpty()}",
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text("简体中文 Android SSH 客户端", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionTitle("功能", "开箱即用的运维工作台")
        }
        featureGroups.forEach { feature ->
            item(key = feature.title) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(feature.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(feature.title, fontWeight = FontWeight.Medium)
                            Text(feature.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            SectionTitle("开源许可")
            Card(
                modifier = Modifier.fillMaxWidth().clickable { showLicenses = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("第三方组件", fontWeight = FontWeight.Medium)
                        Text("xterm.js · JSch · Bouncy Castle 等", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("开源许可") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    licenses.forEach { license ->
                        Column {
                            Text(license.name, fontWeight = FontWeight.Medium)
                            Text(license.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(license.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("完整许可文本见各上游仓库与打包产物。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(8.dp))
}

private fun VaultState.displayName(): String = when (this) {
    VaultState.Disabled -> "未启用"
    VaultState.Locked -> "已锁定 · 使用保存凭据前需要验证"
    is VaultState.Unlocked -> "已解锁"
    is VaultState.Unavailable -> "不可用 · $reason"
}

private fun <T> List<T>.swap(first: Int, second: Int): List<T> = toMutableList().apply {
    val value = this[first]
    this[first] = this[second]
    this[second] = value
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}
