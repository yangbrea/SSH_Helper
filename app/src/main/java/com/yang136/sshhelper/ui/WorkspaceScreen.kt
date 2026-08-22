package com.yang136.sshhelper.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.TransferStatus
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.SessionFeature
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ui.theme.TerminalPalette

enum class WorkspaceTab { TERMINAL, FILES }

/**
 * Per-host workspace with a bottom navigation bar that switches between two independent SSH
 * channels: the terminal tab owns its own SHELL session(s), the file tab lazily opens its own
 * SFTP-only session. Closing either channel never affects the other or in-flight transfers.
 */
@Composable
fun WorkspaceScreen(
    hostId: Long,
    initialTab: WorkspaceTab,
    initialSessionId: SessionId?,
    initialFilesSessionId: SessionId?,
    hosts: List<HostProfile>,
    sessionsViewModel: SessionsViewModel,
    snippets: List<com.yang136.sshhelper.data.CommandSnippet>,
    settings: AppSettings,
    terminalPalette: TerminalPalette,
    onFontSizeChange: (Int) -> Unit,
    onManageSnippets: () -> Unit,
    onOpenForwards: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onUnlockVault: () -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as SshHelperApplication
    val container = app.container
    val profile = hosts.firstOrNull { it.id == hostId }
    val sessions by sessionsViewModel.sessions.collectAsStateWithLifecycle()
    val hostSessions = sessions.filter { it.profile.id == hostId }
    val transfers by container.transferManager.jobs.collectAsStateWithLifecycle()

    var tab by rememberSaveable(hostId) { mutableStateOf(initialTab) }
    var terminalSessionId by remember(hostId) { mutableStateOf(initialSessionId) }
    var sftpSessionId by remember(hostId) { mutableStateOf(initialFilesSessionId) }
    var channelError by remember(hostId) { mutableStateOf<String?>(null) }

    LaunchedEffect(tab, hostId, hostSessions.isEmpty()) {
        channelError = null
        when (tab) {
            WorkspaceTab.TERMINAL -> if (hostSessions.isEmpty()) {
                terminalSessionId = profile?.let { sessionsViewModel.create(it, SessionFeature.SHELL) }
                if (profile != null && terminalSessionId == null) channelError = "已达到会话上限（8 个）"
            }
            WorkspaceTab.FILES -> if (sftpSessionId == null) {
                sftpSessionId = profile?.let { sessionsViewModel.create(it, SessionFeature.SFTP) }
                if (profile != null && sftpSessionId == null) channelError = "已达到会话上限（8 个）"
            }
        }
    }

    // Both panels stay composed so their state (terminal viewport, browser path, dialogs) survives
    // tab switches; only visibility fades. Back handling follows the visible panel.
    val terminalAlpha by animateFloatAsState(if (tab == WorkspaceTab.TERMINAL) 1f else 0f, label = "terminalAlpha")
    val filesAlpha by animateFloatAsState(if (tab == WorkspaceTab.FILES) 1f else 0f, label = "filesAlpha")

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = terminalAlpha }) {
                TerminalScreen(
                    initialSessionId = terminalSessionId?.value,
                    hostId = hostId,
                    sessionsViewModel = sessionsViewModel,
                    snippets = snippets,
                    settings = settings,
                    terminalPalette = terminalPalette,
                    onFontSizeChange = onFontSizeChange,
                    onManageSnippets = onManageSnippets,
                    onSwitchToFiles = { tab = WorkspaceTab.FILES },
                    onOpenForwards = onOpenForwards,
                    onOpenSettings = onOpenSettings,
                    onUnlockVault = onUnlockVault,
                    onBack = onBack,
                    backEnabled = tab == WorkspaceTab.TERMINAL,
                )
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = filesAlpha }) {
                val sftpId = sftpSessionId
                if (sftpId != null) {
                    val sftpViewModel: SftpViewModel = viewModel(
                        key = "sftp-${sftpId.value}",
                        factory = SftpViewModel.factory(container, sftpId),
                    )
                    SftpScreen(
                        viewModel = sftpViewModel,
                        onBack = onBack,
                        onSwitchToTerminal = { tab = WorkspaceTab.TERMINAL },
                        onUnlockVault = onUnlockVault,
                        backEnabled = tab == WorkspaceTab.FILES,
                    )
                } else {
                    FilesChannelPlaceholder(error = channelError, onRetry = {
                        channelError = null
                        sftpSessionId = profile?.let { sessionsViewModel.create(it, SessionFeature.SFTP) }
                    })
                }
            }
        }
        WorkspaceBottomBar(
            tab = tab,
            terminalConnected = hostSessions.any { it.connection is ConnectionState.Connected },
            activeTransferCount = transfers.count { it.status.isWorkspaceActive() },
            onSelect = { tab = it },
        )
    }
}

@Composable
private fun FilesChannelPlaceholder(
    error: String?,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Folder, null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (error != null) "无法打开文件通道" else "正在打开文件通道…", style = MaterialTheme.typography.titleMedium)
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
private fun WorkspaceBottomBar(
    tab: WorkspaceTab,
    terminalConnected: Boolean,
    activeTransferCount: Int,
    onSelect: (WorkspaceTab) -> Unit,
) {
    Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        NavigationBar(containerColor = Color.Transparent) {
            NavigationBarItem(
                selected = tab == WorkspaceTab.TERMINAL,
                onClick = { onSelect(WorkspaceTab.TERMINAL) },
                icon = {
                    Box {
                        Icon(
                            if (tab == WorkspaceTab.TERMINAL) Icons.Filled.Terminal else Icons.Outlined.Terminal,
                            contentDescription = "终端",
                        )
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 7.dp, y = (-5).dp)
                                .size(9.dp)
                                .background(
                                    color = if (terminalConnected) Color(0xFF2DD4BF) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = CircleShape,
                                ),
                        )
                    }
                },
                label = { Text("终端") },
            )
            NavigationBarItem(
                selected = tab == WorkspaceTab.FILES,
                onClick = { onSelect(WorkspaceTab.FILES) },
                icon = {
                    BadgedBox(badge = {
                        if (activeTransferCount > 0) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(activeTransferCount.toString()) }
                        }
                    }) {
                        Icon(
                            if (tab == WorkspaceTab.FILES) Icons.Filled.Folder else Icons.Outlined.Folder,
                            contentDescription = "文件管理",
                        )
                    }
                },
                label = { Text("文件管理") },
            )
        }
    }
}

private fun TransferStatus.isWorkspaceActive(): Boolean = this in setOf(
    TransferStatus.QUEUED,
    TransferStatus.RUNNING,
    TransferStatus.WAITING_NETWORK,
    TransferStatus.WAITING_UNLOCK,
)
