package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.DEFAULT_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.MAX_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.MIN_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.settings.ExtraKeyId
import com.yang136.sshhelper.settings.DEFAULT_EXTRA_KEYS
import com.yang136.sshhelper.security.VaultState
import kotlin.math.roundToInt

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onThemePresetChange: (ThemePreset) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onExtraKeysChange: (List<ExtraKeyId>) -> Unit,
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
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("安全", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("凭据保险库", fontWeight = FontWeight.Medium)
                                Text(vaultState.displayName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            when (vaultState) {
                                VaultState.Disabled -> Button(onClick = onEnableVault, enabled = canAuthenticate) { Text("启用") }
                                VaultState.Locked -> Button(onClick = onUnlockVault, enabled = canAuthenticate) { Text("解锁") }
                                is VaultState.Unlocked -> OutlinedButton(onClick = onLockVault) { Text("立即锁定") }
                                is VaultState.Unavailable -> Button(onClick = { confirmVaultReset = true }) { Text("清除凭据") }
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
                    }
                }
            }

            item {
                Text("显示模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
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

            item {
                Text("主题预设", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                                Modifier.size(48.dp).background(preview.background),
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

            item {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("终端字体", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${settings.terminalFontSize}", color = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedButton(onClick = { onFontSizeChange(DEFAULT_TERMINAL_FONT_SIZE) }) {
                        Text("恢复默认")
                    }
                }
                Slider(
                    value = settings.terminalFontSize.toFloat(),
                    onValueChange = { onFontSizeChange(it.roundToInt()) },
                    valueRange = MIN_TERMINAL_FONT_SIZE.toFloat()..MAX_TERMINAL_FONT_SIZE.toFloat(),
                    steps = MAX_TERMINAL_FONT_SIZE - MIN_TERMINAL_FONT_SIZE - 1,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(MIN_TERMINAL_FONT_SIZE.toString(), style = MaterialTheme.typography.labelSmall)
                    Text(MAX_TERMINAL_FONT_SIZE.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("终端控制键", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("选择并调整显示顺序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { onExtraKeysChange(DEFAULT_EXTRA_KEYS) }) { Text("恢复默认") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
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
