package com.yang136.sshhelper.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewAssetLoader
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.CommandSnippet
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.expandSnippet
import com.yang136.sshhelper.data.requiredSnippetInputs
import com.yang136.sshhelper.ssh.ConnectionStage
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.CredentialRole
import com.yang136.sshhelper.ssh.HostKeyIssue
import com.yang136.sshhelper.ssh.HostKeyRequest
import com.yang136.sshhelper.ssh.HostKeySubject
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.DEFAULT_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.MAX_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.MIN_TERMINAL_FONT_SIZE
import com.yang136.sshhelper.settings.ExtraKeyId
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.SessionFeature
import com.yang136.sshhelper.ssh.TerminalOutputEvent
import com.yang136.sshhelper.ui.theme.TerminalPalette
import com.yang136.sshhelper.ui.adaptive.SshLayoutMode
import com.yang136.sshhelper.ui.adaptive.currentLayoutMode
import com.yang136.sshhelper.ui.adaptive.hasHardwareKeyboard
import com.yang136.sshhelper.ui.design.SshTopAppBar
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.view.WindowInsets as AndroidWindowInsets

private val NullableSessionIdSaver = Saver<SessionId?, String>(
    save = { it?.value.orEmpty() },
    restore = { value -> value.takeIf(String::isNotEmpty)?.let(::SessionId) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    initialSessionId: String?,
    hostId: Long,
    sessionsViewModel: SessionsViewModel,
    snippets: List<CommandSnippet>,
    settings: AppSettings,
    terminalPalette: TerminalPalette,
    onFontSizeChange: (Int) -> Unit,
    onManageSnippets: () -> Unit,
    onOpenForwards: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onUnlockVault: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val isLandscape = currentLayoutMode() == SshLayoutMode.LANDSCAPE
    val sessions by sessionsViewModel.sessions.collectAsStateWithLifecycle()
    val hostSessions = sessions.filter { it.profile.id == hostId }
    val initialId = initialSessionId?.let(::SessionId)
    var activeId by rememberSaveable(hostId, stateSaver = NullableSessionIdSaver) { mutableStateOf(initialId) }
    val current = hostSessions.firstOrNull { it.id == activeId }
    val controller = remember { TerminalController() }
    val surfaceRevision = remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var closingSession by remember { mutableStateOf<SessionId?>(null) }
    var showFontDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var hasSelection by remember { mutableStateOf(false) }
    var layoutState by rememberSaveable { mutableStateOf(TerminalLayoutState()) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var searchCaseSensitive by rememberSaveable { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf(-1 to 0) }
    var variableSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
    var immediateCommand by remember { mutableStateOf<String?>(null) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var forceCredentialDialog by remember { mutableStateOf(false) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var sessionLimitReached by remember { mutableStateOf(false) }
    var renderingDelayed by remember { mutableStateOf(false) }
    var aiHidden by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sessions) {
        if (hostSessions.isNotEmpty() && (activeId == null || hostSessions.none { it.id == activeId })) {
            activeId = hostSessions.first().id
        }
    }
    LaunchedEffect(activeId, surfaceRevision.intValue) {
        val id = activeId ?: return@LaunchedEffect
        sessionsViewModel.enableFeature(id, SessionFeature.SHELL)
        controller.reset()
        var lastSequence = -1L
        sessionsViewModel.output(id).collect { event ->
            when (event) {
                is TerminalOutputEvent.Snapshot -> {
                    if (event.sequence >= lastSequence) {
                        controller.write(event.bytes)
                        lastSequence = event.sequence
                    }
                }
                is TerminalOutputEvent.Chunk -> {
                    if (event.sequence > lastSequence) {
                        controller.write(event.bytes)
                        lastSequence = event.sequence
                    }
                }
            }
        }
    }
    LaunchedEffect(terminalPalette, settings.terminalFontSize) {
        controller.setAppearance(terminalPalette, settings.terminalFontSize)
    }
    LaunchedEffect(imeVisible) {
        controller.setImeVisible(imeVisible)
    }
    DisposableEffect(controller) {
        controller.onSelectionStateChanged = { active, selected ->
            selectionMode = active
            hasSelection = selected
            layoutState = reduceTerminalLayout(layoutState, TerminalLayoutAction.SelectionChanged(active))
        }
        controller.onCopied = { count ->
            scope.launch { snackbarHostState.showSnackbar("已复制 $count 个字符") }
        }
        controller.onSearchResults = { index, total -> searchResult = index to total }
        controller.onOpenLink = { pendingLink = it }
        controller.onCtrlArmed = { ctrlArmed = it }
        controller.onRenderingDelayed = { renderingDelayed = it }
        onDispose {
            controller.onSelectionStateChanged = null
            controller.onCopied = null
            controller.onSearchResults = null
            controller.onOpenLink = null
            controller.onCtrlArmed = null
            controller.onRenderingDelayed = null
            controller.close()
        }
    }
    BackHandler(onBack = onBack)

    fun closeTab(id: SessionId) {
        val remaining = hostSessions.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            onBack()
        } else if (activeId == id) {
            activeId = remaining.first().id
        }
        sessionsViewModel.close(id)
    }

    fun useSnippet(snippet: CommandSnippet, inputs: Map<String, String> = emptyMap()) {
        val profile = current?.profile ?: return
        val expansion = expandSnippet(snippet, profile, inputs)
        when {
            expansion.missingInputs.isNotEmpty() -> variableSnippet = snippet
            expansion.error != null -> scope.launch { snackbarHostState.showSnackbar(expansion.error) }
            snippet.executeImmediately -> immediateCommand = expansion.text
            else -> controller.pasteText(expansion.text.orEmpty())
        }
        if (expansion.missingInputs.isEmpty()) {
            layoutState = reduceTerminalLayout(layoutState, TerminalLayoutAction.ClosePanel)
        }
    }

    val hasHwKeyboard = hasHardwareKeyboard()
    val terminalBackground = androidx.compose.ui.graphics.Color(Color.parseColor(terminalPalette.background))
    val currentSessionState = rememberUpdatedState(current)
    val terminalSurface = remember(controller) {
        movableContentOf<Modifier> { modifier ->
            TerminalWebView(
                controller = controller,
                initialBackground = terminalPalette.background,
                onInput = { bytes -> currentSessionState.value?.let { sessionsViewModel.send(it.id, bytes) } },
                onResize = { columns, rows -> currentSessionState.value?.let { sessionsViewModel.resize(it.id, columns, rows) } },
                onSurfaceCreated = { surfaceRevision.intValue += 1 },
                modifier = modifier,
            )
        }
    }

    fun togglePanel(panel: TerminalPanel) {
        if (panel != TerminalPanel.SELECTION) controller.clearSelection()
        if (panel != TerminalPanel.SEARCH) controller.clearSearch()
        layoutState = reduceTerminalLayout(layoutState, TerminalLayoutAction.TogglePanel(panel))
    }

    TerminalSystemBarsEffect(isLandscape)
    Box(Modifier.fillMaxSize().background(terminalBackground)) {
        if (isLandscape) {
            LandscapeTerminalLayout(
                hostSessions = hostSessions,
                current = current,
                activeId = activeId,
                snippets = snippets.filter { it.hostId == null || it.hostId == current?.profile?.id },
                layoutState = layoutState,
                settings = settings,
                searchText = searchText,
                searchResult = searchResult,
                searchCaseSensitive = searchCaseSensitive,
                selectionMode = selectionMode,
                hasSelection = hasSelection,
                ctrlArmed = ctrlArmed,
                renderingDelayed = renderingDelayed,
                showMoreMenu = showMoreMenu,
                terminalBackground = terminalBackground,
                onBack = onBack,
                onTogglePanel = ::togglePanel,
                onToggleExtraKeys = {
                    layoutState = reduceTerminalLayout(layoutState, TerminalLayoutAction.ToggleExtraKeys)
                },
                onShowMoreMenuChange = { showMoreMenu = it },
                onSelectSession = { id ->
                    activeId = id
                    controller.clearSearch()
                    layoutState = reduceTerminalLayout(layoutState, TerminalLayoutAction.ClosePanel)
                },
                onNewSession = {
                    val profile = current?.profile ?: return@LandscapeTerminalLayout
                    sessionsViewModel.create(profile, SessionFeature.SHELL)?.let { activeId = it }
                        ?: run { sessionLimitReached = true }
                },
                onCloseSession = { closingSession = it },
                onReconnect = sessionsViewModel::reconnect,
                onCancelReconnect = sessionsViewModel::cancelReconnect,
                onUnlockVault = onUnlockVault,
                onCredentials = { forceCredentialDialog = true },
                onQueryChange = { searchText = it; controller.search(it, false, searchCaseSensitive) },
                onSearchPrevious = { controller.search(searchText, true, searchCaseSensitive) },
                onSearchNext = { controller.search(searchText, false, searchCaseSensitive) },
                onCaseSensitiveChange = { searchCaseSensitive = it; controller.search(searchText, false, it) },
                onUseSnippet = ::useSnippet,
                onManageSnippets = onManageSnippets,
                onCopy = controller::copySelection,
                onSelectAll = controller::selectAll,
                onCancelSelection = controller::clearSelection,
                onSendKey = { bytes -> current?.let { sessionsViewModel.send(it.id, bytes) } },
                onShowKeyboard = controller::focusAndShowKeyboard,
                onArmCtrl = controller::armCtrl,
                onPaste = { controller.paste(context) },
                onSelectText = controller::enterSelectionMode,
                onForwards = { current?.let { onOpenForwards(it.profile.id) } },
                onFont = { showFontDialog = true },
                onDisconnect = { current?.let { sessionsViewModel.disconnect(it.id) } },
                terminal = { modifier -> TerminalViewport(current, terminalBackground, modifier, terminalSurface) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Column {
                        SshTopAppBar(
                            title = current?.displayName ?: "SSH 终端",
                            subtitle = connectionLabel(current?.connection ?: ConnectionState.Idle),
                            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                            actions = {
                                IconButton(
                                    onClick = {
                                        val profile = current?.profile ?: return@IconButton
                                        sessionsViewModel.create(profile, SessionFeature.SHELL)?.let { activeId = it }
                                            ?: run { sessionLimitReached = true }
                                    },
                                    enabled = current != null,
                                ) { Icon(Icons.Default.Add, "新建会话") }
                                IconButton(onClick = { togglePanel(TerminalPanel.SEARCH) }) { Icon(Icons.Default.Search, "搜索") }
                                IconButton(onClick = { togglePanel(TerminalPanel.SNIPPETS) }) { Icon(Icons.Default.Code, "快捷命令") }
                                if (selectionMode && hasSelection) IconButton(onClick = controller::copySelection) {
                                    Icon(Icons.Default.ContentCopy, "复制所选内容")
                                }
                                IconButton(onClick = { showMoreMenu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                                    TerminalMoreMenuItems(
                                        selectionMode = selectionMode,
                                        hasHardwareKeyboard = hasHwKeyboard,
                                        forceExtraKeys = layoutState.extraKeysVisible,
                                        onDismiss = { showMoreMenu = false },
                                        onPaste = { controller.paste(context) },
                                        onSelectText = controller::enterSelectionMode,
                                        onForwards = { current?.let { onOpenForwards(it.profile.id) } },
                                        onFont = { showFontDialog = true },
                                        onDisconnect = { current?.let { sessionsViewModel.disconnect(it.id) } },
                                        onToggleExtraKeys = {
                                            layoutState = reduceTerminalLayout(layoutState, TerminalLayoutAction.ToggleExtraKeys)
                                        },
                                    )
                                }
                            },
                            allowImageBackground = false,
                        )
                        if (hostSessions.isNotEmpty()) {
                            PrimaryScrollableTabRow(
                                selectedTabIndex = hostSessions.indexOfFirst { it.id == activeId }.coerceAtLeast(0),
                                edgePadding = 4.dp,
                            ) {
                                hostSessions.forEach { session ->
                                    Tab(
                                        selected = session.id == activeId,
                                        onClick = { activeId = session.id; controller.clearSearch(); layoutState = layoutState.copy(panel = TerminalPanel.NONE) },
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("${session.displayName} · ${session.connection.presentation().first}", maxLines = 1)
                                                IconButton(onClick = { closingSession = session.id }) { Icon(Icons.Default.Close, "关闭 ${session.displayName}") }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                        .background(terminalBackground),
                ) {
                    TerminalViewport(current, terminalBackground, Modifier.weight(1f).fillMaxWidth(), terminalSurface)
                    current?.let { session ->
                        when (layoutState.panel) {
                            TerminalPanel.SEARCH -> TerminalSearchBar(
                                query = searchText,
                                result = searchResult,
                                caseSensitive = searchCaseSensitive,
                                onQueryChange = { searchText = it; controller.search(it, false, searchCaseSensitive) },
                                onPrevious = { controller.search(searchText, true, searchCaseSensitive) },
                                onNext = { controller.search(searchText, false, searchCaseSensitive) },
                                onCaseSensitiveChange = { searchCaseSensitive = it; controller.search(searchText, false, it) },
                                onClose = { controller.clearSearch(); layoutState = layoutState.copy(panel = TerminalPanel.NONE) },
                            )
                            TerminalPanel.SELECTION -> SelectionKeys(hasSelection, controller::copySelection, controller::selectAll, controller::clearSelection)
                            else -> if (!hasHwKeyboard || layoutState.extraKeysVisible) {
                                ExtraKeys(settings.extraKeys, ctrlArmed, { sessionsViewModel.send(session.id, it) }, controller::focusAndShowKeyboard, controller::armCtrl)
                            }
                        }
                    }
                    PortraitTerminalStatus(
                        current = current,
                        renderingDelayed = renderingDelayed,
                        onUnlockVault = onUnlockVault,
                        onCancelReconnect = sessionsViewModel::cancelReconnect,
                        onCredentials = { forceCredentialDialog = true },
                        onReconnect = sessionsViewModel::reconnect,
                    )
                }
            }
        }

        if (settings.aiShowBubble && !aiHidden) {
            current?.let { session ->
                AiBubble(
                    session = session,
                    stateFlow = sessionsViewModel.aiState(session.id),
                    settings = settings,
                    onSend = { sessionsViewModel.sendAi(session.id, it, settings) },
                    onConfirmCommand = { sessionsViewModel.confirmAiCommand(session.id, it) },
                    onFillTerminal = controller::pasteText,
                    onCancelGeneration = { sessionsViewModel.cancelAiGeneration(session.id) },
                    onInterruptCommand = { sessionsViewModel.interruptAiCommand(session.id) },
                    onStopWaiting = { sessionsViewModel.stopAiWaiting(session.id) },
                    onAnalyzePartial = { sessionsViewModel.analyzePartialAiOutput(session.id, it) },
                    onClear = { sessionsViewModel.clearAi(session.id) },
                    onOpenSettings = onOpenSettings,
                    onClose = { aiHidden = true },
                    presentation = if (isLandscape) AiBubblePresentation.LANDSCAPE_FLOATING else AiBubblePresentation.PORTRAIT_SHEET,
                    endClearance = if (isLandscape && layoutState.extraKeysVisible) 160.dp else 0.dp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                )
            }
        }
    }

    if ((current?.needsCredential == true || forceCredentialDialog) && current != null) {
        CredentialDialog(
            authType = current.credentialProfile().authType,
            subject = current.credentialSubjectLabel(),
            onDismiss = { forceCredentialDialog = false; if (current.needsCredential) closeTab(current.id) },
            onConnect = { credential, remember -> forceCredentialDialog = false; sessionsViewModel.connect(current.id, credential, remember) },
        )
    }

    current?.hostKeyRequest?.let { request ->
        val subject = request.subjectLabel()
        AlertDialog(
            onDismissRequest = { sessionsViewModel.respondToHostKey(current.id, false) },
            title = { Text(if (request.issue == HostKeyIssue.UNKNOWN) "确认${subject}身份" else "${subject}主机密钥已变化") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${request.hostname}:${request.port}")
                    Text("类型：${request.keyType}")
                    request.previousFingerprint?.let { Text("已保存：$it") }
                    Text("当前：${request.fingerprint}")
                    if (request.issue == HostKeyIssue.CHANGED) Text("为防止中间人攻击，本次连接已阻止。请核实服务器密钥后删除并重新创建主机。", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (request.issue == HostKeyIssue.UNKNOWN) sessionsViewModel.respondToHostKey(current.id, true)
                    else sessionsViewModel.forgetChangedHostKey(current.id)
                }) {
                    Text(if (request.issue == HostKeyIssue.UNKNOWN) "信任并连接" else "清除旧指纹")
                }
            },
            dismissButton = { TextButton(onClick = { sessionsViewModel.respondToHostKey(current.id, false) }) { Text("取消") } },
        )
    }

    closingSession?.let { id ->
        val label = hostSessions.firstOrNull { it.id == id }?.displayName.orEmpty()
        AlertDialog(
            onDismissRequest = { closingSession = null },
            title = { Text("关闭会话？") },
            text = { Text("将断开并关闭“$label”，终端输出也会被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    closingSession = null
                    closeTab(id)
                }) { Text("断开并关闭") }
            },
            dismissButton = { TextButton(onClick = { closingSession = null }) { Text("取消") } },
        )
    }

    if (sessionLimitReached) AlertDialog(
        onDismissRequest = { sessionLimitReached = false }, title = { Text("已达到会话上限") },
        text = { Text("最多可同时保留 8 个会话，请先关闭一个会话。") },
        confirmButton = { TextButton(onClick = { sessionLimitReached = false }) { Text("知道了") } },
    )

    if (showFontDialog) {
        FontSizeDialog(
            fontSize = settings.terminalFontSize,
            onFontSizeChange = onFontSizeChange,
            onDismiss = { showFontDialog = false },
        )
    }

    if (!isLandscape && layoutState.panel == TerminalPanel.SNIPPETS && current != null) {
        SnippetSheet(
            snippets = snippets.filter { it.hostId == null || it.hostId == current.profile.id },
            onSelect = ::useSnippet,
            onManage = { layoutState = layoutState.copy(panel = TerminalPanel.NONE); onManageSnippets() },
            onDismiss = { layoutState = layoutState.copy(panel = TerminalPanel.NONE) },
        )
    }
    variableSnippet?.let { snippet ->
        SnippetVariablesDialog(
            snippet = snippet,
            onDismiss = { variableSnippet = null },
            onConfirm = { values -> variableSnippet = null; useSnippet(snippet, values) },
        )
    }
    immediateCommand?.let { command ->
        AlertDialog(
            onDismissRequest = { immediateCommand = null },
            title = { Text("执行快捷命令？") },
            text = { Text(command, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = { activeId?.let { sessionsViewModel.send(it, (command + "\r").encodeToByteArray()) }; immediateCommand = null }) { Text("执行") } },
            dismissButton = { TextButton(onClick = { immediateCommand = null }) { Text("取消") } },
        )
    }
    pendingLink?.let { link ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text("在浏览器中打开链接？") },
            text = { Text(link) },
            confirmButton = { TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    .onFailure { scope.launch { snackbarHostState.showSnackbar("无法找到可打开链接的应用") } }
                pendingLink = null
            }) { Text("打开") } },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun connectionColor(state: ConnectionState) = when (state) {
    is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
    is ConnectionState.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> "未连接"
    ConnectionState.Connecting -> "连接中"
    is ConnectionState.Connected -> "已连接 · ${state.label}"
    is ConnectionState.Disconnected -> "已断开"
    is ConnectionState.Error -> "连接失败"
}

@Composable
private fun TerminalViewport(
    current: ManagedSessionState?,
    background: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    terminal: @Composable (Modifier) -> Unit,
) {
    Box(modifier.background(background)) {
        terminal(Modifier.fillMaxSize())
        if (current == null) {
            Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("暂无终端会话", style = MaterialTheme.typography.titleMedium)
                    Text("回到首页打开新终端", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LandscapeTerminalLayout(
    hostSessions: List<ManagedSessionState>,
    current: ManagedSessionState?,
    activeId: SessionId?,
    snippets: List<CommandSnippet>,
    layoutState: TerminalLayoutState,
    settings: AppSettings,
    searchText: String,
    searchResult: Pair<Int, Int>,
    searchCaseSensitive: Boolean,
    selectionMode: Boolean,
    hasSelection: Boolean,
    ctrlArmed: Boolean,
    renderingDelayed: Boolean,
    showMoreMenu: Boolean,
    terminalBackground: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit,
    onTogglePanel: (TerminalPanel) -> Unit,
    onToggleExtraKeys: () -> Unit,
    onShowMoreMenuChange: (Boolean) -> Unit,
    onSelectSession: (SessionId) -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: (SessionId) -> Unit,
    onReconnect: (SessionId) -> Unit,
    onCancelReconnect: (SessionId) -> Unit,
    onUnlockVault: () -> Unit,
    onCredentials: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchPrevious: () -> Unit,
    onSearchNext: () -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onUseSnippet: (CommandSnippet) -> Unit,
    onManageSnippets: () -> Unit,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onCancelSelection: () -> Unit,
    onSendKey: (ByteArray) -> Unit,
    onShowKeyboard: () -> Unit,
    onArmCtrl: () -> Unit,
    onPaste: () -> Unit,
    onSelectText: () -> Unit,
    onForwards: () -> Unit,
    onFont: () -> Unit,
    onDisconnect: () -> Unit,
    terminal: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(terminalBackground)
            // 状态栏在横屏隐藏；这里只避让左右挖孔、导航栏与 IME，不制造顶部整排留白。
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        LandscapeTerminalRail(
            current = current,
            selectedPanel = layoutState.panel,
            extraKeysVisible = layoutState.extraKeysVisible,
            selectionMode = selectionMode,
            showMoreMenu = showMoreMenu,
            onBack = onBack,
            onTogglePanel = onTogglePanel,
            onToggleExtraKeys = onToggleExtraKeys,
            onShowKeyboard = onShowKeyboard,
            onShowMoreMenuChange = onShowMoreMenuChange,
            onPaste = onPaste,
            onSelectText = onSelectText,
            onForwards = onForwards,
            onFont = onFont,
            onDisconnect = onDisconnect,
        )
        if (layoutState.panel != TerminalPanel.NONE) {
            LandscapeContextPanel(
                panel = layoutState.panel,
                hostSessions = hostSessions,
                current = current,
                activeId = activeId,
                snippets = snippets,
                searchText = searchText,
                searchResult = searchResult,
                searchCaseSensitive = searchCaseSensitive,
                hasSelection = hasSelection,
                renderingDelayed = renderingDelayed,
                onClose = { onTogglePanel(layoutState.panel) },
                onSelectSession = onSelectSession,
                onNewSession = onNewSession,
                onCloseSession = onCloseSession,
                onReconnect = onReconnect,
                onCancelReconnect = onCancelReconnect,
                onUnlockVault = onUnlockVault,
                onCredentials = onCredentials,
                onQueryChange = onQueryChange,
                onSearchPrevious = onSearchPrevious,
                onSearchNext = onSearchNext,
                onCaseSensitiveChange = onCaseSensitiveChange,
                onUseSnippet = onUseSnippet,
                onManageSnippets = onManageSnippets,
                onCopy = onCopy,
                onSelectAll = onSelectAll,
                onCancelSelection = onCancelSelection,
            )
        }
        terminal(Modifier.weight(1f).fillMaxHeight())
        if (layoutState.extraKeysVisible && current != null) {
            LandscapeExtraKeys(
                keys = settings.extraKeys,
                ctrlArmed = ctrlArmed,
                onSend = onSendKey,
                onShowKeyboard = onShowKeyboard,
                onArmCtrl = onArmCtrl,
            )
        }
    }
}

@Composable
private fun LandscapeTerminalRail(
    current: ManagedSessionState?,
    selectedPanel: TerminalPanel,
    extraKeysVisible: Boolean,
    selectionMode: Boolean,
    showMoreMenu: Boolean,
    onBack: () -> Unit,
    onTogglePanel: (TerminalPanel) -> Unit,
    onToggleExtraKeys: () -> Unit,
    onShowKeyboard: () -> Unit,
    onShowMoreMenuChange: (Boolean) -> Unit,
    onPaste: () -> Unit,
    onSelectText: () -> Unit,
    onForwards: () -> Unit,
    onFont: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(Modifier.width(56.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    RailButton(selectedPanel == TerminalPanel.SESSIONS, { onTogglePanel(TerminalPanel.SESSIONS) }, "会话") {
                        Icon(Icons.Default.Terminal, null)
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp)
                            .background(connectionColor(current?.connection ?: ConnectionState.Idle), CircleShape),
                    )
                }
                RailButton(false, onShowKeyboard, "软键盘") { Icon(Icons.Default.Keyboard, null) }
                RailButton(extraKeysVisible, onToggleExtraKeys, "扩展键") { Icon(Icons.Default.Tune, null) }
                RailButton(selectedPanel == TerminalPanel.SEARCH, { onTogglePanel(TerminalPanel.SEARCH) }, "搜索") { Icon(Icons.Default.Search, null) }
                RailButton(selectedPanel == TerminalPanel.SNIPPETS, { onTogglePanel(TerminalPanel.SNIPPETS) }, "快捷命令") { Icon(Icons.Default.Code, null) }
            }
            Box {
                IconButton(onClick = { onShowMoreMenuChange(true) }) { Icon(Icons.Default.MoreVert, "更多") }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { onShowMoreMenuChange(false) }) {
                    TerminalMoreMenuItems(
                        selectionMode = selectionMode,
                        hasHardwareKeyboard = false,
                        forceExtraKeys = false,
                        onDismiss = { onShowMoreMenuChange(false) },
                        onPaste = onPaste,
                        onSelectText = onSelectText,
                        onForwards = onForwards,
                        onFont = onFont,
                        onDisconnect = onDisconnect,
                        onToggleExtraKeys = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun RailButton(
    selected: Boolean,
    onClick: () -> Unit,
    description: String,
    icon: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp).padding(3.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(
            Modifier.fillMaxSize().clearAndSetSemantics {
                contentDescription = description
                this.selected = selected
            },
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}

@Composable
private fun LandscapeContextPanel(
    panel: TerminalPanel,
    hostSessions: List<ManagedSessionState>,
    current: ManagedSessionState?,
    activeId: SessionId?,
    snippets: List<CommandSnippet>,
    searchText: String,
    searchResult: Pair<Int, Int>,
    searchCaseSensitive: Boolean,
    hasSelection: Boolean,
    renderingDelayed: Boolean,
    onClose: () -> Unit,
    onSelectSession: (SessionId) -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: (SessionId) -> Unit,
    onReconnect: (SessionId) -> Unit,
    onCancelReconnect: (SessionId) -> Unit,
    onUnlockVault: () -> Unit,
    onCredentials: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchPrevious: () -> Unit,
    onSearchNext: () -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onUseSnippet: (CommandSnippet) -> Unit,
    onManageSnippets: () -> Unit,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onCancelSelection: () -> Unit,
) {
    Surface(Modifier.widthIn(min = 248.dp, max = 300.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (panel) {
                        TerminalPanel.SESSIONS -> "会话"
                        TerminalPanel.SEARCH -> "搜索"
                        TerminalPanel.SNIPPETS -> "快捷命令"
                        TerminalPanel.SELECTION -> "文本选择"
                        TerminalPanel.NONE -> ""
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭面板") }
            }
            when (panel) {
                TerminalPanel.SESSIONS -> LandscapeSessionsPanel(
                    hostSessions, current, activeId, renderingDelayed, onSelectSession, onNewSession,
                    onCloseSession, onReconnect, onCancelReconnect, onUnlockVault, onCredentials,
                )
                TerminalPanel.SEARCH -> LandscapeSearchPanel(
                    searchText, searchResult, searchCaseSensitive, onQueryChange,
                    onSearchPrevious, onSearchNext, onCaseSensitiveChange,
                )
                TerminalPanel.SNIPPETS -> LandscapeSnippetsPanel(snippets, onUseSnippet, onManageSnippets)
                TerminalPanel.SELECTION -> LandscapeSelectionPanel(hasSelection, onCopy, onSelectAll, onCancelSelection)
                TerminalPanel.NONE -> Unit
            }
        }
    }
}

@Composable
private fun LandscapeSessionsPanel(
    sessions: List<ManagedSessionState>,
    current: ManagedSessionState?,
    activeId: SessionId?,
    renderingDelayed: Boolean,
    onSelect: (SessionId) -> Unit,
    onNew: () -> Unit,
    onClose: (SessionId) -> Unit,
    onReconnect: (SessionId) -> Unit,
    onCancelReconnect: (SessionId) -> Unit,
    onUnlockVault: () -> Unit,
    onCredentials: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Button(onClick = onNew, enabled = current != null, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text("新建会话")
            }
        }
        items(sessions, key = { it.id.value }) { session ->
            Surface(
                onClick = { onSelect(session.id) },
                color = if (session.id == activeId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(connectionColor(session.connection), CircleShape))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(session.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(connectionLabel(session.connection), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onClose(session.id) }) { Icon(Icons.Default.Close, "关闭 ${session.displayName}") }
                }
            }
        }
        current?.let { session ->
            item {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (renderingDelayed) Text("大量输出处理中，连接仍保持", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                    if (session.needsVaultUnlock) TextButton(onClick = onUnlockVault) { Icon(Icons.Default.Lock, null); Text("解锁凭据保险库") }
                    when (val connection = session.connection) {
                        ConnectionState.Connecting -> {
                            Text(session.stage.connectingLabel(), style = MaterialTheme.typography.bodySmall)
                            if (session.reconnectAttempt != null) TextButton(onClick = { onCancelReconnect(session.id) }) { Text("取消自动重连") }
                        }
                        is ConnectionState.Error -> {
                            Text(connection.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Row { TextButton(onClick = onCredentials) { Text("重新输入") }; TextButton(onClick = { onReconnect(session.id) }) { Text("重连") } }
                        }
                        is ConnectionState.Disconnected -> {
                            Text(connection.reason, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onReconnect(session.id) }) { Icon(Icons.Default.Refresh, null); Text("重新连接") }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeSearchPanel(
    query: String,
    result: Pair<Int, Int>,
    caseSensitive: Boolean,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, onQueryChange, Modifier.fillMaxWidth(), placeholder = { Text("搜索终端输出") }, singleLine = true)
        Text(if (result.second == 0) "没有匹配" else "${result.first + 1} / ${result.second}", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onPrevious, Modifier.weight(1f), enabled = query.isNotEmpty()) { Text("上一个") }
            OutlinedButton(onNext, Modifier.weight(1f), enabled = query.isNotEmpty()) { Text("下一个") }
        }
        OutlinedButton(onClick = { onCaseSensitiveChange(!caseSensitive) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (caseSensitive) "区分大小写：开" else "区分大小写：关")
        }
    }
}

@Composable
private fun LandscapeSnippetsPanel(
    snippets: List<CommandSnippet>,
    onSelect: (CommandSnippet) -> Unit,
    onManage: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text("管理快捷命令") } }
        if (snippets.isEmpty()) item { Text("当前主机没有可用命令", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(snippets, key = CommandSnippet::id) { snippet ->
            Surface(onClick = { onSelect(snippet) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(snippet.title, style = MaterialTheme.typography.titleSmall)
                    Text(snippet.command, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun LandscapeSelectionPanel(
    hasSelection: Boolean,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (hasSelection) "已选择文本" else "长按或拖动以选择文本", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onCopy, enabled = hasSelection, modifier = Modifier.fillMaxWidth()) { Text("复制") }
        OutlinedButton(onClick = onSelectAll, modifier = Modifier.fillMaxWidth()) { Text("全选") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消选择") }
    }
}

@Composable
private fun LandscapeExtraKeys(
    keys: List<ExtraKeyId>,
    ctrlArmed: Boolean,
    onSend: (ByteArray) -> Unit,
    onShowKeyboard: () -> Unit,
    onArmCtrl: () -> Unit,
) {
    Surface(Modifier.width(160.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            keys.chunked(2).forEach { rowKeys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowKeys.forEach { key ->
                        val action = when (key) {
                            ExtraKeyId.KEYBOARD -> onShowKeyboard
                            ExtraKeyId.CTRL -> onArmCtrl
                            else -> ({ key.sequence?.let { onSend(it.encodeToByteArray()) }; Unit })
                        }
                        OutlinedButton(
                            onClick = action,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text(
                                if (key == ExtraKeyId.CTRL && ctrlArmed) "Ctrl…" else key.label,
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    if (rowKeys.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PortraitTerminalStatus(
    current: ManagedSessionState?,
    renderingDelayed: Boolean,
    onUnlockVault: () -> Unit,
    onCancelReconnect: (SessionId) -> Unit,
    onCredentials: () -> Unit,
    onReconnect: (SessionId) -> Unit,
) {
    if (current?.needsVaultUnlock == true) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Text("保险库已锁定，重连需要验证身份", Modifier.weight(1f).padding(horizontal = 8.dp))
            TextButton(onClick = onUnlockVault) { Text("解锁") }
        }
    }
    if (renderingDelayed) {
        Text(
            "终端正在处理大量输出，SSH 连接仍保持中…",
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    when (val connection = current?.connection ?: ConnectionState.Idle) {
        ConnectionState.Connecting -> Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp).padding(end = 6.dp))
            Text(if (current?.reconnectAttempt == null) current?.stage?.connectingLabel() ?: "正在建立安全连接…" else "正在执行第 ${current.reconnectAttempt}/3 次自动重连…")
            if (current?.reconnectAttempt != null) TextButton(onClick = { onCancelReconnect(current.id) }) { Text("取消") }
        }
        is ConnectionState.Error -> Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(connection.message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onCredentials) { Text("重新输入") }
            current?.let { TextButton(onClick = { onReconnect(it.id) }) { Text("重连") } }
        }
        is ConnectionState.Disconnected -> Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(connection.reason, Modifier.weight(1f))
            current?.let { TextButton(onClick = { onReconnect(it.id) }) { Text("重新连接") } }
        }
        else -> Unit
    }
}

private fun ManagedSessionState.credentialProfile(): HostProfile =
    if (credentialRole == CredentialRole.JUMP) jumpProfile ?: profile else profile

private fun ManagedSessionState.credentialSubjectLabel(): String = when {
    credentialRole == CredentialRole.JUMP -> "跳板机"
    jumpProfile != null -> "目标机"
    else -> "SSH 服务器"
}

private fun HostKeyRequest.subjectLabel(): String = when (subject) {
    HostKeySubject.JUMP -> "跳板机"
    HostKeySubject.TARGET -> "目标服务器"
}

private fun ConnectionStage.connectingLabel(): String = when (this) {
    ConnectionStage.JUMP_AUTH -> "正在验证跳板机身份…"
    ConnectionStage.JUMP_HOST_KEY -> "正在确认跳板机指纹…"
    ConnectionStage.TARGET_AUTH -> "正在验证目标机身份…"
    ConnectionStage.TARGET_HOST_KEY -> "正在确认目标机指纹…"
    ConnectionStage.READY -> "正在建立安全连接…"
}

@Composable
private fun TerminalSearchBar(
    query: String,
    result: Pair<Int, Int>,
    caseSensitive: Boolean,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(query, onQueryChange, Modifier.weight(1f), placeholder = { Text("搜索终端输出") }, singleLine = true)
        Text(if (result.second == 0) "0/0" else "${result.first + 1}/${result.second}", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = onPrevious, enabled = query.isNotEmpty()) { Text("↑") }
        OutlinedButton(onClick = onNext, enabled = query.isNotEmpty()) { Text("↓") }
        OutlinedButton(onClick = { onCaseSensitiveChange(!caseSensitive) }) { Text(if (caseSensitive) "Aa✓" else "Aa") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭搜索") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetSheet(
    snippets: List<CommandSnippet>,
    onSelect: (CommandSnippet) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("快捷命令", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onManage) { Text("管理") }
        }
        if (snippets.isEmpty()) {
            Text("当前主机没有可用的快捷命令", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().widthIn(max = 720.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp, 12.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(snippets, key = CommandSnippet::id) { snippet ->
                    androidx.compose.material3.Card(onClick = { onSelect(snippet) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(snippet.title, style = MaterialTheme.typography.titleSmall)
                            Text("${snippet.groupName}${if (snippet.executeImmediately) " · 确认后执行" else " · 填入终端"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(snippet.command, maxLines = 2, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SnippetVariablesDialog(
    snippet: CommandSnippet,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    val names = remember(snippet.id, snippet.command) { requiredSnippetInputs(snippet.command) }
    var values by remember(snippet.id, snippet.command) { mutableStateOf(names.associateWith { "" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("填写命令变量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEach { name ->
                    OutlinedTextField(
                        value = values[name].orEmpty(),
                        onValueChange = { value -> values = values + (name to value) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(name) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(values) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SelectionKeys(
    hasSelection: Boolean,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (hasSelection) "已选择文本" else "长按或拖动选择文本",
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCopy, enabled = hasSelection) { Text("复制") }
        OutlinedButton(onClick = onSelectAll) { Text("全选") }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

@Composable
private fun FontSizeDialog(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("终端字体大小") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前大小：$fontSize", color = MaterialTheme.colorScheme.primary)
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { onFontSizeChange(it.roundToInt()) },
                    valueRange = MIN_TERMINAL_FONT_SIZE.toFloat()..MAX_TERMINAL_FONT_SIZE.toFloat(),
                    steps = MAX_TERMINAL_FONT_SIZE - MIN_TERMINAL_FONT_SIZE - 1,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(
                        onClick = { onFontSizeChange((fontSize - 1).coerceAtLeast(MIN_TERMINAL_FONT_SIZE)) },
                        enabled = fontSize > MIN_TERMINAL_FONT_SIZE,
                    ) { Text("−") }
                    OutlinedButton(onClick = { onFontSizeChange(DEFAULT_TERMINAL_FONT_SIZE) }) { Text("默认") }
                    OutlinedButton(
                        onClick = { onFontSizeChange((fontSize + 1).coerceAtMost(MAX_TERMINAL_FONT_SIZE)) },
                        enabled = fontSize < MAX_TERMINAL_FONT_SIZE,
                    ) { Text("+") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun ExtraKeys(
    keys: List<ExtraKeyId>,
    ctrlArmed: Boolean,
    onSend: (ByteArray) -> Unit,
    onShowKeyboard: () -> Unit,
    onArmCtrl: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { key ->
            when (key) {
                ExtraKeyId.KEYBOARD -> OutlinedButton(onClick = onShowKeyboard) { Text(key.label) }
                ExtraKeyId.CTRL -> {
                    if (ctrlArmed) Button(onClick = onArmCtrl) { Text("Ctrl…") }
                    else OutlinedButton(onClick = onArmCtrl) { Text(key.label) }
                }
                else -> OutlinedButton(onClick = { key.sequence?.let { onSend(it.encodeToByteArray()) } }) { Text(key.label) }
            }
        }
    }
}

@Composable
internal fun CredentialDialog(
    authType: AuthType,
    subject: String = "SSH 服务器",
    onDismiss: () -> Unit,
    onConnect: (Credential, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var keyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var keyName by remember { mutableStateOf<String?>(null) }
    var rememberCredential by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "private_key"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取私钥")
            require(bytes.size <= 1024 * 1024) { "私钥文件不能超过 1MB" }
            keyBytes = bytes
            keyName = name
            error = null
        }.onFailure { error = it.message ?: "无法读取私钥" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (authType == AuthType.PASSWORD) "输入 $subject 密码" else "选择 $subject 私钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (authType == AuthType.PASSWORD) {
                    OutlinedTextField(password, { password = it; error = null }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                } else {
                    Button(onClick = { keyPicker.launch(arrayOf("application/*", "text/*")) }) { Text(keyName ?: "选择私钥文件") }
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text("私钥口令（可选）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberCredential, { rememberCredential = it })
                    Text("安全保存此凭据")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val credential = when (authType) {
                    AuthType.PASSWORD -> password.takeIf(String::isNotEmpty)?.let { Credential.Password(it.toCharArray()) }
                    AuthType.PRIVATE_KEY -> keyBytes?.let { Credential.PrivateKey(it, passphrase.takeIf(String::isNotEmpty)?.toCharArray(), keyName) }
                }
                if (credential == null) error = if (authType == AuthType.PASSWORD) "请输入密码" else "请选择私钥文件"
                else onConnect(credential, rememberCredential)
            }) { Text("连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private class TerminalController {
    private sealed interface RenderCommand {
        val generation: Long
        data class Reset(override val generation: Long) : RenderCommand
        data class Data(override val generation: Long, val bytes: ByteArray) : RenderCommand
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderCommands = Channel<RenderCommand>(TERMINAL_RENDER_QUEUE_CAPACITY)
    private var webView: WebView? = null
    private var ready = false
    private var readySignal = CompletableDeferred<Unit>()
    private var appearance: Pair<TerminalPalette, Int>? = null
    private var generation = 0L
    private var nextBatchId = 0L
    private var queuedBytes = 0
    private var currentAcknowledgement: Triple<Long, Long, CompletableDeferred<Unit>>? = null
    private var deferredCommand: RenderCommand? = null
    var onSelectionStateChanged: ((Boolean, Boolean) -> Unit)? = null
    var onCopied: ((Int) -> Unit)? = null
    var onSearchResults: ((Int, Int) -> Unit)? = null
    var onOpenLink: ((String) -> Unit)? = null
    var onCtrlArmed: ((Boolean) -> Unit)? = null
    var onRenderingDelayed: ((Boolean) -> Unit)? = null

    init {
        scope.launch { renderLoop() }
    }

    fun attach(view: WebView) {
        if (webView !== view) {
            currentAcknowledgement?.third?.complete(Unit)
            currentAcknowledgement = null
            ready = false
            readySignal = CompletableDeferred()
        }
        webView = view
    }

    fun markReady() {
        ready = true
        readySignal.complete(Unit)
        applyAppearance()
    }

    fun close() {
        currentAcknowledgement?.third?.complete(Unit)
        currentAcknowledgement = null
        webView = null
        ready = false
        scope.cancel()
    }

    suspend fun write(bytes: ByteArray) {
        val currentGeneration = generation
        splitTerminalOutput(bytes).forEach { chunk ->
            queuedBytes += chunk.size
            updateRenderingDelay()
            try {
                renderCommands.send(RenderCommand.Data(currentGeneration, chunk))
            } catch (error: Throwable) {
                releaseQueuedBytes(chunk.size)
                throw error
            }
        }
    }

    suspend fun reset() {
        generation += 1
        renderCommands.send(RenderCommand.Reset(generation))
    }

    fun paste(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        writeInput(text, webView)
    }

    fun pasteText(text: String) = writeInput(text, webView)

    fun setAppearance(palette: TerminalPalette, fontSize: Int) {
        appearance = palette to fontSize
        applyAppearance()
    }

    fun enterSelectionMode() = evaluate("window.sshTerminal.enterSelectionMode()")
    fun selectAll() = evaluate("window.sshTerminal.selectAll()")
    fun copySelection() = evaluate("window.sshTerminal.copySelection()")
    fun clearSelection() = evaluate("window.sshTerminal.clearSelection()")
    fun search(query: String, backwards: Boolean, caseSensitive: Boolean) =
        evaluate("window.sshTerminal.search(${JSONObject.quote(query)},$backwards,$caseSensitive)")
    fun clearSearch() = evaluate("window.sshTerminal.clearSearch()")
    fun setImeVisible(visible: Boolean) = evaluate("window.sshTerminal.setImeVisible($visible)")
    fun armCtrl() {
        evaluate("window.sshTerminal.armCtrl()")
        focusAndShowKeyboard()
    }

    fun selectionChanged(active: Boolean, hasSelection: Boolean) {
        onSelectionStateChanged?.invoke(active, hasSelection)
    }

    fun copied(characterCount: Int) {
        onCopied?.invoke(characterCount)
    }

    fun searchResults(index: Int, total: Int) = onSearchResults?.invoke(index, total)
    fun openLink(uri: String) = onOpenLink?.invoke(uri)
    fun ctrlArmed(armed: Boolean) = onCtrlArmed?.invoke(armed)

    fun outputProcessed(processedGeneration: Long, batchId: Long) {
        val acknowledgement = currentAcknowledgement ?: return
        if (acknowledgement.first == processedGeneration && acknowledgement.second == batchId) {
            acknowledgement.third.complete(Unit)
        }
    }

    fun focusAndShowKeyboard() {
        val view = webView ?: return
        view.requestFocus(View.FOCUS_DOWN)
        view.evaluateJavascript("window.sshTerminal && window.sshTerminal.focusForIme()", null)
        view.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.windowInsetsController?.show(AndroidWindowInsets.Type.ime())
            }
            val inputMethod = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethod.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 80)
    }

    fun hideKeyboard() {
        val view = webView ?: return
        setImeVisible(false)
        view.clearFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.hide(AndroidWindowInsets.Type.ime())
        }
        val inputMethod = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethod.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private suspend fun renderLoop() {
        while (true) {
            val command = deferredCommand?.also { deferredCommand = null } ?: renderCommands.receive()
            if (command.generation != generation) {
                if (command is RenderCommand.Data) releaseQueuedBytes(command.bytes.size)
                continue
            }
            when (command) {
                is RenderCommand.Reset -> {
                    awaitReady()
                    val acknowledgement = CompletableDeferred<Unit>()
                    currentAcknowledgement = Triple(command.generation, RESET_BATCH_ID, acknowledgement)
                    webView?.evaluateJavascript("window.sshTerminal.resetOutput(${command.generation})", null)
                    acknowledgement.await()
                    currentAcknowledgement = null
                }
                is RenderCommand.Data -> renderData(command)
            }
        }
    }

    private suspend fun renderData(first: RenderCommand.Data) {
        val renderGeneration = first.generation
        delay(8)
        if (renderGeneration != generation) {
            releaseQueuedBytes(first.bytes.size)
            return
        }
        val output = ByteArrayOutputStream(MAX_TERMINAL_RENDER_BATCH_BYTES)
        output.write(first.bytes)
        while (output.size() < MAX_TERMINAL_RENDER_BATCH_BYTES) {
            val next = renderCommands.tryReceive().getOrNull() ?: break
            if (next !is RenderCommand.Data || next.generation != generation || output.size() + next.bytes.size > MAX_TERMINAL_RENDER_BATCH_BYTES) {
                deferredCommand = next
                break
            }
            output.write(next.bytes)
        }
        val batch = output.toByteArray()
        awaitReady()
        val batchId = ++nextBatchId
        val acknowledgement = CompletableDeferred<Unit>()
        currentAcknowledgement = Triple(renderGeneration, batchId, acknowledgement)
        val warning = scope.launch {
            delay(1_000)
            onRenderingDelayed?.invoke(true)
        }
        val base64 = withContext(Dispatchers.Default) { Base64.encodeToString(batch, Base64.NO_WRAP) }
        webView?.evaluateJavascript("window.sshTerminal.writeBase64($renderGeneration,$batchId,'$base64')", null)
        acknowledgement.await()
        warning.cancel()
        currentAcknowledgement = null
        releaseQueuedBytes(batch.size)
    }

    private suspend fun awaitReady() {
        if (!ready || webView == null) readySignal.await()
    }

    private fun releaseQueuedBytes(count: Int) {
        queuedBytes = (queuedBytes - count).coerceAtLeast(0)
        updateRenderingDelay()
    }

    private fun updateRenderingDelay() {
        when {
            queuedBytes >= TERMINAL_RENDER_WARNING_BYTES -> onRenderingDelayed?.invoke(true)
            queuedBytes <= TERMINAL_RENDER_RECOVERED_BYTES -> onRenderingDelayed?.invoke(false)
        }
    }

    private fun writeInput(text: String, view: WebView?) {
        val base64 = Base64.encodeToString(text.encodeToByteArray(), Base64.NO_WRAP)
        view?.evaluateJavascript("window.sshTerminal.pasteBase64('$base64')", null)
    }

    private fun evaluate(script: String) {
        val view = webView ?: return
        if (ready) view.post { view.evaluateJavascript(script, null) }
    }

    private fun applyAppearance() {
        val view = webView ?: return
        val (palette, fontSize) = appearance ?: return
        view.setBackgroundColor(Color.parseColor(palette.background))
        if (!ready) return
        val theme = JSONObject().apply {
            put("background", palette.background)
            put("foreground", palette.foreground)
            put("cursor", palette.cursor)
            put("cursorAccent", palette.cursorAccent)
            put("selectionBackground", palette.selectionBackground)
            put("black", palette.black)
            put("red", palette.red)
            put("green", palette.green)
            put("yellow", palette.yellow)
            put("blue", palette.blue)
            put("magenta", palette.magenta)
            put("cyan", palette.cyan)
            put("white", palette.white)
            put("brightBlack", palette.brightBlack)
            put("brightRed", palette.brightRed)
            put("brightGreen", palette.brightGreen)
            put("brightYellow", palette.brightYellow)
            put("brightBlue", palette.brightBlue)
            put("brightMagenta", palette.brightMagenta)
            put("brightCyan", palette.brightCyan)
            put("brightWhite", palette.brightWhite)
        }
        val payload = JSONObject().put("theme", theme).put("fontSize", fontSize)
        view.post { view.evaluateJavascript("window.sshTerminal.setAppearance($payload)", null) }
    }

    private companion object {
        const val RESET_BATCH_ID = -1L
    }
}

private class TerminalBridge(
    private val view: WebView,
    private val controller: TerminalController,
    private val inputCallback: (ByteArray) -> Unit,
    private val resizeCallback: (Int, Int) -> Unit,
) {
    @JavascriptInterface fun onInput(base64: String) {
        runCatching { Base64.decode(base64, Base64.DEFAULT) }.onSuccess(inputCallback)
    }
    @JavascriptInterface fun onResize(columns: Int, rows: Int): Unit = resizeCallback(columns, rows)
    @JavascriptInterface fun onRequestKeyboard() {
        view.post(controller::focusAndShowKeyboard)
    }
    @JavascriptInterface fun onHideKeyboard() {
        view.post(controller::hideKeyboard)
    }
    @JavascriptInterface fun onSelectionChanged(active: Boolean, hasSelection: Boolean) {
        view.post { controller.selectionChanged(active, hasSelection) }
    }
    @JavascriptInterface fun onCopySelection(base64: String) {
        runCatching { Base64.decode(base64, Base64.DEFAULT).decodeToString() }
            .onSuccess { selection ->
                if (selection.isEmpty()) return@onSuccess
                val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SSH terminal", selection))
                view.post { controller.copied(selection.length) }
            }
    }
    @JavascriptInterface fun onSearchResults(index: Int, total: Int) {
        view.post { controller.searchResults(index, total) }
    }
    @JavascriptInterface fun onOpenLink(uri: String) {
        if (uri.startsWith("https://", true) || uri.startsWith("http://", true)) {
            view.post { controller.openLink(uri) }
        }
    }
    @JavascriptInterface fun onCtrlArmed(armed: Boolean) {
        view.post { controller.ctrlArmed(armed) }
    }
    @JavascriptInterface fun onOutputProcessed(generation: Long, batchId: Long) {
        view.post { controller.outputProcessed(generation, batchId) }
    }
    @JavascriptInterface fun onReady(columns: Int, rows: Int) {
        view.post {
            controller.markReady()
            resizeCallback(columns, rows)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalWebView(
    controller: TerminalController,
    initialBackground: String,
    onInput: (ByteArray) -> Unit,
    onResize: (Int, Int) -> Unit,
    onSurfaceCreated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // WebView 的 JS bridge 生命周期长于一次 Compose 重组，必须间接读取最新回调；
    // 否则切换会话后 bridge 仍会把输入和 resize 发给旧会话。
    val inputState = rememberUpdatedState(onInput)
    val resizeState = rememberUpdatedState(onResize)
    val surfaceCreatedState = rememberUpdatedState(onSurfaceCreated)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            WebView(context).apply {
                // WebView 绑定时立即应用已保存的终端背景色，避免首帧深色闪烁。
                setBackgroundColor(Color.parseColor(initialBackground))
                isFocusable = true
                isFocusableInTouchMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        assetLoader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.host != "appassets.androidplatform.net"
                }
                controller.attach(this)
                addJavascriptInterface(
                    TerminalBridge(
                        this,
                        controller,
                        { inputState.value(it) },
                        { columns, rows -> resizeState.value(columns, rows) },
                    ),
                    "AndroidTerminal",
                )
                post { surfaceCreatedState.value() }
                loadUrl("https://appassets.androidplatform.net/assets/terminal/index.html")
            }
        },
        update = { controller.attach(it) },
    )
}

@Composable
private fun TerminalSystemBarsEffect(isLandscape: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, isLandscape) {
        if (!isLandscape) return@DisposableEffect onDispose {}
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        val previousCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode
        } else {
            null
        }
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = previousBehavior
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && previousCutoutMode != null) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = previousCutoutMode }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 顶部栏与沉浸控制条共用的「更多」菜单内容。 */
@Composable
private fun TerminalMoreMenuItems(
    selectionMode: Boolean,
    hasHardwareKeyboard: Boolean,
    forceExtraKeys: Boolean,
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    onSelectText: () -> Unit,
    onForwards: () -> Unit,
    onFont: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleExtraKeys: () -> Unit,
) {
    DropdownMenuItem(text = { Text("粘贴") }, onClick = { onDismiss(); onPaste() }, enabled = !selectionMode)
    DropdownMenuItem(text = { Text("选择文本") }, onClick = { onDismiss(); onSelectText() }, enabled = !selectionMode)
    DropdownMenuItem(text = { Text("端口转发") }, onClick = { onDismiss(); onForwards() })
    DropdownMenuItem(text = { Text("字体大小") }, onClick = { onDismiss(); onFont() })
    DropdownMenuItem(text = { Text("断开当前会话") }, onClick = { onDismiss(); onDisconnect() })
    if (hasHardwareKeyboard) {
        DropdownMenuItem(text = { Text(if (forceExtraKeys) "隐藏扩展键" else "显示扩展键") }, onClick = { onDismiss(); onToggleExtraKeys() })
    }
}
