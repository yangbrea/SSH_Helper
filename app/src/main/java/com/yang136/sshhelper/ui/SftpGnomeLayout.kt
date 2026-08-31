package com.yang136.sshhelper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yang136.sshhelper.data.LocalRootEntity
import com.yang136.sshhelper.data.SftpBookmarkEntity
import com.yang136.sshhelper.data.TransferStatus
import com.yang136.sshhelper.sftp.LocalFile
import com.yang136.sshhelper.sftp.RemoteFile
import com.yang136.sshhelper.sftp.RemoteFileType
import com.yang136.sshhelper.sftp.SftpSearchHit
import com.yang136.sshhelper.sftp.TransferJob

/** GNOME Files 风格横屏文件管理器布局。仅横屏使用;竖屏走 SftpScreen 紧凑布局。 */
@Composable
internal fun GnomeSftpLayout(
    vm: SftpViewModel,
    state: SftpUiState,
    search: SftpSearchUiState,
    bookmarks: List<SftpBookmarkEntity>,
    roots: List<LocalRootEntity>,
    transfers: List<TransferJob>,
    onBack: () -> Unit,
    onCreateRemoteDirectory: () -> Unit,
    onDeleteRemote: () -> Unit,
    onDeleteLocal: () -> Unit,
    onPropertiesRemote: (RemoteFile) -> Unit,
    onPreview: (RemoteFile) -> Unit,
    onShowTransfers: () -> Unit,
    onAddLocalRoot: () -> Unit,
    onRemoveLocalRoot: (LocalRootEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activePane by rememberSaveable { mutableStateOf(GnomePane.REMOTE) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var pathDialog by remember { mutableStateOf(false) }
    var renameRemoteFile by remember { mutableStateOf<RemoteFile?>(null) }
    var moveDialog by remember { mutableStateOf(false) }
    var copyDialog by remember { mutableStateOf(false) }
    var symlinkDialog by remember { mutableStateOf(false) }
    var createLocalDirectory by remember { mutableStateOf(false) }
    var renameLocalFile by remember { mutableStateOf<LocalFile?>(null) }
    var viewMenu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.uploadFiles(uris)
    }

    val searching = searchText.isNotBlank()
    val remoteSelectedCount = state.selectedRemote.size
    val localSelectedCount = state.selectedLocal.size
    val activeViewOptions = if (activePane == GnomePane.REMOTE) state.remoteView else state.localView

    fun switchPane(pane: GnomePane) {
        if (pane != activePane) {
            activePane = pane
            searchText = ""
            vm.clearSearch()
            vm.setLocalQuery("")
        }
    }

    fun updateSearch(text: String) {
        searchText = text
        if (activePane == GnomePane.REMOTE) vm.setSearchQuery(text) else vm.setLocalQuery(text)
    }

    fun clearSearchAll() {
        searchText = ""
        vm.clearSearch()
        vm.setLocalQuery("")
    }

    fun openRemoteFile(file: RemoteFile) {
        if (file.type == RemoteFileType.DIRECTORY) {
            clearSearchAll()
            vm.setPath(file.path)
        } else {
            onPreview(file)
        }
    }

    Row(modifier.fillMaxSize()) {
        // ---------------- 左侧:操作栏(上)+位置栏(下) ----------------
        Column(Modifier.width(GNOME_SIDEBAR_WIDTH).fillMaxHeight()) {
            GnomeActionSidebar(
                activePane = activePane,
                searching = searching,
                remoteSelectedCount = remoteSelectedCount,
                localSelectedCount = localSelectedCount,
                remoteLoading = state.remoteLoading,
                onCreateDirectory = { if (activePane == GnomePane.REMOTE) onCreateRemoteDirectory() else createLocalDirectory = true },
                onUpload = { uploadPicker.launch(arrayOf("*/*")) },
                onDownload = vm::downloadSelected,
                onDelete = { if (activePane == GnomePane.REMOTE) onDeleteRemote() else onDeleteLocal() },
                onRename = {
                    if (activePane == GnomePane.REMOTE) {
                        renameRemoteFile = state.remoteFiles.firstOrNull { it.path in state.selectedRemote }
                    } else {
                        renameLocalFile = state.localFiles.firstOrNull { it.uri.toString() in state.selectedLocal }
                    }
                },
                onMove = { moveDialog = true },
                onCopy = { copyDialog = true },
                onProperties = { state.remoteFiles.firstOrNull { it.path in state.selectedRemote }?.let(onPropertiesRemote) },
                onAddBookmark = vm::addCurrentBookmark,
                onViewMenu = { viewMenu = true },
                viewMenuExpanded = viewMenu,
                onViewMenuChange = { viewMenu = it },
                viewMenu = { ViewOptionItems(
                    options = activeViewOptions,
                    onHidden = if (activePane == GnomePane.REMOTE) vm::setRemoteShowHidden else vm::setLocalShowHidden,
                    onSort = if (activePane == GnomePane.REMOTE) vm::setRemoteSort else vm::setLocalSort,
                    onDirection = if (activePane == GnomePane.REMOTE) vm::toggleRemoteDirection else vm::toggleLocalDirection,
                ) },
            )
            HorizontalDivider()
            GnomePlacesSidebar(
                bookmarks = bookmarks,
                roots = roots,
                transfers = transfers,
                activePane = activePane,
                onOpenBookmark = { path -> switchPane(GnomePane.REMOTE); vm.setPath(path) },
                onRemoveBookmark = vm::removeBookmark,
                onChooseRoot = { root -> switchPane(GnomePane.LOCAL); vm.chooseLocalRoot(root) },
                onAddRoot = onAddLocalRoot,
                onRemoveRoot = onRemoveLocalRoot,
                onShowTransfers = onShowTransfers,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        VerticalDivider()
        // ---------------- 右侧:顶部一行 + 主区域 ----------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            GnomeTopBar(
                activePane = activePane,
                remotePath = state.remotePath,
                localLabel = state.localRoot?.displayName ?: "选择本地目录",
                localBackEnabled = state.localBackStack.isNotEmpty(),
                searchQuery = searchText,
                onSearchChange = ::updateSearch,
                onClearSearch = ::clearSearchAll,
                onBack = onBack,
                onUp = { if (activePane == GnomePane.REMOTE) vm.upRemote() else vm.upLocal() },
                onBreadcrumb = { path -> clearSearchAll(); vm.setPath(path) },
                onEllipsis = { pathDialog = true },
                onRefresh = { if (activePane == GnomePane.REMOTE) vm.refreshRemote() else vm.refreshLocal() },
                transfersCount = transfers.count { it.status.isActive() },
                onShowTransfers = onShowTransfers,
            )
            HorizontalDivider()
            // 主区域
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // 选择模式优先于搜索/浏览
                    remoteSelectedCount > 0 -> GnomeSelectionArea(
                        count = remoteSelectedCount,
                        primaryLabel = "下载",
                        primaryIcon = Icons.Default.Download,
                        onClear = vm::clearSelection,
                        onPrimary = vm::downloadSelected,
                        selectionMenu = selectionMenu,
                        onSelectionMenuChange = { selectionMenu = it },
                        menu = {
                            val selectedFile = state.remoteFiles.firstOrNull { it.path in state.selectedRemote }
                            if (remoteSelectedCount == 1) {
                                DropdownMenuItem({ Text("属性") }, onClick = { selectionMenu = false; selectedFile?.let(onPropertiesRemote) })
                                DropdownMenuItem({ Text("重命名") }, onClick = { selectionMenu = false; renameRemoteFile = selectedFile })
                            }
                            DropdownMenuItem({ Text("移动") }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }, onClick = { selectionMenu = false; moveDialog = true })
                            DropdownMenuItem({ Text("复制") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { selectionMenu = false; copyDialog = true })
                            DropdownMenuItem({ Text("删除") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { selectionMenu = false; onDeleteRemote() })
                        },
                    )
                    localSelectedCount > 0 -> GnomeSelectionArea(
                        count = localSelectedCount,
                        primaryLabel = "上传",
                        primaryIcon = Icons.Default.ArrowUpward,
                        onClear = vm::clearSelection,
                        onPrimary = vm::uploadSelected,
                        selectionMenu = selectionMenu,
                        onSelectionMenuChange = { selectionMenu = it },
                        menu = {
                            val selectedFile = state.localFiles.firstOrNull { it.uri.toString() in state.selectedLocal }
                            if (localSelectedCount == 1) DropdownMenuItem({ Text("重命名") }, onClick = { selectionMenu = false; renameLocalFile = selectedFile })
                            DropdownMenuItem({ Text("删除") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { selectionMenu = false; onDeleteLocal() })
                        },
                    )
                    activePane == GnomePane.REMOTE && searching -> GnomeRemoteSearchResults(
                        search = search,
                        onOpenDir = { path -> clearSearchAll(); vm.setPath(path) },
                        onOpenFile = { hit -> onPreview(hit.toRemoteFile()) },
                        onRetry = { vm.setSearchQuery(searchText) },
                    )
                    activePane == GnomePane.REMOTE -> GnomeRemoteBrowse(
                        state = state,
                        visibleFiles = vm.visibleRemoteFiles(),
                        onSelect = vm::toggleRemote,
                        onOpen = ::openRemoteFile,
                        onRetry = vm::refreshRemote,
                    )
                    else -> GnomeLocalBrowse(
                        state = state,
                        visibleFiles = vm.visibleLocalFiles(),
                        searching = searching,
                        onSelect = vm::toggleLocal,
                        onOpen = { file -> if (file.isDirectory) vm.openLocal(file) },
                        onAddRoot = onAddLocalRoot,
                    )
                }
            }
        }
    }

    // 对话框
    if (pathDialog) PathDialog("转到远程路径", state.remotePath, { pathDialog = false }) { path -> pathDialog = false; clearSearchAll(); vm.setPath(path) }
    renameRemoteFile?.let { file -> NameDialog("重命名", "新名称", { renameRemoteFile = null }) { vm.renameRemote(file, it); renameRemoteFile = null } }
    renameLocalFile?.let { file -> NameDialog("重命名", "新名称", { renameLocalFile = null }) { vm.renameLocal(file, it); renameLocalFile = null } }
    if (moveDialog) NameDialog("移动到目录", "远程目标目录", { moveDialog = false }) { vm.moveSelectedRemote(it); moveDialog = false }
    if (copyDialog) NameDialog("复制到目录", "远程目标目录", { copyDialog = false }) { vm.copySelectedRemote(it); copyDialog = false }
    if (symlinkDialog) SymlinkDialog({ symlinkDialog = false }) { target, name -> vm.createSymlink(target, name); symlinkDialog = false }
    if (createLocalDirectory) NameDialog("新建本地目录", "目录名称", { createLocalDirectory = false }) { vm.createLocalDirectory(it); createLocalDirectory = false }
}

private enum class GnomePane { REMOTE, LOCAL }

private val GNOME_SIDEBAR_WIDTH = 200.dp

/** 面包屑分段:label + 跳转路径;过长时折叠前段为「…」。 */
data class BreadcrumbSegment(val label: String, val path: String, val ellipsis: Boolean = false)

internal fun breadcrumbSegments(path: String, maxVisible: Int = 3): List<BreadcrumbSegment> {
    val parts = path.split('/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return if (path.startsWith('/')) listOf(BreadcrumbSegment("/", "/")) else emptyList()
    val root = if (path.startsWith('/')) "/" else ""
    val segments = parts.mapIndexed { index, part ->
        val segmentPath = if (index == 0) "$root$part" else parts.take(index + 1).joinToString("/", root)
        BreadcrumbSegment(part, segmentPath)
    }
    if (segments.size <= maxVisible) return segments
    val visible = segments.takeLast(maxVisible)
    val collapseRoot = segments[segments.size - maxVisible - 1]
    return listOf(BreadcrumbSegment("…", collapseRoot.path, ellipsis = true)) + visible
}

/** 顶部一行:返回/上级/面包屑 + 搜索框 + 刷新/传输。 */
@Composable
private fun GnomeTopBar(
    activePane: GnomePane,
    remotePath: String,
    localLabel: String,
    localBackEnabled: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onBreadcrumb: (String) -> Unit,
    onEllipsis: () -> Unit,
    onRefresh: () -> Unit,
    transfersCount: Int,
    onShowTransfers: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        IconButton(onClick = onUp, enabled = activePane == GnomePane.LOCAL && localBackEnabled || activePane == GnomePane.REMOTE) {
            Icon(Icons.Default.ArrowUpward, "上级")
        }
        if (activePane == GnomePane.REMOTE) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                breadcrumbSegments(remotePath).forEachIndexed { index, segment ->
                    Text(
                        segment.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (index == breadcrumbSegments(remotePath).lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { if (segment.ellipsis) onEllipsis() else onBreadcrumb(segment.path) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    if (index < breadcrumbSegments(remotePath).lastIndex) {
                        Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Home, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(localLabel, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.width(200.dp),
            singleLine = true,
            placeholder = { Text(if (activePane == GnomePane.LOCAL) "搜索本地（仅当前目录）" else "搜索远程") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                { IconButton(onClick = onClearSearch) { Icon(Icons.Default.Close, "清空") } }
            } else null,
        )
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "刷新") }
        BadgedBox(
            badge = { if (transfersCount > 0) Badge { Text(transfersCount.toString()) } },
        ) {
            IconButton(onClick = onShowTransfers) { Icon(Icons.Default.Download, "传输任务") }
        }
    }
}

/** 左侧操作按钮栏:浏览模式(新建/上传/收藏/视图)与选择模式(下载/重命名/移动/复制/属性/删除)。 */
@Composable
private fun GnomeActionSidebar(
    activePane: GnomePane,
    searching: Boolean,
    remoteSelectedCount: Int,
    localSelectedCount: Int,
    remoteLoading: Boolean,
    onCreateDirectory: () -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onProperties: () -> Unit,
    onAddBookmark: () -> Unit,
    onViewMenu: () -> Unit,
    viewMenuExpanded: Boolean,
    onViewMenuChange: (Boolean) -> Unit,
    viewMenu: @Composable () -> Unit,
) {
    val browseEnabled = !searching && remoteSelectedCount == 0 && localSelectedCount == 0
    val selection = remoteSelectedCount > 0 || localSelectedCount > 0
    val remoteSelection = remoteSelectedCount > 0
    val localSelection = localSelectedCount > 0
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (selection) {
            if (remoteSelection) SidebarAction(Icons.Default.Download, "下载", true) { onDownload() }
            if (remoteSelection) SidebarAction(Icons.Default.DriveFileMove, "移动", remoteSelectedCount > 0) { onMove() }
            if (remoteSelection) SidebarAction(Icons.Default.ContentCopy, "复制", remoteSelectedCount > 0) { onCopy() }
            if (remoteSelection) SidebarAction(Icons.Default.Description, "属性", remoteSelectedCount == 1) { onProperties() }
            if (localSelection || remoteSelection) SidebarAction(Icons.Default.Delete, "删除", true) { onDelete() }
            SidebarAction(Icons.Default.CreateNewFolder, "重命名", (remoteSelectedCount == 1 || localSelectedCount == 1)) { onRename() }
        } else {
            SidebarAction(Icons.Default.CreateNewFolder, "新建目录", browseEnabled && !remoteLoading) { onCreateDirectory() }
            if (activePane == GnomePane.REMOTE) SidebarAction(Icons.Default.UploadFile, "上传", browseEnabled && !remoteLoading) { onUpload() }
            if (activePane == GnomePane.REMOTE) SidebarAction(Icons.Default.Star, "收藏", browseEnabled) { onAddBookmark() }
            Box {
                SidebarAction(Icons.Default.MoreVert, "视图", browseEnabled) { onViewMenu() }
                DropdownMenu(expanded = viewMenuExpanded, onDismissRequest = { onViewMenuChange(false) }) { viewMenu() }
            }
        }
    }
}

@Composable
private fun SidebarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
    }
}

/** 左侧位置栏:收藏 / 本地目录 / 传输。 */
@Composable
private fun GnomePlacesSidebar(
    bookmarks: List<SftpBookmarkEntity>,
    roots: List<LocalRootEntity>,
    transfers: List<TransferJob>,
    activePane: GnomePane,
    onOpenBookmark: (String) -> Unit,
    onRemoveBookmark: (SftpBookmarkEntity) -> Unit,
    onChooseRoot: (LocalRootEntity) -> Unit,
    onAddRoot: () -> Unit,
    onRemoveRoot: (LocalRootEntity) -> Unit,
    onShowTransfers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(vertical = 8.dp)) {
        PlacesHeader("收藏")
        if (bookmarks.isEmpty()) {
            Text("暂无收藏", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            bookmarks.forEach { bookmark ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenBookmark(bookmark.path) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(bookmark.label, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { onRemoveBookmark(bookmark) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "删除收藏", Modifier.size(16.dp)) }
                }
            }
        }
        PlacesHeader("本地")
        roots.forEach { root ->
            Row(
                Modifier.fillMaxWidth().clickable { onChooseRoot(root) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(root.displayName, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { onRemoveRoot(root) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "移除授权", Modifier.size(16.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onAddRoot).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text("授权新目录", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider()
        val activeCount = transfers.count { it.status.isActive() }
        Row(Modifier.fillMaxWidth().clickable(onClick = onShowTransfers).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BadgedBox(badge = { if (activeCount > 0) Badge { Text(activeCount.toString()) } }) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text("传输任务", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PlacesHeader(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

/** 选择模式操作条(复用紧凑布局的 CompactSelectionBar)。 */
@Composable
private fun GnomeSelectionArea(
    count: Int,
    primaryLabel: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClear: () -> Unit,
    onPrimary: () -> Unit,
    selectionMenu: Boolean,
    onSelectionMenuChange: (Boolean) -> Unit,
    menu: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        CompactSelectionBar(
            count = count,
            primaryLabel = primaryLabel,
            primaryIcon = primaryIcon,
            onClear = onClear,
            onPrimary = onPrimary,
            onMore = { onSelectionMenuChange(true) },
            menu = { DropdownMenu(selectionMenu, { onSelectionMenuChange(false) }) { menu() } },
        )
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("已选 $count 项", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 远程浏览主区域。 */
@Composable
private fun GnomeRemoteBrowse(
    state: SftpUiState,
    visibleFiles: List<RemoteFile>,
    onSelect: (String) -> Unit,
    onOpen: (RemoteFile) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.remoteLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error != null && state.remoteFiles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("无法读取远程目录", style = MaterialTheme.typography.titleMedium)
                Text(state.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
        state.remoteFiles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("远程目录为空") }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(visibleFiles, key = RemoteFile::path) { file ->
                RemoteFileRow(file, file.path in state.selectedRemote, onSelect = { onSelect(file.path) }, onOpen = { onOpen(file) })
            }
        }
    }
}

/** 递归搜索结果主区域。 */
@Composable
private fun GnomeRemoteSearchResults(
    search: SftpSearchUiState,
    onOpenDir: (String) -> Unit,
    onOpenFile: (SftpSearchHit) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (search.loading && search.results.isEmpty()) "正在搜索…" else "搜索结果 · ${search.results.size}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (search.loading && search.results.isNotEmpty()) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        when {
            search.loading && search.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            search.error != null && search.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("搜索失败", style = MaterialTheme.typography.titleMedium)
                    Text(search.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
            search.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有匹配的文件") }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(search.results, key = SftpSearchHit::path) { hit ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (hit.type == RemoteFileType.DIRECTORY) onOpenDir(hit.path) else onOpenFile(hit)
                        }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(hit.typeIcon(), null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(hit.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hit.parentDir, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (hit.type == RemoteFileType.FILE) formatSize(hit.size) else "目录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 本地浏览主区域。 */
@Composable
private fun GnomeLocalBrowse(
    state: SftpUiState,
    visibleFiles: List<LocalFile>,
    searching: Boolean,
    onSelect: (android.net.Uri) -> Unit,
    onOpen: (LocalFile) -> Unit,
    onAddRoot: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (searching) {
            Text("本地搜索仅匹配当前目录", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            state.localRoot == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("尚未授权本地目录")
                    Button(onClick = onAddRoot) { Text("选择目录") }
                }
            }
            state.localLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.localFiles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("本地目录为空") }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(visibleFiles, key = { it.uri.toString() }) { file ->
                    LocalFileRow(file, file.uri.toString() in state.selectedLocal, onSelect = { onSelect(file.uri) }, onOpen = { onOpen(file) })
                }
            }
        }
    }
}

private fun SftpSearchHit.typeIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    RemoteFileType.DIRECTORY -> Icons.Default.Folder
    RemoteFileType.SYMLINK -> Icons.Default.Link
    RemoteFileType.OTHER -> Icons.Default.InsertDriveFile
    RemoteFileType.FILE -> Icons.Default.Description
}
