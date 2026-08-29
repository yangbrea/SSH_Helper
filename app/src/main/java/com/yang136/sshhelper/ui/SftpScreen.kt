package com.yang136.sshhelper.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.ConflictPolicy
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.LocalRootEntity
import com.yang136.sshhelper.data.TransferDirection
import com.yang136.sshhelper.data.TransferStatus
import com.yang136.sshhelper.sftp.LocalFile
import com.yang136.sshhelper.sftp.RemoteFile
import com.yang136.sshhelper.sftp.RemoteFileType
import com.yang136.sshhelper.sftp.TransferJob
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.CredentialRole
import com.yang136.sshhelper.ssh.HostKeyIssue
import com.yang136.sshhelper.ssh.HostKeySubject
import com.yang136.sshhelper.preview.PreviewKind
import com.yang136.sshhelper.preview.PreviewPlaybackManager
import com.yang136.sshhelper.preview.SftpImage
import com.yang136.sshhelper.preview.previewKind
import com.yang136.sshhelper.ui.design.SshStatusBadge
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.design.SshTopAppBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat

private enum class MobilePane { REMOTE, LOCAL }
private enum class PaneMode { BROWSE, SEARCH, SELECTION }

@Composable
private fun CompactPaneSwitcher(pane: MobilePane, enabled: Boolean, onPane: (MobilePane) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        listOf(MobilePane.REMOTE to "远程", MobilePane.LOCAL to "本地").forEachIndexed { index, (item, label) ->
            SegmentedButton(
                selected = pane == item,
                onClick = { onPane(item) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(
    viewModel: SftpViewModel,
    onBack: () -> Unit,
    onUnlockVault: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val roots by viewModel.localRoots.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var mobilePane by remember { mutableStateOf(MobilePane.REMOTE) }
    var remoteSearching by remember { mutableStateOf(false) }
    var localSearching by remember { mutableStateOf(false) }
    var showTransfers by remember { mutableStateOf(false) }
    var showCreateDirectory by remember { mutableStateOf(false) }
    var deletingRemote by remember { mutableStateOf(false) }
    var deletingLocal by remember { mutableStateOf(false) }
    var properties by remember { mutableStateOf<RemoteFile?>(null) }
    var preview by remember { mutableStateOf<RemotePreview?>(null) }
    var imagePreview by remember { mutableStateOf<RemoteFile?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf<String?>(null) }
    var conflictPreview by remember { mutableStateOf<Pair<RemotePreview, String>?>(null) }
    var confirmDiscardPreview by remember { mutableStateOf(false) }

    val handleBack = {
        when {
            state.selectedRemote.isNotEmpty() || state.selectedLocal.isNotEmpty() -> viewModel.clearSelection()
            mobilePane == MobilePane.REMOTE && remoteSearching -> {
                remoteSearching = false
                viewModel.setRemoteQuery("")
            }
            mobilePane == MobilePane.LOCAL && localSearching -> {
                localSearching = false
                viewModel.setLocalQuery("")
            }
            remoteSearching -> {
                remoteSearching = false
                viewModel.setRemoteQuery("")
            }
            localSearching -> {
                localSearching = false
                viewModel.setLocalQuery("")
            }
            else -> onBack()
        }
    }
    BackHandler(onBack = handleBack)
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }

    // Audio streams straight into ExoPlayer and images stream through Coil; everything
    // else keeps the in-memory preview path (text editing), which also performs sniffing.
    val openPreview: (RemoteFile) -> Unit = { file ->
        when (previewKind(file.name)) {
            PreviewKind.AUDIO -> viewModel.playback.start(file, session?.profile?.hostname ?: "")
            PreviewKind.IMAGE -> imagePreview = file
            else -> scope.launch {
                viewModel.preview(file).onSuccess { preview = it; editingText = it.editableText }
                    .onFailure { previewError = it.message }
            }
        }
    }

    val rootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "本地目录"
            viewModel.addLocalRoot(uri, name)
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        containerColor = imageAwareScaffoldColor(),
        topBar = {
            SshTopAppBar(
                title = session?.displayName ?: "SFTP 文件",
                subtitle = session?.connection.sftpLabel(),
                navigationIcon = { IconButton(onClick = handleBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showTransfers = true }) {
                        BadgedBox(badge = { val count = transfers.count { it.status.isActive() }; if (count > 0) Badge { Text(count.toString()) } }) {
                            Icon(Icons.Default.Download, "传输任务")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val active = transfers.filter { it.status.isActive() }
            if (active.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 5.dp) {
                    Column(Modifier.fillMaxWidth().clickable { showTransfers = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("传输状态", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            SshStatusBadge("${active.size} 个进行中", SshStatusTone.CONNECTING)
                        }
                        LinearProgressIndicator(progress = { active.map(TransferJob::progress).average().toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (session?.needsVaultUnlock == true) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Text("凭据保险库已锁定", Modifier.weight(1f).padding(horizontal = 10.dp))
                    Button(onClick = onUnlockVault) { Text("解锁") }
                }
            }
            val s = session
            if (s != null && s.needsCredential) {
                val credentialProfile = if (s.credentialRole == CredentialRole.JUMP) s.jumpProfile ?: s.profile else s.profile
                val subject = when {
                    s.credentialRole == CredentialRole.JUMP -> "跳板机"
                    s.jumpProfile != null -> "目标机"
                    else -> "SSH 服务器"
                }
                CredentialDialog(
                    authType = credentialProfile.authType,
                    subject = subject,
                    onDismiss = onBack,
                    onConnect = viewModel::connect,
                )
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth >= 840.dp) {
                    Row(Modifier.fillMaxSize()) {
                        RemotePane(viewModel, state, remoteSearching, { remoteSearching = it }, Modifier.weight(1f).fillMaxHeight(), onCreateDirectory = { showCreateDirectory = true }, onDelete = { deletingRemote = true }, onProperties = { properties = it }, onPreview = openPreview, onDownload = viewModel::downloadSelected)
                        LocalPane(viewModel, state, roots, localSearching, { localSearching = it }, Modifier.weight(1f).fillMaxHeight(), onAddRoot = { rootPicker.launch(null) }, onDelete = { deletingLocal = true }, onUpload = viewModel::uploadSelected)
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        CompactPaneSwitcher(
                            pane = mobilePane,
                            enabled = state.selectedRemote.isEmpty() && state.selectedLocal.isEmpty(),
                            onPane = { mobilePane = it },
                        )
                        if (mobilePane == MobilePane.REMOTE) {
                            RemotePane(viewModel, state, remoteSearching, { remoteSearching = it }, Modifier.weight(1f).fillMaxWidth(), onCreateDirectory = { showCreateDirectory = true }, onDelete = { deletingRemote = true }, onProperties = { properties = it }, onPreview = openPreview, onDownload = viewModel::downloadSelected)
                        } else {
                            LocalPane(viewModel, state, roots, localSearching, { localSearching = it }, Modifier.weight(1f).fillMaxWidth(), onAddRoot = { rootPicker.launch(null) }, onDelete = { deletingLocal = true }, onUpload = viewModel::uploadSelected)
                        }
                    }
                }
            }
        }
    }

    session?.hostKeyRequest?.let { request ->
        val subject = when (request.subject) {
            HostKeySubject.JUMP -> "跳板机"
            HostKeySubject.TARGET -> "目标服务器"
        }
        AlertDialog(
            onDismissRequest = { viewModel.respondToHostKey(false) },
            title = { Text(if (request.issue == HostKeyIssue.UNKNOWN) "确认${subject}身份" else "${subject}主机密钥已变化") },
            text = { Text("${request.hostname}:${request.port}\n${request.keyType}\n${request.fingerprint}") },
            confirmButton = { TextButton(onClick = { if (request.issue == HostKeyIssue.UNKNOWN) viewModel.respondToHostKey(true) else viewModel.forgetChangedHostKey() }) { Text(if (request.issue == HostKeyIssue.UNKNOWN) "信任并连接" else "清除旧指纹") } },
            dismissButton = { TextButton(onClick = { viewModel.respondToHostKey(false) }) { Text("取消") } },
        )
    }
    if (showCreateDirectory) NameDialog("新建远程目录", "目录名称", { showCreateDirectory = false }) { viewModel.createRemoteDirectory(it); showCreateDirectory = false }
    if (deletingRemote) ConfirmDeleteDialog(state.selectedRemote.size, { deletingRemote = false }) { viewModel.deleteSelectedRemote(); deletingRemote = false }
    if (deletingLocal) ConfirmDeleteDialog(state.selectedLocal.size, { deletingLocal = false }) { viewModel.deleteSelectedLocal(); deletingLocal = false }
    properties?.let { file -> RemotePropertiesDialog(file, onDismiss = { properties = null }, onApply = { mode, uid, gid -> viewModel.chmod(file, mode); viewModel.chown(file, uid, gid); properties = null }) }
    if (showTransfers) TransferSheet(transfers, viewModel, onDismiss = { showTransfers = false })
    preview?.let { item -> PreviewDialog(item, editingText, onTextChange = { editingText = it }, onDismiss = {
        if (editingText != null && editingText != item.editableText) confirmDiscardPreview = true
        else { item.bytes.fill(0); preview = null; editingText = null }
    }, onSave = { text -> scope.launch { viewModel.saveText(item, text, false).onSuccess { item.bytes.fill(0); preview = it; editingText = it.editableText }.onFailure { if (it.message == "REMOTE_CHANGED") conflictPreview = item to text else previewError = it.message } } }) }
    conflictPreview?.let { (item, text) -> AlertDialog(
        onDismissRequest = { conflictPreview = null },
        title = { Text("远端文件已变化") },
        text = { Column { Text("其他程序可能已经修改此文件。仍然覆盖可能丢失更改。"); TextButton(onClick = { scope.launch { viewModel.saveTextCopy(item, text).onSuccess { preview = it; editingText = it.editableText }; conflictPreview = null } }) { Text("另存副本") } } },
        confirmButton = { TextButton(onClick = { scope.launch { viewModel.saveText(item, text, true).onSuccess { preview = it; editingText = it.editableText }; conflictPreview = null } }) { Text("仍然覆盖") } },
        dismissButton = { TextButton(onClick = { conflictPreview = null; viewModel.refreshRemote() }) { Text("重新加载") } },
    ) }
    previewError?.let { message -> AlertDialog(onDismissRequest = { previewError = null }, title = { Text("无法预览") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { previewError = null }) { Text("知道了") } }) }
    if (confirmDiscardPreview) AlertDialog(
        onDismissRequest = { confirmDiscardPreview = false },
        title = { Text("放弃未保存的修改？") },
        text = { Text("关闭后，本次文本修改将无法恢复。") },
        confirmButton = { TextButton(onClick = { preview?.bytes?.fill(0); preview = null; editingText = null; confirmDiscardPreview = false }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscardPreview = false }) { Text("继续编辑") } },
    )
    val playbackState by viewModel.playback.state.collectAsStateWithLifecycle()
    if (playbackState.file != null) {
        AudioPreviewDialog(manager = viewModel.playback, onDismiss = { viewModel.playback.stop() })
    }
    imagePreview?.let { file ->
        ImagePreviewDialog(
            file = file,
            hostName = session?.profile?.hostname ?: "",
            imageLoader = viewModel.imageLoader,
            onDismiss = { imagePreview = null },
        )
    }
}

@Composable
private fun RemotePane(
    vm: SftpViewModel,
    state: SftpUiState,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    modifier: Modifier,
    onCreateDirectory: () -> Unit,
    onDelete: () -> Unit,
    onProperties: (RemoteFile) -> Unit,
    onPreview: (RemoteFile) -> Unit,
    onDownload: () -> Unit,
) {
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var pathDialog by remember { mutableStateOf(false) }
    var browseMenu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var renameFile by remember { mutableStateOf<RemoteFile?>(null) }
    var moveDialog by remember { mutableStateOf(false) }
    var copyDialog by remember { mutableStateOf(false) }
    var symlinkDialog by remember { mutableStateOf(false) }
    val mode = when { state.selectedRemote.isNotEmpty() -> PaneMode.SELECTION; searching -> PaneMode.SEARCH; else -> PaneMode.BROWSE }
    LaunchedEffect(state.selectedRemote.isNotEmpty()) {
        if (state.selectedRemote.isNotEmpty() && searching) {
            onSearchingChange(false)
            vm.setRemoteQuery("")
        }
    }
    Column(modifier.padding(horizontal = 8.dp)) {
        when (mode) {
            PaneMode.BROWSE -> CompactBrowseBar(
                leadingIcon = Icons.Default.ArrowUpward,
                leadingDescription = "上级",
                onLeading = vm::upRemote,
                path = state.remotePath,
                onPath = { pathDialog = true },
                onSearch = { onSearchingChange(true) },
                onRefresh = vm::refreshRemote,
                onMore = { browseMenu = true },
                menu = {
                    RemoteBrowseMenu(
                        expanded = browseMenu,
                        onDismiss = { browseMenu = false },
                        options = state.remoteView,
                        bookmarks = bookmarks,
                        fileSystemLabel = state.fileSystem?.let { fs -> "容量 ${formatSize(fs.used)} / ${formatSize(fs.size)} · 可用 ${formatSize(fs.available)}" },
                        onCreateDirectory = { browseMenu = false; onCreateDirectory() },
                        onCreateSymlink = { browseMenu = false; symlinkDialog = true },
                        onAddBookmark = { browseMenu = false; vm.addCurrentBookmark() },
                        onOpenBookmark = { browseMenu = false; vm.setPath(it) },
                        onRemoveBookmark = vm::removeBookmark,
                        onHidden = vm::setRemoteShowHidden,
                        onSort = vm::setRemoteSort,
                        onDirection = vm::toggleRemoteDirection,
                    )
                },
            )
            PaneMode.SEARCH -> CompactSearchBar(state.remoteView.query, vm::setRemoteQuery) {
                onSearchingChange(false)
                vm.setRemoteQuery("")
            }
            PaneMode.SELECTION -> CompactSelectionBar(
                count = state.selectedRemote.size,
                primaryLabel = "下载",
                primaryIcon = Icons.Default.ArrowDownward,
                onClear = vm::clearSelection,
                onPrimary = onDownload,
                onMore = { selectionMenu = true },
                menu = {
                    val selectedFile = state.remoteFiles.firstOrNull { it.path in state.selectedRemote }
                    DropdownMenu(selectionMenu, { selectionMenu = false }) {
                        if (state.selectedRemote.size == 1) {
                            DropdownMenuItem({ Text("属性") }, onClick = { selectionMenu = false; selectedFile?.let(onProperties) })
                            DropdownMenuItem({ Text("重命名") }, onClick = { selectionMenu = false; renameFile = selectedFile })
                        }
                        DropdownMenuItem({ Text("移动") }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }, onClick = { selectionMenu = false; moveDialog = true })
                        DropdownMenuItem({ Text("复制") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { selectionMenu = false; copyDialog = true })
                        DropdownMenuItem({ Text("删除") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { selectionMenu = false; onDelete() })
                    }
                },
            )
        }
        HorizontalDivider()
        if (state.remoteLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (state.error != null && state.remoteFiles.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("无法读取远程目录", style = MaterialTheme.typography.titleMedium); Text(state.error, color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::refreshRemote) { Text("重试") } } }
        else if (state.remoteFiles.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("远程目录为空") }
        else LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(vm.visibleRemoteFiles(), key = RemoteFile::path) { file ->
                RemoteFileRow(file, file.path in state.selectedRemote, onSelect = { vm.toggleRemote(file.path) }, onOpen = { if (file.type == RemoteFileType.DIRECTORY) vm.setPath(file.path) else onPreview(file) })
            }
        }
    }
    if (pathDialog) PathDialog("转到远程路径", state.remotePath, { pathDialog = false }) { vm.setPath(it); pathDialog = false }
    renameFile?.let { file -> NameDialog("重命名", "新名称", { renameFile = null }) { vm.renameRemote(file, it); renameFile = null } }
    if (moveDialog) NameDialog("移动到目录", "远程目标目录", { moveDialog = false }) { vm.moveSelectedRemote(it); moveDialog = false }
    if (copyDialog) NameDialog("复制到目录", "远程目标目录", { copyDialog = false }) { vm.copySelectedRemote(it); copyDialog = false }
    if (symlinkDialog) SymlinkDialog({ symlinkDialog = false }) { target, name -> vm.createSymlink(target, name); symlinkDialog = false }
}

@Composable
private fun LocalPane(
    vm: SftpViewModel,
    state: SftpUiState,
    roots: List<LocalRootEntity>,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    modifier: Modifier,
    onAddRoot: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
) {
    val listState = rememberLazyListState()
    var rootsMenu by remember { mutableStateOf(false) }
    var browseMenu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var createDirectory by remember { mutableStateOf(false) }
    var renameFile by remember { mutableStateOf<LocalFile?>(null) }
    val mode = when { state.selectedLocal.isNotEmpty() -> PaneMode.SELECTION; searching -> PaneMode.SEARCH; else -> PaneMode.BROWSE }
    LaunchedEffect(state.selectedLocal.isNotEmpty()) {
        if (state.selectedLocal.isNotEmpty() && searching) {
            onSearchingChange(false)
            vm.setLocalQuery("")
        }
    }
    Column(modifier.padding(horizontal = 8.dp)) {
        when (mode) {
            PaneMode.BROWSE -> CompactBrowseBar(
                leadingIcon = Icons.Default.ArrowUpward,
                leadingDescription = "上级",
                onLeading = vm::upLocal,
                leadingEnabled = state.localBackStack.isNotEmpty(),
                path = state.localRoot?.displayName ?: "选择本地目录",
                pathIcon = Icons.Default.Home,
                onPath = { rootsMenu = true },
                onSearch = { onSearchingChange(true) },
                onRefresh = vm::refreshLocal,
                refreshEnabled = state.localUri != null,
                onMore = { browseMenu = true },
                menu = {
                    DropdownMenu(rootsMenu, { rootsMenu = false }) {
                        roots.forEach { root -> DropdownMenuItem({ Text(root.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { rootsMenu = false; vm.chooseLocalRoot(root) }) }
                        DropdownMenuItem({ Text("授权新目录") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { rootsMenu = false; onAddRoot() })
                    }
                    LocalBrowseMenu(
                        expanded = browseMenu,
                        onDismiss = { browseMenu = false },
                        options = state.localView,
                        directoryEnabled = state.localUri != null,
                        onCreateDirectory = { browseMenu = false; createDirectory = true },
                        onHidden = vm::setLocalShowHidden,
                        onSort = vm::setLocalSort,
                        onDirection = vm::toggleLocalDirection,
                    )
                },
            )
            PaneMode.SEARCH -> CompactSearchBar(state.localView.query, vm::setLocalQuery) {
                onSearchingChange(false)
                vm.setLocalQuery("")
            }
            PaneMode.SELECTION -> CompactSelectionBar(
                count = state.selectedLocal.size,
                primaryLabel = "上传",
                primaryIcon = Icons.Default.ArrowUpward,
                onClear = vm::clearSelection,
                onPrimary = onUpload,
                onMore = { selectionMenu = true },
                menu = {
                    val selectedFile = state.localFiles.firstOrNull { it.uri.toString() in state.selectedLocal }
                    DropdownMenu(selectionMenu, { selectionMenu = false }) {
                        if (state.selectedLocal.size == 1) DropdownMenuItem({ Text("重命名") }, onClick = { selectionMenu = false; renameFile = selectedFile })
                        DropdownMenuItem({ Text("删除") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { selectionMenu = false; onDelete() })
                    }
                },
            )
        }
        HorizontalDivider()
        if (state.localRoot == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("尚未授权本地目录"); Button(onClick = onAddRoot) { Text("选择目录") } } }
        else if (state.localLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (state.localFiles.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("本地目录为空") }
        else LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(vm.visibleLocalFiles(), key = { it.uri.toString() }) { file ->
                LocalFileRow(file, file.uri.toString() in state.selectedLocal, onSelect = { vm.toggleLocal(file.uri) }, onOpen = { vm.openLocal(file) })
            }
        }
    }
    if (createDirectory) NameDialog("新建本地目录", "目录名称", { createDirectory = false }) { vm.createLocalDirectory(it); createDirectory = false }
    renameFile?.let { file -> NameDialog("重命名", "新名称", { renameFile = null }) { vm.renameLocal(file, it); renameFile = null } }
}

@Composable
private fun CompactBrowseBar(
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    leadingDescription: String,
    onLeading: () -> Unit,
    path: String,
    onPath: () -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    menu: @Composable () -> Unit,
    leadingEnabled: Boolean = true,
    refreshEnabled: Boolean = true,
    pathIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onLeading, enabled = leadingEnabled) { Icon(leadingIcon, leadingDescription) }
        Surface(
            modifier = Modifier.weight(1f).height(40.dp).clickable(onClick = onPath),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                pathIcon?.let { Icon(it, null, Modifier.size(18.dp)); Box(Modifier.size(6.dp)) }
                Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
        IconButton(onClick = onRefresh, enabled = refreshEnabled) { Icon(Icons.Default.Refresh, "刷新") }
        Box {
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "更多") }
            menu()
        }
    }
}

@Composable
private fun CompactSearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭搜索") }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("筛选当前目录") },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "清空") } },
        )
    }
}

@Composable
private fun CompactSelectionBar(
    count: Int,
    primaryLabel: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClear: () -> Unit,
    onPrimary: () -> Unit,
    onMore: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClear) { Icon(Icons.Default.Close, "取消选择") }
        Text("已选 $count 项", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onPrimary) { Icon(primaryIcon, null, Modifier.size(18.dp)); Text(primaryLabel, maxLines = 1, modifier = Modifier.padding(start = 4.dp)) }
        Box {
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "更多操作") }
            menu()
        }
    }
}

@Composable
private fun RemoteBrowseMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: FileViewOptions,
    bookmarks: List<com.yang136.sshhelper.data.SftpBookmarkEntity>,
    fileSystemLabel: String?,
    onCreateDirectory: () -> Unit,
    onCreateSymlink: () -> Unit,
    onAddBookmark: () -> Unit,
    onOpenBookmark: (String) -> Unit,
    onRemoveBookmark: (com.yang136.sshhelper.data.SftpBookmarkEntity) -> Unit,
    onHidden: (Boolean) -> Unit,
    onSort: (FileSort) -> Unit,
    onDirection: () -> Unit,
) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text("新建目录") }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }, onClick = onCreateDirectory)
        DropdownMenuItem({ Text("创建软链接") }, leadingIcon = { Icon(Icons.Default.Link, null) }, onClick = onCreateSymlink)
        DropdownMenuItem({ Text("收藏当前路径") }, leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = onAddBookmark)
        bookmarks.forEach { bookmark ->
            DropdownMenuItem(
                text = { Text("打开：${bookmark.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = { onOpenBookmark(bookmark.path) },
                trailingIcon = { IconButton(onClick = { onRemoveBookmark(bookmark) }) { Icon(Icons.Default.Close, "删除收藏") } },
            )
        }
        HorizontalDivider()
        ViewOptionItems(options, onHidden, onSort, onDirection)
        fileSystemLabel?.let { DropdownMenuItem({ Text(it, maxLines = 2, style = MaterialTheme.typography.labelMedium) }, enabled = false, onClick = {}) }
    }
}

@Composable
private fun LocalBrowseMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: FileViewOptions,
    directoryEnabled: Boolean,
    onCreateDirectory: () -> Unit,
    onHidden: (Boolean) -> Unit,
    onSort: (FileSort) -> Unit,
    onDirection: () -> Unit,
) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text("新建目录") }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }, enabled = directoryEnabled, onClick = onCreateDirectory)
        HorizontalDivider()
        ViewOptionItems(options, onHidden, onSort, onDirection)
    }
}

@Composable
private fun ViewOptionItems(options: FileViewOptions, onHidden: (Boolean) -> Unit, onSort: (FileSort) -> Unit, onDirection: () -> Unit) {
    DropdownMenuItem(
        text = { Text("显示隐藏文件") },
        trailingIcon = { if (options.showHidden) Icon(Icons.Default.Check, null) },
        onClick = { onHidden(!options.showHidden) },
    )
    FileSort.entries.forEach { item ->
        DropdownMenuItem(
            text = { Text("排序：${item.label()}") },
            leadingIcon = { Icon(Icons.Default.Sort, null) },
            trailingIcon = { if (options.sort == item) Icon(Icons.Default.Check, null) },
            onClick = { onSort(item) },
        )
    }
    DropdownMenuItem(
        text = { Text(if (options.descending) "降序排列" else "升序排列") },
        leadingIcon = { Icon(if (options.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null) },
        onClick = onDirection,
    )
}

@Composable
private fun PathDialog(title: String, initialValue: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text("路径") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("转到") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RemoteFileRow(file: RemoteFile, selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, { onSelect() })
        Icon(if (file.type == RemoteFileType.DIRECTORY) Icons.Default.Folder else if (file.type == RemoteFileType.FILE) Icons.Default.Description else Icons.Default.UploadFile, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${formatSize(file.size)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(file.modifiedAt)} · ${file.permissions.toString(8).padStart(4, '0')}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun LocalFileRow(file: LocalFile, selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, { onSelect() })
        Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (file.isDirectory) "目录" else formatSize(file.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(jobs: List<TransferJob>, vm: SftpViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("传输任务", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            items(jobs, key = TransferJob::id) { job ->
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row { Icon(if (job.direction == TransferDirection.UPLOAD) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null); Text(job.source.substringAfterLast('/'), Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1); Text(job.status.label(), style = MaterialTheme.typography.labelMedium) }
                    LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${formatSize(job.transferredBytes)} / ${formatSize(job.totalBytes)}${job.error?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                    if (job.status == TransferStatus.RUNNING) Text(
                        "${formatSize(job.speedBytesPerSecond)}/s${job.etaSeconds?.let { " · 预计 ${formatDuration(it)}" }.orEmpty()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (job.conflictPolicy == ConflictPolicy.ASK && job.error?.contains("已存在") == true) {
                        Text("选择冲突处理方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { vm.setTransferConflictPolicy(job.id, ConflictPolicy.OVERWRITE) }) { Text("覆盖") }
                            TextButton(onClick = { vm.setTransferConflictPolicy(job.id, ConflictPolicy.SKIP) }) { Text("跳过") }
                            TextButton(onClick = { vm.setTransferConflictPolicy(job.id, ConflictPolicy.RENAME) }) { Text("自动改名") }
                            TextButton(onClick = { vm.setTransferConflictPolicy(job.id, ConflictPolicy.RESUME) }) { Text("断点继续") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        when (job.status) {
                            TransferStatus.RUNNING, TransferStatus.QUEUED -> TextButton(onClick = { vm.pauseTransfer(job.id) }) { Text("暂停") }
                            TransferStatus.PAUSED, TransferStatus.FAILED, TransferStatus.WAITING_NETWORK, TransferStatus.WAITING_UNLOCK -> TextButton(onClick = { vm.resumeTransfer(job.id) }) { Text("继续") }
                            else -> Unit
                        }
                        if (job.status.isActive() || job.status == TransferStatus.PAUSED) TextButton(onClick = { vm.cancelTransfer(job.id) }) { Text("取消") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPreviewDialog(manager: PreviewPlaybackManager, onDismiss: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    val player = manager.playerOrNull
    val error = state.error
    val file = state.file
    var positionMs by remember(player) { mutableStateOf(0L) }
    LaunchedEffect(player, state.isPrepared, state.isPlaying) {
        while (isActive && player != null && state.isPrepared) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            delay(200)
        }
    }
    val duration = state.durationMs.coerceAtLeast(0L)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file?.name ?: "音频预览", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            when {
                error != null -> Column {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Text("播放中断，请检查 SSH 连接。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    if (file != null) TextButton(onClick = { manager.start(file, state.hostName) }) { Text("重试") }
                }
                !state.isPrepared -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("正在从服务器读取…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                else -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { manager.togglePlayPause() }, modifier = Modifier.size(56.dp)) {
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (state.isPlaying) "暂停" else "播放",
                                Modifier.size(36.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Slider(
                                value = positionMs.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                                onValueChange = { positionMs = it.toLong() },
                                onValueChangeFinished = { manager.seekTo(positionMs) },
                                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text(formatPlaybackTime(positionMs), style = MaterialTheme.typography.labelSmall)
                                Box(Modifier.weight(1f))
                                Text(formatPlaybackTime(duration), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (state.isBuffering) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ImagePreviewDialog(file: RemoteFile, hostName: String, imageLoader: ImageLoader, onDismiss: () -> Unit) {
    var attempt by remember(file.path) { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            key(attempt) {
                SubcomposeAsyncImage(
                    model = SftpImage(file.path, hostName, file.size),
                    imageLoader = imageLoader,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    loading = {
                        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text("正在从服务器读取…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    },
                    error = {
                        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("图片加载失败", color = MaterialTheme.colorScheme.error)
                            Text("请检查 SSH 连接后重试。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            TextButton(onClick = { attempt++ }) { Text("重试") }
                        }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun PreviewDialog(preview: RemotePreview, text: String?, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preview.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            if (text != null) OutlinedTextField(text, onTextChange, Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 520.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            else {
                val bitmap = remember(preview.bytes) { BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size) }
                if (bitmap != null) Image(bitmap.asImageBitmap(), preview.file.name, Modifier.fillMaxWidth().heightIn(max = 520.dp)) else Text("该文件不能在应用内预览，请下载后使用其他应用打开。")
            }
        },
        confirmButton = { if (text != null) TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun RemotePropertiesDialog(file: RemoteFile, onDismiss: () -> Unit, onApply: (Int, Int, Int) -> Unit) {
    var mode by remember(file.path) { mutableStateOf(file.permissions.toString(8).padStart(4, '0')) }
    var uid by remember(file.path) { mutableStateOf(file.uid.toString()) }
    var gid by remember(file.path) { mutableStateOf(file.gid.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("文件属性") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(file.path); Text("大小：${formatSize(file.size)}"); file.linkTarget?.let { Text("链接到：$it") }; OutlinedTextField(mode, { mode = it.filter { c -> c in '0'..'7' }.take(4) }, label = { Text("权限（八进制）") }); OutlinedTextField(uid, { uid = it.filter(Char::isDigit) }, label = { Text("UID") }); OutlinedTextField(gid, { gid = it.filter(Char::isDigit) }, label = { Text("GID") }) } }, confirmButton = { TextButton(onClick = { onApply(mode.toIntOrNull(8) ?: file.permissions, uid.toIntOrNull() ?: file.uid, gid.toIntOrNull() ?: file.gid) }) { Text("应用") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) { var value by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) }

@Composable
private fun ConfirmDeleteDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text("永久删除？") }, text = { Text("将永久删除 $count 个项目，此操作无法撤销。") }, confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })

@Composable
private fun SymlinkDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var target by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建软链接") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(target, { target = it }, label = { Text("链接目标") }); OutlinedTextField(name, { name = it }, label = { Text("链接名称") }) } },
        confirmButton = { TextButton(onClick = { onConfirm(target, name) }, enabled = target.isNotBlank() && name.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun ConnectionState?.sftpLabel(): String = when (this) { is ConnectionState.Connected -> "已连接 · $label"; ConnectionState.Connecting -> "连接中"; is ConnectionState.Error -> "连接失败 · $message"; is ConnectionState.Disconnected -> "已断开 · $reason"; else -> "准备连接" }
private fun FileSort.label() = when (this) { FileSort.NAME -> "名称"; FileSort.SIZE -> "大小"; FileSort.TIME -> "时间"; FileSort.TYPE -> "类型" }
private fun TransferStatus.label() = when (this) { TransferStatus.QUEUED -> "排队"; TransferStatus.RUNNING -> "传输中"; TransferStatus.PAUSED -> "已暂停"; TransferStatus.WAITING_NETWORK -> "等待网络"; TransferStatus.WAITING_UNLOCK -> "等待解锁"; TransferStatus.COMPLETED -> "完成"; TransferStatus.FAILED -> "失败"; TransferStatus.CANCELLED -> "已取消" }
private fun TransferStatus.isActive() = this == TransferStatus.QUEUED || this == TransferStatus.RUNNING || this == TransferStatus.WAITING_NETWORK || this == TransferStatus.WAITING_UNLOCK
private fun formatSize(bytes: Long): String { if (bytes < 0) return "未知"; val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB"); var value = bytes.toDouble(); var unit = 0; while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }; return if (unit == 0) "$bytes B" else "%.1f %s".format(value, units[unit]) }
private fun formatDuration(seconds: Long): String = if (seconds < 60) "${seconds}秒" else if (seconds < 3600) "${seconds / 60}分${seconds % 60}秒" else "${seconds / 3600}时${seconds % 3600 / 60}分"
private fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
