package com.yang136.sshhelper.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.ImportReport
import com.yang136.sshhelper.security.VaultState
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.DEFAULT_EXTRA_KEYS
import com.yang136.sshhelper.settings.DEFAULT_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.ExtraKeyId
import com.yang136.sshhelper.settings.MAX_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.MIN_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.settings.ThemeSource
import com.yang136.sshhelper.settings.ImageThemeVariant
import com.yang136.sshhelper.settings.MIN_IMAGE_OVERLAY_STRENGTH
import com.yang136.sshhelper.settings.MAX_IMAGE_OVERLAY_STRENGTH
import com.yang136.sshhelper.theme.ImageThemeState
import com.yang136.sshhelper.theme.imageThemeTokens
import com.yang136.sshhelper.ui.design.PreferenceAction
import com.yang136.sshhelper.ui.design.PreferenceGroup
import com.yang136.sshhelper.ui.design.PreferenceSwitch
import com.yang136.sshhelper.ui.design.SshCenteredList
import com.yang136.sshhelper.ui.design.SshInlineBanner
import com.yang136.sshhelper.ui.design.SshSectionHeader
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsDestination(val id: String, val title: String, val icon: ImageVector) {
    APPEARANCE("appearance", "外观与主题", Icons.Default.Palette),
    TERMINAL("terminal", "终端", Icons.Default.Terminal),
    AI("ai", "AI 助手", Icons.Default.AutoAwesome),
    CONNECTIONS("connections", "连接与后台", Icons.Default.NotificationsActive),
    SECURITY("security", "安全与凭据", Icons.Default.Security),
    DOCUMENTS("documents", "系统文件访问", Icons.Default.Storage),
    DATA("data", "数据备份", Icons.Default.SettingsBackupRestore),
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
    SettingsDestination.APPEARANCE -> if (settings.themeSource == ThemeSource.IMAGE) {
        "图片主题 · ${settings.imageThemeVariant.label}"
    } else {
        "${settings.themeMode.displayName()} · ${settings.themePreset.displayName()}"
    }
    SettingsDestination.TERMINAL -> "${settings.terminalFontSize} px · ${settings.extraKeys.size} 个控制键"
    SettingsDestination.AI -> if (settings.aiApiKey.isBlank()) "未配置 API Key" else "${settings.aiModel} · 已配置"
    SettingsDestination.CONNECTIONS -> if (settings.forwardReconnectAfterLock) "允许活动隧道锁屏后重连" else "锁屏后等待解锁"
    SettingsDestination.SECURITY -> vault
    SettingsDestination.DOCUMENTS -> "$documentRoots 台主机已授权${if (writebacks > 0) " · $writebacks 个待恢复" else ""}"
    SettingsDestination.DATA -> "导出或增量导入服务器配置与快捷指令"
    SettingsDestination.ABOUT -> "版本、功能与开源许可"
}

internal data class SettingsCategoryUiState(val destination: SettingsDestination, val summary: String)

internal data class SettingsHomeUiState(val categories: List<SettingsCategoryUiState>)

internal fun buildSettingsHomeUiState(
    settings: AppSettings,
    vault: String,
    documentRoots: Int,
    writebacks: Int,
) = SettingsHomeUiState(
    SettingsDestination.entries.map { destination ->
        SettingsCategoryUiState(destination, settingsSummary(destination, settings, vault, documentRoots, writebacks))
    },
)

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
    ThemePreview(ThemePreset.AMBER, Color(0xFF0B0B0D), Color(0xFFD9B45F)),
    ThemePreview(ThemePreset.VIOLET, Color(0xFF0D1117), Color(0xFFB8C4D6)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    imageThemeState: ImageThemeState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onThemePresetChange: (ThemePreset) -> Unit,
    onThemeSourceChange: (ThemeSource) -> Unit,
    onImportImageTheme: (Uri, Float) -> Unit,
    onImageVariantChange: (ImageThemeVariant) -> Unit,
    onImageOverlayChange: (Float) -> Unit,
    onSelectImageTheme: (String) -> Unit,
    onDeleteImageTheme: (String) -> Unit,
    onClearImageThemeError: () -> Unit,
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
    modifier: Modifier = Modifier,
    showRootBack: Boolean = true,
    onDetailChanged: (Boolean) -> Unit = {},
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

    LaunchedEffect(selectedId) { onDetailChanged(selectedId != null) }

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
        modifier = modifier,
        containerColor = imageAwareScaffoldColor(),
        contentColor = imageAwareContentColor(),
        topBar = {
            SshTopAppBar(
                title = selected?.title ?: "设置",
                subtitle = selected?.let { "SSH Helper 偏好设置" },
                navigationIcon = {
                    if (selected != null || showRootBack) IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
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
                buildSettingsHomeUiState(settings, vaultState.displayName(), roots.size, writebacks.size),
                Modifier.padding(padding),
            ) { selectedId = it.id }
            SettingsDestination.APPEARANCE -> AppearanceSettings(
                settings = settings,
                imageThemeState = imageThemeState,
                onMode = onThemeModeChange,
                onPreset = onThemePresetChange,
                onSource = onThemeSourceChange,
                onImport = onImportImageTheme,
                onVariant = onImageVariantChange,
                onOverlay = onImageOverlayChange,
                onSelectImage = onSelectImageTheme,
                onDeleteImage = onDeleteImageTheme,
                onClearError = onClearImageThemeError,
                modifier = Modifier.padding(padding),
            )
            SettingsDestination.TERMINAL -> TerminalSettings(settings, onFontSizeChange, onExtraKeysChange, Modifier.padding(padding))
            SettingsDestination.AI -> AiSettings(settings, aiBaseUrl, { aiBaseUrl = it }, aiApiKey, { aiApiKey = it }, aiModel, { aiModel = it }, onAiSendContextChange, onAiShowBubbleChange, Modifier.padding(padding))
            SettingsDestination.CONNECTIONS -> ConnectionsSettings(settings, onForwardReconnectAfterLockChange, Modifier.padding(padding))
            SettingsDestination.SECURITY -> SecuritySettings(vaultState, canAuthenticate, onEnableVault, onUnlockVault, onDisableVault, onLockVault, { confirmVaultReset = true }, Modifier.padding(padding))
            SettingsDestination.DOCUMENTS -> DocumentsSettings(roots.size, writebacks.size, Modifier.padding(padding))
            SettingsDestination.DATA -> DataSettings(Modifier.padding(padding))
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
    SshCenteredList(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SettingsHome(state: SettingsHomeUiState, modifier: Modifier, onDestination: (SettingsDestination) -> Unit) {
    SettingsPage(modifier) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
                Text("控制你的工作环境", style = MaterialTheme.typography.headlineSmall)
                Text("外观、连接、安全与工具都集中在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.categories.forEach { category ->
            item(category.destination.id) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    PreferenceAction(category.destination.icon, category.destination.title, category.summary, { onDestination(category.destination) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettings(
    settings: AppSettings,
    imageThemeState: ImageThemeState,
    onMode: (ThemeMode) -> Unit,
    onPreset: (ThemePreset) -> Unit,
    onSource: (ThemeSource) -> Unit,
    onImport: (Uri, Float) -> Unit,
    onVariant: (ImageThemeVariant) -> Unit,
    onOverlay: (Float) -> Unit,
    onSelectImage: (String) -> Unit,
    onDeleteImage: (String) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier,
) {
    val configuration = LocalConfiguration.current
    val targetAspectRatio = (configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1))
        .coerceIn(0.3f, 3f)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onImport(it, targetAspectRatio) }
    }
    val openPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    SettingsPage(modifier) {
        item { SshSectionHeader("主题来源", summary = if (settings.themeSource == ThemeSource.IMAGE) "图片主题" else "预设主题") }
        item {
            PreferenceGroup {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeSource.entries.forEachIndexed { index, source ->
                        SegmentedButton(
                            selected = settings.themeSource == source,
                            onClick = {
                                if (source == ThemeSource.IMAGE && !imageThemeState.hasImage) openPicker()
                                else onSource(source)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeSource.entries.size),
                        ) { Text(if (source == ThemeSource.PRESET) "预设主题" else "图片主题") }
                    }
                }
                if (imageThemeState.isImporting) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在处理图片…", Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (settings.themeSource == ThemeSource.PRESET) {
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
                        colors = CardDefaults.cardColors(
                            containerColor = imageAwareContainerColor(MaterialTheme.colorScheme.surfaceContainer),
                            contentColor = imageAwareContentColor(),
                        ),
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
        } else {
            item {
                ImageThemeControls(
                    settings = settings,
                    state = imageThemeState,
                    onImport = openPicker,
                    onVariant = onVariant,
                    onOverlay = onOverlay,
                    onSelect = onSelectImage,
                    onDelete = onDeleteImage,
                    onClearError = onClearError,
                )
            }
        }
        if (settings.themeSource == ThemeSource.PRESET && imageThemeState.errorMessage != null) {
            item {
                SshInlineBanner("图片处理失败", imageThemeState.errorMessage, tone = SshStatusTone.ERROR)
                TextButton(onClick = onClearError) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun ImageThemeControls(
    settings: AppSettings,
    state: ImageThemeState,
    onImport: () -> Unit,
    onVariant: (ImageThemeVariant) -> Unit,
    onOverlay: (Float) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearError: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val light = settings.imageThemeVariant == ImageThemeVariant.BRIGHT
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(
            containerColor = imageAwareContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentColor = imageAwareContentColor(),
        )) {
            Box(Modifier.fillMaxWidth().height(190.dp)) {
                state.bitmap?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), "当前图片背景预览", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(if (light) Color.White.copy(alpha = settings.imageOverlayStrength) else Color.Black.copy(alpha = settings.imageOverlayStrength)))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, if (light) Color.White.copy(.3f) else Color.Black.copy(.5f)))))
                } ?: Icon(Icons.Default.PhotoLibrary, null, Modifier.size(42.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text("图片主题 · ${settings.imageThemeVariant.label}", fontWeight = FontWeight.Bold)
                    Text("仅保存在本机，不会上传或备份", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (state.recentEntries.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近使用", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${state.recentEntries.size}/3", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { index ->
                    val entry = state.recentEntries.getOrNull(index)
                    val selected = entry?.id == state.activeId
                    Box(
                        Modifier.weight(1f).aspectRatio(.78f).clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .then(if (entry != null) Modifier.clickable { onSelect(entry.id) } else Modifier)
                            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                    ) {
                        entry?.thumbnail?.let { Image(it.asImageBitmap(), "最近背景 ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        if (entry == null) Text("空", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else IconButton(
                            onClick = { pendingDelete = entry.id },
                            modifier = Modifier.align(Alignment.TopEnd).size(40.dp).background(Color.Black.copy(.48f), CircleShape),
                        ) { Icon(Icons.Default.Delete, "删除最近背景 ${index + 1}", tint = Color.White) }
                        if (selected) Icon(Icons.Default.Check, "当前背景", Modifier.align(Alignment.BottomStart).padding(8.dp), tint = Color.White)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onImport, enabled = !state.isImporting, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PhotoLibrary, null)
                Spacer(Modifier.width(8.dp))
                Text("导入新图片")
            }
            OutlinedButton(
                onClick = { state.activeId?.let { pendingDelete = it } },
                enabled = state.activeId != null && !state.isImporting,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(8.dp))
                Text("删除当前")
            }
        }

        state.palette?.let { palette ->
            Text("派生配色", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageThemeVariant.entries.forEach { variant ->
                    val tokens = imageThemeTokens(palette, variant)
                    Card(
                        onClick = { onVariant(variant) },
                        modifier = Modifier.weight(1f),
                        border = if (settings.imageThemeVariant == variant) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(tokens.background, tokens.surface, tokens.primary).forEach { Box(Modifier.size(15.dp).background(Color(it), CircleShape)) }
                            }
                            Text(variant.label, fontWeight = FontWeight.SemiBold)
                            Text(if (palette.recommendedVariant == variant) "算法推荐" else " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        PreferenceGroup {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("背景遮罩", fontWeight = FontWeight.SemiBold)
                    Text("提高后可增强文字可读性", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${(settings.imageOverlayStrength * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
            }
            Slider(settings.imageOverlayStrength, onOverlay, valueRange = MIN_IMAGE_OVERLAY_STRENGTH..MAX_IMAGE_OVERLAY_STRENGTH)
        }

        state.errorMessage?.let {
            SshInlineBanner("图片处理失败", it, tone = SshStatusTone.ERROR)
            TextButton(onClick = onClearError) { Text("知道了") }
        }
    }
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这张背景？") },
            text = { Text(if (id == state.activeId && state.recentEntries.size == 1) "删除后将恢复到预设主题。" else "图片与独立配色将从本机永久删除。") },
            confirmButton = { TextButton(onClick = { pendingDelete = null; onDelete(id) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
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

/**
 * 数据备份：将服务器配置与快捷指令导出为 JSON，或对 JSON 做增量导入。
 * 导出不包含密码/私钥等凭据，也不包含设置项；增量导入只新增或更新，不删除任何数据。
 */
@Composable
private fun DataSettings(modifier: Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as SshHelperApplication
    val manager = app.container.configTransferManager
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var pendingImportReport by remember { mutableStateOf<ImportReport?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        exportResult = null
        exportError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val payload = manager.export()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(payload.json.encodeToByteArray())
                    } ?: error("无法创建导出文件")
                    payload
                }
            }.onSuccess { payload ->
                working = false
                exportResult = "已导出 ${payload.hostCount} 台主机 · ${payload.snippetCount} 条快捷指令（不含凭据）"
            }.onFailure { failure ->
                working = false
                exportError = "导出失败：${failure.message ?: "未知错误"}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        importResult = null
        importError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取所选文件")
                    text to manager.previewImport(text)
                }
            }.onSuccess { (text, report) ->
                working = false
                pendingImportText = text
                pendingImportReport = report
            }.onFailure { failure ->
                working = false
                importError = "导入失败：${failure.message ?: "未知错误"}"
            }
        }
    }

    fun performImport() {
        val text = pendingImportText ?: return
        pendingImportText = null
        pendingImportReport = null
        working = true
        importResult = null
        importError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { manager.importIncremental(text) } }
                .onSuccess { report ->
                    working = false
                    importResult = "导入完成：${report.summary()}"
                }
                .onFailure { failure ->
                    working = false
                    importError = "导入失败：${failure.message ?: "未知错误"}"
                }
        }
    }

    SettingsPage(modifier) {
        item { SshSectionHeader("导出", summary = "服务器配置与快捷指令") }
        item {
            PreferenceGroup {
                Text(
                    "导出为 JSON 文件：包含主机连接参数与快捷指令，不含密码/私钥等凭据，也不含设置选项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        exportResult = null
                        exportError = null
                        exportLauncher.launch("ssh-helper-config-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json")
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("导出 JSON")
                }
            }
        }
        item { SshSectionHeader("导入", summary = "增量合并") }
        item {
            PreferenceGroup {
                Text(
                    "选择 SSH Helper 导出的 JSON 做增量导入：相同服务器（地址+端口+用户名）与相同快捷指令会被更新，其余新增；不会删除现有数据，重复导入不会产生重复条目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        importResult = null
                        importError = null
                        importLauncher.launch(arrayOf("application/json"))
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileUpload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择 JSON 文件")
                }
            }
        }
        if (working) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在处理…", Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        exportResult?.let { item { SshInlineBanner("导出完成", it, tone = SshStatusTone.CONNECTED) } }
        importResult?.let { item { SshInlineBanner("导入完成", it, tone = SshStatusTone.CONNECTED) } }
        exportError?.let { item { SshInlineBanner("导出失败", it, tone = SshStatusTone.ERROR) } }
        importError?.let { item { SshInlineBanner("导入失败", it, tone = SshStatusTone.ERROR) } }
    }

    pendingImportReport?.let { report ->
        AlertDialog(
            onDismissRequest = {
                if (!working) {
                    pendingImportText = null
                    pendingImportReport = null
                }
            },
            title = { Text("确认增量导入？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(report.summary())
                    if (report.skippedReasons.isNotEmpty()) {
                        Text(
                            "跳过 ${report.skippedHosts + report.skippedSnippets} 项：${report.skippedReasons.take(3).joinToString("；")}${if (report.skippedReasons.size > 3) "…" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "导入只新增或更新现有数据，不会删除任何条目；文件不含密码或私钥，导入后请为需要的主机重新输入凭据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = ::performImport, enabled = !working) { Text("导入") } },
            dismissButton = { TextButton(onClick = {
                pendingImportText = null
                pendingImportReport = null
            }) { Text("取消") } },
        )
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
internal fun ThemePreset.displayName(): String = when (this) { ThemePreset.OCEAN -> "深海蓝"; ThemePreset.EMERALD -> "矩阵绿"; ThemePreset.AMBER -> "曜石金"; ThemePreset.VIOLET -> "北境灰" }
private fun <T> List<T>.swap(first: Int, second: Int): List<T> = toMutableList().apply { val value = this[first]; this[first] = this[second]; this[second] = value }
