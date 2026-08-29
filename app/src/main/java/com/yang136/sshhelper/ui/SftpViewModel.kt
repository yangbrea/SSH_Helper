package com.yang136.sshhelper.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yang136.sshhelper.AppContainer
import com.yang136.sshhelper.data.ConflictPolicy
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.LocalRootEntity
import com.yang136.sshhelper.data.TransferDirection
import com.yang136.sshhelper.data.TransferStatus
import com.yang136.sshhelper.sftp.LocalFile
import com.yang136.sshhelper.sftp.RemoteFile
import com.yang136.sshhelper.sftp.RemoteFileSystem
import com.yang136.sshhelper.sftp.RemoteFileType
import com.yang136.sshhelper.sftp.SftpClient
import com.yang136.sshhelper.sftp.TransferJob
import com.yang136.sshhelper.sftp.TransferRequest
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.preview.PreviewPlaybackManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

enum class FileSort { NAME, SIZE, TIME, TYPE }

data class FileViewOptions(
    val query: String = "",
    val showHidden: Boolean = false,
    val sort: FileSort = FileSort.NAME,
    val descending: Boolean = false,
)

data class SftpUiState(
    val remotePath: String = ".",
    val remoteFiles: List<RemoteFile> = emptyList(),
    val localRoot: LocalRootEntity? = null,
    val localUri: Uri? = null,
    val localBackStack: List<Uri> = emptyList(),
    val localFiles: List<LocalFile> = emptyList(),
    val remoteLoading: Boolean = false,
    val localLoading: Boolean = false,
    val remoteView: FileViewOptions = FileViewOptions(),
    val localView: FileViewOptions = FileViewOptions(),
    val selectedRemote: Set<String> = emptySet(),
    val selectedLocal: Set<String> = emptySet(),
    val error: String? = null,
    val fileSystem: RemoteFileSystem? = null,
)

data class RemotePreview(
    val file: RemoteFile,
    val bytes: ByteArray,
    val editableText: String?,
)

class SftpViewModel(
    private val container: AppContainer,
    val sessionId: SessionId,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SftpUiState())
    val state: StateFlow<SftpUiState> = mutableState.asStateFlow()
    val localRoots = container.sftpRepository.localRoots.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val transfers: StateFlow<List<TransferJob>> = container.transferManager.jobs
    val session: StateFlow<ManagedSessionState?> = container.sessionManager.sessions
        .map { list -> list.firstOrNull { it.id == sessionId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarks = session.filterNotNull().flatMapLatest { container.sftpRepository.bookmarks(it.profile.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var client: SftpClient? = null

    /** Streaming audio/video preview over the shared SSH session (dedicated SFTP channel). */
    val playback = PreviewPlaybackManager(container.application, container.sessionManager, sessionId, container.previewCache.cache)

    /** Coil loader whose fetcher streams remote images over the shared SSH session. */
    val imageLoader: coil3.ImageLoader by lazy {
        coil3.ImageLoader.Builder(container.application)
            .components {
                add(com.yang136.sshhelper.preview.SftpImageFetcher.Factory(container.sessionManager, sessionId))
            }
            .build()
    }

    init {
        viewModelScope.launch {
            var connected = false
            session.collect { managed ->
                val nowConnected = managed?.connection is com.yang136.sshhelper.ssh.ConnectionState.Connected
                if (nowConnected && !connected) {
                    client = null
                    refreshRemote()
                } else if (!nowConnected) client = null
                connected = nowConnected
            }
        }
    }

    fun connect(credential: Credential, remember: Boolean) = viewModelScope.launch {
        container.sessionManager.connect(sessionId, credential, remember)
    }

    fun respondToHostKey(accept: Boolean) = container.sessionManager.respondToHostKey(sessionId, accept)
    fun forgetChangedHostKey() = viewModelScope.launch { container.sessionManager.forgetChangedHostKey(sessionId) }

    fun setPath(path: String) {
        mutableState.value = mutableState.value.copy(remotePath = path, selectedRemote = emptySet())
        refreshRemote()
    }

    fun upRemote() {
        val current = mutableState.value.remotePath.trimEnd('/')
        setPath(current.substringBeforeLast('/', if (current.startsWith('/')) "/" else "."))
    }

    fun refreshRemote() = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(remoteLoading = true, error = null)
        runCatching {
            val sftp = ensureClient()
            val resolvedPath = if (mutableState.value.remotePath == ".") sftp.home() else mutableState.value.remotePath
            val files = sftp.list(resolvedPath)
            val fs = runCatching { sftp.fileSystem(resolvedPath) }.getOrNull()
            mutableState.value = mutableState.value.copy(remotePath = resolvedPath, remoteFiles = files, fileSystem = fs, remoteLoading = false)
        }.onFailure { mutableState.value = mutableState.value.copy(remoteLoading = false, error = it.message ?: "无法读取远程目录") }
    }

    fun chooseLocalRoot(root: LocalRootEntity) {
        mutableState.value = mutableState.value.copy(localRoot = root, localUri = Uri.parse(root.uri), localBackStack = emptyList(), selectedLocal = emptySet())
        refreshLocal()
    }

    fun openLocal(directory: LocalFile) {
        if (!directory.isDirectory) return
        val current = mutableState.value.localUri ?: return
        mutableState.value = mutableState.value.copy(localUri = directory.uri, localBackStack = mutableState.value.localBackStack + current, selectedLocal = emptySet())
        refreshLocal()
    }

    fun upLocal() {
        val stack = mutableState.value.localBackStack
        if (stack.isEmpty()) return
        mutableState.value = mutableState.value.copy(localUri = stack.last(), localBackStack = stack.dropLast(1), selectedLocal = emptySet())
        refreshLocal()
    }

    fun refreshLocal() = viewModelScope.launch {
        val uri = mutableState.value.localUri ?: return@launch
        mutableState.value = mutableState.value.copy(localLoading = true, error = null)
        runCatching { container.sftpRepository.listLocal(uri) }
            .onSuccess { mutableState.value = mutableState.value.copy(localFiles = it, localLoading = false) }
            .onFailure { mutableState.value = mutableState.value.copy(localLoading = false, error = it.message ?: "无法读取本地目录") }
    }

    fun addLocalRoot(uri: Uri, name: String) = viewModelScope.launch {
        runCatching { container.sftpRepository.addLocalRoot(uri, name) }
            .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
    }

    fun removeLocalRoot(root: LocalRootEntity) = viewModelScope.launch { container.sftpRepository.removeLocalRoot(root) }

    fun setRemoteQuery(value: String) = updateRemoteView { copy(query = value) }
    fun setRemoteShowHidden(value: Boolean) = updateRemoteView { copy(showHidden = value) }
    fun setRemoteSort(sort: FileSort) = updateRemoteView { copy(sort = sort) }
    fun toggleRemoteDirection() = updateRemoteView { copy(descending = !descending) }
    fun setLocalQuery(value: String) = updateLocalView { copy(query = value) }
    fun setLocalShowHidden(value: Boolean) = updateLocalView { copy(showHidden = value) }
    fun setLocalSort(sort: FileSort) = updateLocalView { copy(sort = sort) }
    fun toggleLocalDirection() = updateLocalView { copy(descending = !descending) }
    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }

    private fun updateRemoteView(transform: FileViewOptions.() -> FileViewOptions) {
        mutableState.value = mutableState.value.copy(remoteView = mutableState.value.remoteView.transform())
    }

    private fun updateLocalView(transform: FileViewOptions.() -> FileViewOptions) {
        mutableState.value = mutableState.value.copy(localView = mutableState.value.localView.transform())
    }

    fun toggleRemote(path: String) {
        val selected = mutableState.value.selectedRemote
        mutableState.value = mutableState.value.copy(selectedRemote = if (path in selected) selected - path else selected + path)
    }

    fun toggleLocal(uri: Uri) {
        val key = uri.toString()
        val selected = mutableState.value.selectedLocal
        mutableState.value = mutableState.value.copy(selectedLocal = if (key in selected) selected - key else selected + key)
    }

    fun clearSelection() { mutableState.value = mutableState.value.copy(selectedRemote = emptySet(), selectedLocal = emptySet()) }

    fun createRemoteDirectory(name: String) = remoteOperation { mkdir(joinPath(mutableState.value.remotePath, name)) }
    fun renameRemote(file: RemoteFile, name: String) = remoteOperation { rename(file.path, joinPath(file.path.substringBeforeLast('/', "/"), name)) }
    fun chmod(file: RemoteFile, mode: Int) = remoteOperation { chmod(file.path, mode) }
    fun chown(file: RemoteFile, uid: Int, gid: Int) = remoteOperation { chown(file.path, uid); chgrp(file.path, gid) }
    fun createSymlink(target: String, name: String) = remoteOperation { symlink(target, joinPath(mutableState.value.remotePath, name)) }
    fun addCurrentBookmark() = viewModelScope.launch {
        val profile = session.value?.profile ?: return@launch
        container.sftpRepository.addBookmark(profile.id, mutableState.value.remotePath, mutableState.value.remotePath.substringAfterLast('/').ifEmpty { "/" })
    }
    fun removeBookmark(bookmark: com.yang136.sshhelper.data.SftpBookmarkEntity) = viewModelScope.launch {
        container.sftpRepository.removeBookmark(bookmark)
    }

    fun createLocalDirectory(name: String) = viewModelScope.launch {
        val parent = mutableState.value.localUri ?: return@launch
        runCatching { container.sftpRepository.createLocalDirectory(parent, name) }
            .onSuccess { refreshLocal() }
            .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
    }

    fun renameLocal(file: LocalFile, name: String) = viewModelScope.launch {
        runCatching { container.sftpRepository.renameLocal(file.uri, name) }
            .onSuccess { refreshLocal() }
            .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
    }

    fun moveSelectedRemote(targetDirectory: String) = viewModelScope.launch {
        runCatching {
            val sftp = ensureClient()
            mutableState.value.selectedRemote.forEach { source -> sftp.rename(source, joinPath(targetDirectory, source.substringAfterLast('/'))) }
        }.onSuccess { clearSelection(); refreshRemote() }
            .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
    }

    fun copySelectedRemote(targetDirectory: String, policy: ConflictPolicy = ConflictPolicy.ASK) = viewModelScope.launch {
        val currentSession = session.value ?: return@launch
        runCatching {
            val requests = mutableState.value.remoteFiles
                .filter { it.path in mutableState.value.selectedRemote && it.type == RemoteFileType.FILE }
                .map { file -> TransferRequest(currentSession.profile.id, sessionId, TransferDirection.REMOTE_COPY, file.path, joinPath(targetDirectory, file.name), file.size, policy) }
            require(requests.isNotEmpty()) { "同服务器复制当前只支持普通文件" }
            container.transferManager.enqueue(requests)
        }.onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
        clearSelection()
    }

    fun deleteSelectedRemote() = viewModelScope.launch {
        val paths = mutableState.value.selectedRemote.toList()
        runCatching { paths.forEach { ensureClient().delete(it, recursive = true) } }
            .onSuccess { mutableState.value = mutableState.value.copy(selectedRemote = emptySet()); refreshRemote() }
            .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
    }

    fun deleteSelectedLocal() = viewModelScope.launch {
        runCatching { mutableState.value.selectedLocal.forEach { container.sftpRepository.deleteLocal(Uri.parse(it)) } }
            .onSuccess { mutableState.value = mutableState.value.copy(selectedLocal = emptySet()); refreshLocal() }
            .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
    }

    fun uploadSelected(policy: ConflictPolicy = ConflictPolicy.ASK) = viewModelScope.launch {
        val session = session.value ?: return@launch
        runCatching {
            val selected = mutableState.value.localFiles.filter { it.uri.toString() in mutableState.value.selectedLocal }
            val requests = buildList {
                selected.forEach { file ->
                    val target = joinPath(mutableState.value.remotePath, file.name)
                    if (!file.isDirectory) {
                        add(TransferRequest(session.profile.id, sessionId, TransferDirection.UPLOAD, file.uri.toString(), target, file.size, policy))
                    } else {
                        ensureRemoteDirectory(target)
                        container.sftpRepository.walkLocal(file.uri).drop(1).forEach { entry ->
                            val destination = joinPath(target, entry.relativePath)
                            if (entry.file.isDirectory) ensureRemoteDirectory(destination)
                            else add(TransferRequest(session.profile.id, sessionId, TransferDirection.UPLOAD, entry.file.uri.toString(), destination, entry.file.size, policy))
                        }
                    }
                }
            }
            container.transferManager.enqueue(requests)
        }.onFailure {
            mutableState.value = mutableState.value.copy(error = it.message ?: "无法创建上传任务")
        }
        mutableState.value = mutableState.value.copy(selectedLocal = emptySet())
    }

    fun downloadSelected(policy: ConflictPolicy = ConflictPolicy.ASK) = viewModelScope.launch {
        val session = session.value ?: return@launch
        val destination = mutableState.value.localUri ?: run {
            mutableState.value = mutableState.value.copy(error = "请先授权并选择本地目录")
            return@launch
        }
        runCatching {
            val selected = mutableState.value.remoteFiles.filter { it.path in mutableState.value.selectedRemote }
            val requests = buildList {
                selected.forEach { file ->
                    when (file.type) {
                        RemoteFileType.FILE -> add(TransferRequest(session.profile.id, sessionId, TransferDirection.DOWNLOAD, file.path, destination.toString(), file.size, policy))
                        RemoteFileType.DIRECTORY -> collectRemoteDownloads(file, destination, session.profile.id, policy, this)
                        RemoteFileType.SYMLINK, RemoteFileType.OTHER -> Unit // Never follow links during recursive transfer.
                    }
                }
            }
            container.transferManager.enqueue(requests)
        }.onFailure {
            mutableState.value = mutableState.value.copy(error = it.message ?: "无法创建下载任务")
        }
        mutableState.value = mutableState.value.copy(selectedRemote = emptySet())
    }

    fun setTransferConflictPolicy(id: Long, policy: ConflictPolicy) = viewModelScope.launch {
        container.transferManager.setConflictPolicy(id, policy)
    }

    fun pauseTransfer(id: Long) = viewModelScope.launch { container.transferManager.pause(id) }
    fun resumeTransfer(id: Long) = viewModelScope.launch { container.transferManager.resume(id, sessionId) }
    fun cancelTransfer(id: Long) = viewModelScope.launch { container.transferManager.cancel(id) }

    suspend fun preview(file: RemoteFile): Result<RemotePreview> = runCatching {
        require(file.type == RemoteFileType.FILE) { "只能预览普通文件" }
        require(file.size <= MAX_PREVIEW_BYTES) { "文件超过 25 MiB，请下载后打开" }
        val output = ByteArrayOutputStream(file.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        ensureClient().download(file.path, output)
        val bytes = output.toByteArray()
        val text = if (file.size <= MAX_TEXT_BYTES && looksLikeText(bytes)) bytes.decodeToString() else null
        RemotePreview(file, bytes, text)
    }

    suspend fun saveText(preview: RemotePreview, text: String, overwriteConflict: Boolean): Result<RemotePreview> = runCatching {
        require(text.encodeToByteArray().size <= MAX_TEXT_BYTES) { "文本不能超过 2 MiB" }
        val sftp = ensureClient()
        val latest = sftp.stat(preview.file.path)
        if (!overwriteConflict && (latest.size != preview.file.size || latest.modifiedAt != preview.file.modifiedAt)) {
            error("REMOTE_CHANGED")
        }
        val bytes = text.encodeToByteArray()
        val parent = preview.file.path.substringBeforeLast('/', "/")
        val temporary = joinPath(parent, ".${preview.file.name}.sshhelper-edit.part")
        val backup = joinPath(parent, ".${preview.file.name}.sshhelper-edit.backup")
        sftp.upload(ByteArrayInputStream(bytes), temporary)
        sftp.chmod(temporary, preview.file.permissions)
        runCatching { sftp.delete(backup) }
        sftp.rename(preview.file.path, backup)
        runCatching { sftp.rename(temporary, preview.file.path) }.getOrElse { failure ->
            runCatching { sftp.rename(backup, preview.file.path) }
            throw failure
        }
        runCatching { sftp.delete(backup) }
        bytes.fill(0)
        val updated = sftp.stat(preview.file.path)
        RemotePreview(updated, text.encodeToByteArray(), text)
    }

    suspend fun saveTextCopy(preview: RemotePreview, text: String): Result<RemotePreview> = runCatching {
        val bytes = text.encodeToByteArray()
        require(bytes.size <= MAX_TEXT_BYTES) { "文本不能超过 2 MiB" }
        val parent = preview.file.path.substringBeforeLast('/', "/")
        val base = preview.file.name.substringBeforeLast('.', preview.file.name)
        val extension = preview.file.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val target = joinPath(parent, "$base (副本 ${System.currentTimeMillis()})$extension")
        ensureClient().upload(ByteArrayInputStream(bytes), target)
        ensureClient().chmod(target, preview.file.permissions)
        bytes.fill(0)
        val saved = ensureClient().stat(target)
        RemotePreview(saved, text.encodeToByteArray(), text)
    }

    private fun remoteOperation(block: suspend SftpClient.() -> Unit) = viewModelScope.launch {
        runCatching { ensureClient().block() }
            .onSuccess { refreshRemote() }
            .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
    }

    private suspend fun ensureClient(): SftpClient = client ?: container.sessionManager.sftp(sessionId).also { client = it }

    private suspend fun ensureRemoteDirectory(path: String) {
        val sftp = ensureClient()
        val existing = runCatching { sftp.stat(path, followLinks = false) }.getOrNull()
        if (existing == null) sftp.mkdir(path)
        else check(existing.type == RemoteFileType.DIRECTORY) { "远端已存在同名文件：$path" }
    }

    private suspend fun collectRemoteDownloads(
        directory: RemoteFile,
        localParent: Uri,
        hostId: Long,
        policy: ConflictPolicy,
        target: MutableList<TransferRequest>,
    ) {
        val localDirectory = container.sftpRepository.ensureLocalDirectory(localParent, directory.name)
        ensureClient().list(directory.path).forEach { child ->
            when (child.type) {
                RemoteFileType.FILE -> target += TransferRequest(
                    hostId, sessionId, TransferDirection.DOWNLOAD, child.path, localDirectory.toString(), child.size, policy,
                )
                RemoteFileType.DIRECTORY -> collectRemoteDownloads(child, localDirectory, hostId, policy, target)
                RemoteFileType.SYMLINK, RemoteFileType.OTHER -> Unit
            }
        }
    }

    fun visibleRemoteFiles(): List<RemoteFile> = mutableState.value.remoteFiles.filteredRemote(mutableState.value)
    fun visibleLocalFiles(): List<LocalFile> = mutableState.value.localFiles.filteredLocal(mutableState.value)

    override fun onCleared() {
        playback.stop()
        // The browser channel belongs to SessionManager and intentionally survives page changes.
    }

    companion object {
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        const val MAX_PREVIEW_BYTES = 25L * 1024 * 1024
        fun factory(container: AppContainer, sessionId: SessionId) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SftpViewModel(container, sessionId) as T
        }
    }
}

internal fun List<RemoteFile>.filteredRemote(state: SftpUiState): List<RemoteFile> {
    val options = state.remoteView
    val filtered = filter { (options.showHidden || !it.name.startsWith('.')) && (options.query.isBlank() || it.name.contains(options.query, true)) }
    val detailComparator = when (options.sort) {
        FileSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER, RemoteFile::name)
        FileSort.SIZE -> compareBy(RemoteFile::size)
        FileSort.TIME -> compareBy(RemoteFile::modifiedAt)
        FileSort.TYPE -> compareBy { it.type.name }
    }.let { if (options.descending) it.reversed() else it }
    return filtered.sortedWith(compareBy<RemoteFile> { it.type != RemoteFileType.DIRECTORY }.then(detailComparator))
}

internal fun List<LocalFile>.filteredLocal(state: SftpUiState): List<LocalFile> {
    val options = state.localView
    val filtered = filter { (options.showHidden || !it.name.startsWith('.')) && (options.query.isBlank() || it.name.contains(options.query, true)) }
    val detailComparator = when (options.sort) {
        FileSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER, LocalFile::name)
        FileSort.SIZE -> compareBy(LocalFile::size)
        FileSort.TIME -> compareBy(LocalFile::modifiedAt)
        FileSort.TYPE -> compareBy { it.mimeType.orEmpty() }
    }.let { if (options.descending) it.reversed() else it }
    return filtered.sortedWith(compareBy<LocalFile> { !it.isDirectory }.then(detailComparator))
}

private fun joinPath(parent: String, child: String): String = if (parent == "/") "/$child" else "${parent.trimEnd('/')}/$child"

private fun looksLikeText(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    if (bytes.any { it == 0.toByte() }) return false
    return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.isSuccess
}
