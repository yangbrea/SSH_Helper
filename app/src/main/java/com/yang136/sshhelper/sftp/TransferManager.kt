package com.yang136.sshhelper.sftp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yang136.sshhelper.R
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.data.AppDatabase
import com.yang136.sshhelper.data.ConflictPolicy
import com.yang136.sshhelper.data.HostRepository
import com.yang136.sshhelper.data.TransferBatchEntity
import com.yang136.sshhelper.data.TransferDirection
import com.yang136.sshhelper.data.TransferJobEntity
import com.yang136.sshhelper.data.TransferStatus
import com.yang136.sshhelper.security.CredentialVault
import com.yang136.sshhelper.security.VaultState
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.SessionFeature
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.SessionManager
import java.util.concurrent.ConcurrentHashMap
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class TransferJob(
    val id: Long,
    val hostId: Long,
    val direction: TransferDirection,
    val source: String,
    val destination: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val status: TransferStatus,
    val conflictPolicy: ConflictPolicy,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    val speedBytesPerSecond: Long get() = if (transferredBytes <= 0) 0 else {
        (transferredBytes * 1000L / (updatedAt - createdAt).coerceAtLeast(1000L)).coerceAtLeast(0)
    }
    val etaSeconds: Long? get() = speedBytesPerSecond.takeIf { it > 0 && totalBytes > transferredBytes }
        ?.let { (totalBytes - transferredBytes) / it }
}

data class TransferRequest(
    val hostId: Long,
    val sessionId: SessionId,
    val direction: TransferDirection,
    val source: String,
    val destination: String,
    val totalBytes: Long = -1,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
)

interface TransferManager {
    val jobs: StateFlow<List<TransferJob>>
    suspend fun enqueue(requests: List<TransferRequest>)
    suspend fun pause(id: Long)
    suspend fun resume(id: Long, sessionId: SessionId? = null)
    suspend fun cancel(id: Long)
    suspend fun retry(id: Long, sessionId: SessionId? = null)
    suspend fun setConflictPolicy(id: Long, policy: ConflictPolicy)
    suspend fun cancelForHost(hostId: Long)
}

class DefaultTransferManager(
    private val context: Context,
    private val database: AppDatabase,
    private val hostRepository: HostRepository,
    private val sessions: SessionManager,
    private val vault: CredentialVault,
) : TransferManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Long, Job>()
    private val sessionBindings = ConcurrentHashMap<Long, SessionId>()
    private val globalSlots = Semaphore(3)
    private val hostSlots = ConcurrentHashMap<Long, Semaphore>()
    private val dao = database.transferDao()

    override val jobs: StateFlow<List<TransferJob>> = dao.observeAll().map { list -> list.map(TransferJobEntity::toModel) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch { dao.pauseInterrupted() }
        scope.launch {
            vault.state.collect { state ->
                if (state is VaultState.Unlocked || state == VaultState.Disabled) {
                    dao.requeueWaitingUnlock()
                    runPending()
                }
            }
        }
    }

    override suspend fun enqueue(requests: List<TransferRequest>) {
        if (requests.isEmpty()) return
        val batchId = dao.insertBatch(TransferBatchEntity(title = requests.first().direction.displayName()))
        requests.forEach { request ->
            val id = dao.insertJob(TransferJobEntity(
                batchId = batchId,
                hostId = request.hostId,
                direction = request.direction,
                source = request.source,
                destination = request.destination,
                totalBytes = request.totalBytes,
                conflictPolicy = request.conflictPolicy,
            ))
            sessionBindings[id] = request.sessionId
        }
        scheduleBackgroundWork()
        runPending()
    }

    override suspend fun pause(id: Long) {
        running.remove(id)?.cancel()
        dao.setStatus(id, TransferStatus.PAUSED)
    }

    override suspend fun resume(id: Long, sessionId: SessionId?) {
        sessionId?.let { sessionBindings[id] = it }
        dao.setStatus(id, TransferStatus.QUEUED, null)
        scheduleBackgroundWork()
        runPending()
    }

    override suspend fun cancel(id: Long) {
        running.remove(id)?.cancel()
        dao.setStatus(id, TransferStatus.CANCELLED)
    }

    override suspend fun retry(id: Long, sessionId: SessionId?) = resume(id, sessionId)

    override suspend fun setConflictPolicy(id: Long, policy: ConflictPolicy) {
        val job = dao.get(id) ?: return
        dao.updateJob(job.copy(conflictPolicy = policy, status = TransferStatus.QUEUED, error = null, updatedAt = System.currentTimeMillis()))
        runPending()
    }

    override suspend fun cancelForHost(hostId: Long) {
        jobs.value.filter { it.hostId == hostId && it.status.isTransferOpen() }.forEach { cancel(it.id) }
    }

    suspend fun runPending() {
        dao.pending().filter { it.status == TransferStatus.QUEUED || it.status == TransferStatus.WAITING_NETWORK }.forEach { entity ->
            if (running.containsKey(entity.id)) return@forEach
            running[entity.id] = scope.launch {
                globalSlots.withPermit {
                    hostSlots.getOrPut(entity.hostId) { Semaphore(2) }.withPermit { execute(entity) }
                }
            }.also { job -> job.invokeOnCompletion { running.remove(entity.id) } }
        }
    }

    private suspend fun execute(initial: TransferJobEntity) {
        if (!isNetworkAvailable()) {
            dao.setStatus(initial.id, TransferStatus.WAITING_NETWORK, "等待网络恢复")
            return
        }
        val resolved = resolveSession(initial) ?: return
        dao.setStatus(initial.id, TransferStatus.RUNNING, null)
        val client = runCatching { sessions.newSftpClient(resolved.id) }.getOrElse {
            dao.setStatus(initial.id, TransferStatus.FAILED, it.message ?: "无法打开 SFTP Channel")
            return
        }
        try {
            when (initial.direction) {
                TransferDirection.UPLOAD -> upload(initial, client)
                TransferDirection.DOWNLOAD -> download(initial, client)
                TransferDirection.REMOTE_COPY -> remoteCopy(initial, resolved.id, client)
            }
            if (dao.get(initial.id)?.status == TransferStatus.RUNNING) dao.setStatus(initial.id, TransferStatus.COMPLETED)
        } catch (_: CancellationException) {
            if (dao.get(initial.id)?.status == TransferStatus.RUNNING) dao.setStatus(initial.id, TransferStatus.PAUSED)
        } catch (error: Throwable) {
            dao.setStatus(
                initial.id,
                if (isNetworkAvailable()) TransferStatus.FAILED else TransferStatus.WAITING_NETWORK,
                if (isNetworkAvailable()) error.message ?: "传输失败" else "网络已断开，等待恢复",
            )
        } finally {
            client.close()
            // Transfers manage their own SSH lifetime: sessions created purely for a transfer are
            // reclaimed once the job reaches a terminal state, so background jobs never leak slots.
            val status = dao.get(initial.id)?.status
            if (resolved.autoCreated && status != TransferStatus.PAUSED) runCatching { sessions.close(resolved.id) }
        }
    }

    private data class ResolvedSession(val id: SessionId, val autoCreated: Boolean)

    private suspend fun resolveSession(job: TransferJobEntity): ResolvedSession? {
        sessionBindings[job.id]?.let { bound ->
            if (sessions.sessions.value.any { it.id == bound && it.connection is ConnectionState.Connected }) return ResolvedSession(bound, false)
        }
        sessions.sessions.value.firstOrNull { it.profile.id == job.hostId && it.connection is ConnectionState.Connected }?.let {
            sessionBindings[job.id] = it.id
            return ResolvedSession(it.id, false)
        }
        if (vault.state.value == VaultState.Locked) {
            dao.setStatus(job.id, TransferStatus.WAITING_UNLOCK, "请解锁凭据保险库后继续")
            return null
        }
        val profile = hostRepository.getHost(job.hostId) ?: run {
            dao.setStatus(job.id, TransferStatus.FAILED, "主机配置不存在")
            return null
        }
        val created = sessions.create(profile, SessionFeature.SFTP) ?: run {
            dao.setStatus(job.id, TransferStatus.FAILED, "已达到会话上限")
            return null
        }
        sessionBindings[job.id] = created
        repeat(150) {
            val state = sessions.sessions.value.firstOrNull { it.id == created }
            when (state?.connection) {
                is ConnectionState.Connected -> return ResolvedSession(created, true)
                is ConnectionState.Error -> {
                    dao.setStatus(job.id, if (state.needsVaultUnlock) TransferStatus.WAITING_UNLOCK else TransferStatus.FAILED, (state.connection as ConnectionState.Error).message)
                    return null
                }
                else -> if (state?.needsVaultUnlock == true) {
                    dao.setStatus(job.id, TransferStatus.WAITING_UNLOCK, "请解锁凭据保险库后继续")
                    return null
                }
            }
            delay(100)
        }
        dao.setStatus(job.id, TransferStatus.FAILED, "等待 SSH 连接超时")
        return null
    }

    private suspend fun upload(job: TransferJobEntity, client: SftpClient) {
        val sourceUri = Uri.parse(job.source)
        val resolver = context.contentResolver
        val total = if (job.totalBytes >= 0) job.totalBytes else DocumentFile.fromSingleUri(context, sourceUri)?.length() ?: -1
        val target = resolveRemoteConflict(client, job.destination, job.conflictPolicy, total) ?: return dao.setStatus(job.id, TransferStatus.COMPLETED, "已跳过")
        val temporary = ".${target.substringAfterLast('/')}.sshhelper-${job.id}.part"
        val tempPath = joinRemotePath(target.substringBeforeLast('/', "/"), temporary)
        dao.setTemporaryPath(job.id, tempPath, total)
        val offset = if (job.conflictPolicy == ConflictPolicy.RESUME) runCatching { client.stat(tempPath).size }.getOrDefault(0) else 0
        resolver.openInputStream(sourceUri)?.use { input ->
            if (offset > 0) input.skipFully(offset)
            client.upload(input, tempPath, offset) { transferred ->
                scope.launch { dao.setProgress(job.id, offset + transferred, total, TransferStatus.RUNNING) }
                running[job.id]?.isActive == true
            }
        } ?: error("无法读取本地文件")
        runCatching { client.delete(target) }
        client.rename(tempPath, target)
    }

    private suspend fun download(job: TransferJobEntity, client: SftpClient) {
        val remote = client.stat(job.source)
        val root = asDocumentUri(context, Uri.parse(job.destination))
        val finalName = remote.name
        val existing = findLocalChild(context, root, finalName)
        if (existing != null) {
            when (job.conflictPolicy) {
                ConflictPolicy.SKIP -> return dao.setStatus(job.id, TransferStatus.COMPLETED, "已跳过")
                ConflictPolicy.ASK -> return dao.setStatus(job.id, TransferStatus.PAUSED, "目标文件已存在，请选择冲突策略")
                else -> Unit
            }
        }
        val desired = if (job.conflictPolicy == ConflictPolicy.RENAME) uniqueLocalName(context, root, finalName) else finalName
        val partName = ".$finalName.sshhelper-${job.id}.part"
        var part = findLocalChild(context, root, partName)
            ?: DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", partName)
            ?: error("无法创建本地临时文件")
        dao.setTemporaryPath(job.id, part.toString(), remote.size)
        var offset = if (job.conflictPolicy == ConflictPolicy.RESUME) localDocumentSize(context, part) else 0
        context.contentResolver.openFileDescriptor(part, "rw")?.use { descriptor ->
            java.io.FileOutputStream(descriptor.fileDescriptor).use { output ->
                if (offset > 0) {
                    runCatching { output.channel.position(offset) }.onFailure {
                        // Some cloud-backed SAF providers cannot seek. Restart explicitly instead
                        // of pretending that the task resumed successfully.
                        offset = 0
                        output.channel.truncate(0)
                    }
                } else output.channel.truncate(0)
                client.download(job.source, output, offset) { transferred ->
                    scope.launch { dao.setProgress(job.id, offset + transferred, remote.size, TransferStatus.RUNNING) }
                    running[job.id]?.isActive == true
                }
            }
        } ?: error("无法写入本地文件")
        if (existing != null && job.conflictPolicy != ConflictPolicy.RENAME) {
            check(DocumentsContract.deleteDocument(context.contentResolver, existing)) { "无法覆盖本地同名文件" }
        }
        part = DocumentsContract.renameDocument(context.contentResolver, part, desired)
            ?: error("文件已下载到部分文件，但当前 Provider 不支持原子重命名")
    }

    private suspend fun remoteCopy(job: TransferJobEntity, sessionId: SessionId, sourceClient: SftpClient) {
        val second = sessions.newSftpClient(sessionId)
        try {
            val source = sourceClient.stat(job.source)
            val target = resolveRemoteConflict(second, job.destination, job.conflictPolicy, source.size)
                ?: return dao.setStatus(job.id, TransferStatus.COMPLETED, "已跳过")
            val temporary = joinRemotePath(target.substringBeforeLast('/', "/"), ".${target.substringAfterLast('/')}.sshhelper-${job.id}.part")
            dao.setTemporaryPath(job.id, temporary, source.size)
            val pipeIn = java.io.PipedInputStream(256 * 1024)
            val pipeOut = java.io.PipedOutputStream(pipeIn)
            val download = scope.launch { pipeOut.use { sourceClient.download(job.source, it) } }
            pipeIn.use { input ->
                second.upload(input, temporary) { transferred ->
                    scope.launch { dao.setProgress(job.id, transferred, source.size, TransferStatus.RUNNING) }
                    running[job.id]?.isActive == true
                }
            }
            download.join()
            runCatching { second.delete(target) }
            second.rename(temporary, target)
        } finally {
            second.close()
        }
    }

    private suspend fun resolveRemoteConflict(client: SftpClient, path: String, policy: ConflictPolicy, size: Long): String? {
        val exists = runCatching { client.stat(path, false) }.isSuccess
        if (!exists) return path
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.ASK -> error("远端已存在同名文件，请选择覆盖、续传、跳过或自动改名")
            ConflictPolicy.RENAME -> {
                val parent = path.substringBeforeLast('/', "/")
                val name = path.substringAfterLast('/')
                val base = name.substringBeforeLast('.', name)
                val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                (1..999).firstNotNullOfOrNull { number ->
                    joinRemotePath(parent, "$base ($number)$ext").takeIf { runCatching { client.stat(it, false) }.isFailure }
                } ?: error("无法生成可用文件名")
            }
            ConflictPolicy.RESUME -> path
            ConflictPolicy.OVERWRITE -> path
        }
    }

    private fun scheduleBackgroundWork() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val info = JobInfo.Builder(UIDT_JOB_ID, ComponentName(context, SftpTransferJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setUserInitiated(true)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(info)
        } else {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SftpTransferWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val WORK_NAME = "ssh-helper-sftp-transfers"
        const val UIDT_JOB_ID = 12_021
    }
}

class SftpTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setForeground(ForegroundInfo(NOTIFICATION_ID, createTransferNotification(applicationContext), foregroundServiceType()))
        val manager = (applicationContext as SshHelperApplication).container.transferManager
        manager.runPending()
        while (manager.jobs.value.any { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED }) delay(500)
        return Result.success()
    }

    private fun foregroundServiceType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else 0

    private companion object { const val NOTIFICATION_ID = 1201 }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class SftpTransferJobService : JobService() {
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        setNotification(params, 1201, createTransferNotification(this), JOB_END_NOTIFICATION_POLICY_REMOVE)
        runningJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val manager = (application as SshHelperApplication).container.transferManager
            manager.runPending()
            while (manager.jobs.value.any { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED }) delay(500)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = null
        return true
    }
}

class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val manager = (context.applicationContext as SshHelperApplication).container.transferManager
            val active = manager.jobs.value.filter { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED }
            when (intent.action) {
                ACTION_PAUSE -> active.forEach { manager.pause(it.id) }
                ACTION_CANCEL -> active.forEach { manager.cancel(it.id) }
            }
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.yang136.sshhelper.PAUSE_TRANSFERS"
        const val ACTION_CANCEL = "com.yang136.sshhelper.CANCEL_TRANSFERS"
    }
}

private fun createTransferNotification(context: Context): Notification {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannel(NotificationChannel(TRANSFER_CHANNEL_ID, "SFTP 文件传输", NotificationManager.IMPORTANCE_LOW))
    }
    fun actionIntent(action: String, code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, TransferActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, TRANSFER_CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("SSH Helper 文件传输")
        .setContentText("正在处理上传和下载任务")
        .setOngoing(true)
        .setProgress(0, 0, true)
        .addAction(0, "全部暂停", actionIntent(TransferActionReceiver.ACTION_PAUSE, 1))
        .addAction(0, "全部取消", actionIntent(TransferActionReceiver.ACTION_CANCEL, 2))
        .build()
}

private const val TRANSFER_CHANNEL_ID = "sftp_transfers"

private fun TransferStatus.isTransferOpen(): Boolean = this in setOf(
    TransferStatus.QUEUED, TransferStatus.RUNNING, TransferStatus.PAUSED,
    TransferStatus.WAITING_NETWORK, TransferStatus.WAITING_UNLOCK,
)

private fun TransferJobEntity.toModel() = TransferJob(
    id, hostId, direction, source, destination, totalBytes, transferredBytes, status, conflictPolicy, error, createdAt, updatedAt,
)

private fun TransferDirection.displayName(): String = when (this) {
    TransferDirection.UPLOAD -> "上传"
    TransferDirection.DOWNLOAD -> "下载"
    TransferDirection.REMOTE_COPY -> "远程复制"
}

private fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) {
            if (read() < 0) error("本地文件短于续传位置")
            remaining--
        } else remaining -= skipped
    }
}

private fun asDocumentUri(context: Context, uri: Uri): Uri =
    if (DocumentsContract.isDocumentUri(context, uri)) uri
    else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))

private fun findLocalChild(context: Context, parent: Uri, name: String): Uri? {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == name) return@use DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
        }
        null
    }
}

private fun localDocumentSize(context: Context, uri: Uri): Long {
    val projection = arrayOf(DocumentsContract.Document.COLUMN_SIZE)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
    } ?: 0L
}

private fun uniqueLocalName(context: Context, root: Uri, original: String): String {
    val base = original.substringBeforeLast('.', original)
    val ext = original.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    return (1..999).firstOrNull { findLocalChild(context, root, "$base ($it)$ext") == null }?.let { "$base ($it)$ext" }
        ?: "${base}-${System.currentTimeMillis()}$ext"
}
