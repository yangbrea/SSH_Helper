package com.yang136.sshhelper.documents

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yang136.sshhelper.MainActivity
import com.yang136.sshhelper.R
import com.yang136.sshhelper.data.AppDatabase
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.diagnosticlog.DiagnosticSink
import com.yang136.sshhelper.diagnosticlog.NoOpDiagnosticSink
import com.yang136.sshhelper.sftp.RemoteFile
import com.yang136.sshhelper.sftp.RemoteFileType
import com.yang136.sshhelper.sftp.SftpClient
import com.yang136.sshhelper.sftp.joinRemotePath
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.JschSshSession
import com.yang136.sshhelper.ssh.clearCredential
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DocumentRootInfo(val host: HostProfile)

data class OpenedDocument(
    val hostId: Long,
    val remote: RemoteFile,
    val cache: File,
    var baselineSize: Long,
    var baselineModifiedAt: Long,
    var recoveryId: Long? = null,
)

interface DocumentsBackend {
    fun isDeviceUnlocked(): Boolean
    suspend fun roots(): List<DocumentRootInfo>
    suspend fun home(hostId: Long): String
    suspend fun stat(hostId: Long, path: String?): RemoteFile
    suspend fun children(hostId: Long, path: String?): List<RemoteFile>
    suspend fun create(hostId: Long, parentPath: String?, name: String, directory: Boolean): RemoteFile
    suspend fun rename(hostId: Long, path: String, newName: String): RemoteFile
    suspend fun delete(hostId: Long, path: String)
    suspend fun isChild(hostId: Long, parentPath: String?, childPath: String): Boolean
    suspend fun prepareOpen(hostId: Long, path: String, download: Boolean): OpenedDocument
    suspend fun commit(opened: OpenedDocument)
    suspend fun release(opened: OpenedDocument, keepRecovery: Boolean)
    fun close(hostId: Long? = null)
}

class SshDocumentsBackend(
    private val context: Context,
    private val database: AppDatabase,
    private val access: DocumentAccessManager,
    diagnostics: DiagnosticSink = NoOpDiagnosticSink,
) : DocumentsBackend {
    private val pool = DocumentsSessionPool(database, access, diagnostics)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDirectory = File(context.noBackupFilesDir, "documents-provider").apply { mkdirs() }

    init {
        access.closeConnections = ::close
        access.retryWritebackHandler = ::retryWriteback
        scope.launch {
            val retained = database.documentAccessDao().writebacks().mapTo(hashSetOf()) { it.localPath }
            cacheDirectory.listFiles()?.filter {
                it.isFile && it.absolutePath !in retained && System.currentTimeMillis() - it.lastModified() > ORPHAN_MAX_AGE_MS
            }?.forEach(File::delete)
        }
    }

    override fun isDeviceUnlocked(): Boolean = access.isDeviceUnlocked()

    override suspend fun roots(): List<DocumentRootInfo> {
        if (!access.isDeviceUnlocked()) return emptyList()
        return access.roots.value.mapNotNull { grant -> database.hostDao().get(grant.hostId)?.toProfileForDocuments() }
            .map(::DocumentRootInfo)
    }

    override suspend fun home(hostId: Long): String = pool.withClient(hostId) { realPath(home()) }

    override suspend fun stat(hostId: Long, path: String?): RemoteFile = pool.withClient(hostId) {
        val home = guardedHome(this)
        if (path == null) rootFile(home) else stat(guardExisting(this, home, path), followLinks = true)
    }

    override suspend fun children(hostId: Long, path: String?): List<RemoteFile> = pool.withClient(hostId) {
        val home = guardedHome(this)
        val directory = if (path == null) home else guardExisting(this, home, path)
        list(directory).filter { child ->
            if (child.type != RemoteFileType.SYMLINK) true
            else runCatching { isWithin(home, realPath(child.path)) }.getOrDefault(false)
        }
    }

    override suspend fun create(hostId: Long, parentPath: String?, name: String, directory: Boolean): RemoteFile =
        pool.withClient(hostId) {
            validateName(name)
            val home = guardedHome(this)
            val parent = if (parentPath == null) home else guardExisting(this, home, parentPath)
            val target = uniqueChild(this, parent, name)
            if (directory) mkdir(target) else upload(ByteArray(0).inputStream(), target)
            stat(target, followLinks = true)
        }

    override suspend fun rename(hostId: Long, path: String, newName: String): RemoteFile = pool.withClient(hostId) {
        validateName(newName)
        val home = guardedHome(this)
        val source = guardExisting(this, home, path)
        check(source != home) { "不能重命名用户主目录" }
        val target = joinRemotePath(source.substringBeforeLast('/', home), newName)
        check(runCatching { stat(target, false) }.isFailure) { "目标名称已存在" }
        rename(source, target)
        stat(target, followLinks = true)
    }

    override suspend fun delete(hostId: Long, path: String) = pool.withClient(hostId) {
        val home = guardedHome(this)
        val target = guardExisting(this, home, path)
        check(target != home) { "不能删除用户主目录" }
        delete(target, recursive = true)
    }

    override suspend fun isChild(hostId: Long, parentPath: String?, childPath: String): Boolean = pool.withClient(hostId) {
        val home = guardedHome(this)
        val parent = if (parentPath == null) home else guardExisting(this, home, parentPath)
        val child = guardExisting(this, home, childPath)
        child != parent && isWithin(parent, child)
    }

    override suspend fun prepareOpen(hostId: Long, path: String, download: Boolean): OpenedDocument = pool.withClient(hostId) {
        val home = guardedHome(this)
        val safe = guardExisting(this, home, path)
        val remote = stat(safe, followLinks = true)
        require(remote.type == RemoteFileType.FILE) { "只能打开普通文件" }
        val cache = File(cacheDirectory, "${hostId}-${UUID.randomUUID()}.cache")
        if (download) FileOutputStream(cache).use { download(safe, it) } else cache.createNewFile()
        OpenedDocument(hostId, remote, cache, remote.size, remote.modifiedAt)
    }

    override suspend fun commit(opened: OpenedDocument) {
        try {
            pool.withClient(opened.hostId) {
                val home = guardedHome(this)
                val target = guardExisting(this, home, opened.remote.path)
                val updated = atomicReplace(
                    cache = opened.cache,
                    target = target,
                    displayName = opened.remote.name,
                    baselineSize = opened.baselineSize,
                    baselineModifiedAt = opened.baselineModifiedAt,
                )
                opened.baselineSize = updated.size
                opened.baselineModifiedAt = updated.modifiedAt
                opened.recoveryId?.let { access.removeWriteback(it) }
                opened.recoveryId = null
            }
        } catch (error: Throwable) {
            val recoveryId = access.addFailedWriteback(
                opened.hostId,
                opened.remote.path,
                opened.cache.absolutePath,
                opened.baselineSize,
                opened.baselineModifiedAt,
                error.message ?: "写回失败",
            )
            opened.recoveryId?.let { access.removeWriteback(it) }
            opened.recoveryId = recoveryId
            notifyWritebackFailure(opened.remote.name, error.message ?: "写回失败")
            throw error
        }
    }

    override suspend fun release(opened: OpenedDocument, keepRecovery: Boolean) {
        if (!keepRecovery && opened.recoveryId == null) opened.cache.delete()
    }

    override fun close(hostId: Long?) = pool.close(hostId)

    suspend fun retryWriteback(id: Long) {
        val item = database.documentAccessDao().writeback(id) ?: return
        val cache = File(item.localPath)
        check(cache.isFile) { "恢复副本不存在" }
        val remote = stat(item.hostId, item.remotePath)
        val opened = OpenedDocument(
            item.hostId, remote, cache, item.baselineSize, item.baselineModifiedAt, item.id,
        )
        commit(opened)
        release(opened, keepRecovery = false)
    }

    private suspend fun guardedHome(client: SftpClient): String = client.realPath(client.home())

    private suspend fun guardExisting(client: SftpClient, home: String, path: String): String {
        require(!path.split('/').contains("..")) { "路径包含非法父目录" }
        val real = client.realPath(path)
        check(isWithin(home, real)) { "拒绝访问用户主目录之外的路径" }
        return real
    }

    private fun rootFile(home: String) = RemoteFile(
        path = home,
        name = home.substringAfterLast('/').ifEmpty { "/" },
        type = RemoteFileType.DIRECTORY,
        size = 0,
        modifiedAt = 0,
        permissions = 0x1ED,
        uid = 0,
        gid = 0,
    )

    private suspend fun uniqueChild(client: SftpClient, parent: String, requested: String): String {
        val base = requested.substringBeforeLast('.', requested)
        val extension = requested.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val first = joinRemotePath(parent, requested)
        if (runCatching { client.stat(first, false) }.isFailure) return first
        return (1..999).firstNotNullOfOrNull { index ->
            joinRemotePath(parent, "$base ($index)$extension").takeIf { runCatching { client.stat(it, false) }.isFailure }
        } ?: error("无法生成不重复的文件名")
    }

    private fun notifyWritebackFailure(name: String, error: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(RECOVERY_CHANNEL, "系统文件写回", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            7301,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                .putExtra(MainActivity.EXTRA_SETTINGS_SECTION, "documents"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            RECOVERY_NOTIFICATION,
            NotificationCompat.Builder(context, RECOVERY_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("远端文件保存失败：$name")
                .setContentText("$error；本地恢复副本已保留")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val RECOVERY_CHANNEL = "document_writebacks"
        const val RECOVERY_NOTIFICATION = 7301
        const val ORPHAN_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}

private class DocumentsSessionPool(
    private val database: AppDatabase,
    private val access: DocumentAccessManager,
    private val diagnostics: DiagnosticSink,
) {
    private data class Slot(val session: JschSshSession, var leases: Int = 0, var lastUsed: Long = 0)
    private val mutex = Mutex()
    private val slots = linkedMapOf<Long, Slot>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun <T> withClient(hostId: Long, block: suspend SftpClient.() -> T): T {
        val slot = acquire(hostId)
        val client = try {
            slot.session.openSftpClient()
        } catch (error: Throwable) {
            release(hostId, broken = true)
            throw error
        }
        return try {
            client.block()
        } finally {
            client.close()
            release(hostId, broken = false)
        }
    }

    private suspend fun acquire(hostId: Long): Slot = mutex.withLock {
        slots[hostId]?.takeIf { it.session.state.value is ConnectionState.Connected }?.also {
            it.leases++
            it.lastUsed = System.currentTimeMillis()
            return@withLock it
        }
        if (slots.size >= MAX_SESSIONS) {
            val victim = slots.entries.filter { it.value.leases == 0 }.minByOrNull { it.value.lastUsed }
            victim?.let { slots.remove(it.key)?.session?.close() }
            check(slots.size < MAX_SESSIONS) { "系统文件连接数已达到上限，请稍后重试" }
        }
        val authorized = access.authorizedRoute(hostId)
        val session = JschSshSession(database.knownHostDao(), allowHostKeyPrompt = false, diagnostics = diagnostics)
        try {
            session.connect(authorized.route.copy(diagnosticFeature = "DOCUMENTS"), authorized.credentials, openShell = false)
        } finally {
            clearCredential(authorized.credentials.target)
            clearCredential(authorized.credentials.jump)
        }
        val state = session.state.value
        if (state is ConnectionState.Error) access.notifyAccessProblem(hostId, state.message)
        check(state is ConnectionState.Connected) {
            (state as? ConnectionState.Error)?.message ?: "无法连接 SSH 主机"
        }
        Slot(session, leases = 1, lastUsed = System.currentTimeMillis()).also { slots[hostId] = it }
    }

    private suspend fun release(hostId: Long, broken: Boolean) {
        mutex.withLock {
            val slot = slots[hostId] ?: return
            slot.leases = (slot.leases - 1).coerceAtLeast(0)
            slot.lastUsed = System.currentTimeMillis()
            if (broken) slots.remove(hostId)?.session?.close()
        }
        if (!broken) scope.launch {
            delay(IDLE_TIMEOUT_MS)
            mutex.withLock {
                val current = slots[hostId] ?: return@withLock
                if (current.leases == 0 && System.currentTimeMillis() - current.lastUsed >= IDLE_TIMEOUT_MS) {
                    slots.remove(hostId)?.session?.close()
                }
            }
        }
    }

    fun close(hostId: Long?) {
        val closing = runBlocking(Dispatchers.IO) {
            mutex.withLock {
                if (hostId == null) slots.values.map { it.session }.also { slots.clear() }
                else listOfNotNull(slots.remove(hostId)?.session)
            }
        }
        closing.forEach(JschSshSession::close)
    }

    private companion object {
        const val MAX_SESSIONS = 3
        const val IDLE_TIMEOUT_MS = 60_000L
    }
}

private fun validateName(value: String) {
    require(value.isNotBlank() && value != "." && value != ".." && '/' !in value && '\u0000' !in value) {
        "文件名无效"
    }
}

internal fun isWithin(home: String, path: String): Boolean {
    val normalizedHome = home.trimEnd('/').ifEmpty { "/" }
    val normalizedPath = path.trimEnd('/').ifEmpty { "/" }
    return normalizedHome == "/" || normalizedPath == normalizedHome || normalizedPath.startsWith("$normalizedHome/")
}

internal suspend fun SftpClient.atomicReplace(
    cache: File,
    target: String,
    displayName: String,
    baselineSize: Long,
    baselineModifiedAt: Long,
    token: String = UUID.randomUUID().toString(),
): RemoteFile {
    val latest = stat(target, followLinks = true)
    check(latest.size == baselineSize && latest.modifiedAt == baselineModifiedAt) {
        "远端文件已被其他程序修改，本地副本已保留"
    }
    val parent = target.substringBeforeLast('/', "/")
    val temporary = joinRemotePath(parent, ".$displayName.sshhelper-docs-$token.part")
    val backup = joinRemotePath(parent, ".$displayName.sshhelper-docs-$token.backup")
    try {
        FileInputStream(cache).use { upload(it, temporary) }
        chmod(temporary, latest.permissions)
        rename(target, backup)
        runCatching { rename(temporary, target) }.getOrElse { failure ->
            runCatching { rename(backup, target) }.getOrElse { rollback -> failure.addSuppressed(rollback) }
            throw failure
        }
        runCatching { delete(backup) }
        return stat(target, followLinks = true)
    } catch (error: Throwable) {
        runCatching { delete(temporary) }
        throw error
    }
}

private fun com.yang136.sshhelper.data.HostEntity.toProfileForDocuments() = HostProfile(
    id, name, hostname, port, username, authType, rememberCredential, privateKeyName,
    autoReconnect, jumpHostId, proxyType, proxyHost, proxyPort, proxyUsername,
)
