package com.yang136.sshhelper.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RemoteFileType { FILE, DIRECTORY, SYMLINK, OTHER }

data class RemoteFile(
    val path: String,
    val name: String,
    val type: RemoteFileType,
    val size: Long,
    val modifiedAt: Long,
    val permissions: Int,
    val uid: Int,
    val gid: Int,
    val linkTarget: String? = null,
)

data class RemoteFileSystem(val size: Long, val used: Long, val available: Long, val capacityPercent: Int)

/**
 * A cancellable remote byte stream opened at [SftpClient.openRead]. [size] is the full
 * file size reported by the server up front, which lets callers implement seeking and
 * progress without a separate stat round-trip. Closing [stream] aborts the transfer.
 */
data class RemoteRead(val size: Long, val stream: InputStream)

interface SftpClient : AutoCloseable {
    suspend fun home(): String
    suspend fun realPath(path: String): String
    suspend fun list(path: String): List<RemoteFile>
    suspend fun stat(path: String, followLinks: Boolean = true): RemoteFile
    suspend fun fileSystem(path: String): RemoteFileSystem
    suspend fun mkdir(path: String)
    suspend fun rename(source: String, target: String)
    suspend fun delete(path: String, recursive: Boolean = false)
    suspend fun chmod(path: String, mode: Int)
    suspend fun chown(path: String, uid: Int)
    suspend fun chgrp(path: String, gid: Int)
    suspend fun symlink(target: String, linkPath: String)
    suspend fun readlink(path: String): String
    suspend fun download(path: String, output: OutputStream, offset: Long = 0, progress: (Long) -> Boolean = { true })
    suspend fun upload(input: InputStream, path: String, offset: Long = 0, progress: (Long) -> Boolean = { true })

    /**
     * Opens a sequential, cancellable read of [path] starting at [offset] bytes. Intended
     * for streaming previews: the returned stream must be closed by the caller (ideally in
     * a `use` block) to abort the transfer and free the channel.
     */
    suspend fun openRead(path: String, offset: Long = 0): RemoteRead
}

class JschSftpClient(private val channel: ChannelSftp) : SftpClient {
    private val mutex = Mutex()
    // Transfers launched by openRead run on their own scope so a long-lived stream never
    // holds the channel mutex (the caller is expected to use a dedicated channel anyway).
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun home(): String = io { channel.home }

    override suspend fun realPath(path: String): String = io { channel.realpath(normalizeRemotePath(path)) }

    override suspend fun list(path: String): List<RemoteFile> = io {
        @Suppress("UNCHECKED_CAST")
        (channel.ls(normalizeRemotePath(path)) as java.util.Vector<ChannelSftp.LsEntry>)
            .asSequence()
            .filterNot { it.filename == "." || it.filename == ".." }
            .map { entry -> entry.attrs.toRemote(joinRemotePath(path, entry.filename), entry.filename, entry.attrs.takeIf(SftpATTRS::isLink)?.let { runCatching { channel.readlink(joinRemotePath(path, entry.filename)) }.getOrNull() }) }
            .toList()
    }

    override suspend fun stat(path: String, followLinks: Boolean): RemoteFile = io {
        val normalized = normalizeRemotePath(path)
        val attrs = if (followLinks) channel.stat(normalized) else channel.lstat(normalized)
        attrs.toRemote(normalized, normalized.substringAfterLast('/').ifEmpty { "/" }, if (attrs.isLink) runCatching { channel.readlink(normalized) }.getOrNull() else null)
    }

    override suspend fun fileSystem(path: String): RemoteFileSystem = io {
        val stat = channel.statVFS(normalizeRemotePath(path))
        RemoteFileSystem(stat.size, stat.used, stat.availForNonRoot, stat.capacity)
    }

    override suspend fun mkdir(path: String) = ioUnit { channel.mkdir(normalizeRemotePath(path)) }
    override suspend fun rename(source: String, target: String) = ioUnit { channel.rename(normalizeRemotePath(source), normalizeRemotePath(target)) }

    override suspend fun delete(path: String, recursive: Boolean) = ioUnit {
        deleteInternal(normalizeRemotePath(path), recursive)
    }

    override suspend fun chmod(path: String, mode: Int) = ioUnit { channel.chmod(mode and 0xFFF, normalizeRemotePath(path)) }
    override suspend fun chown(path: String, uid: Int) = ioUnit { channel.chown(uid, normalizeRemotePath(path)) }
    override suspend fun chgrp(path: String, gid: Int) = ioUnit { channel.chgrp(gid, normalizeRemotePath(path)) }
    override suspend fun symlink(target: String, linkPath: String) = ioUnit { channel.symlink(target, normalizeRemotePath(linkPath)) }
    override suspend fun readlink(path: String): String = io { channel.readlink(normalizeRemotePath(path)) }

    override suspend fun download(path: String, output: OutputStream, offset: Long, progress: (Long) -> Boolean) = ioUnit {
        val monitor = CountingMonitor(progress)
        channel.get(normalizeRemotePath(path), output, monitor, ChannelSftp.RESUME, offset.coerceAtLeast(0))
    }

    override suspend fun upload(input: InputStream, path: String, offset: Long, progress: (Long) -> Boolean) = ioUnit {
        val monitor = CountingMonitor(progress)
        if (offset > 0) {
            channel.put(normalizeRemotePath(path), monitor, ChannelSftp.RESUME, offset).use { output -> input.copyTo(output) }
        } else {
            channel.put(input, normalizeRemotePath(path), monitor, ChannelSftp.OVERWRITE)
        }
    }

    override suspend fun openRead(path: String, offset: Long): RemoteRead {
        val normalized = normalizeRemotePath(path)
        // Validates existence/readability synchronously (and reports a proper error), while
        // the actual byte transfer runs in the background and streams through a pipe.
        val size = stat(normalized).size
        val stream = io {
            val buffer = PipedInputStream(PIPE_BUFFER_SIZE)
            val output = PipedOutputStream(buffer)
            val failure = AtomicReference<Throwable?>()
            val abortRequested = AtomicBoolean(false)
            val monitor = object : com.jcraft.jsch.SftpProgressMonitor {
                override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
                // Returning false makes JSch stop between chunks cleanly, keeping the
                // channel usable for follow-up requests (e.g. a seek on the same channel).
                override fun count(count: Long) = !abortRequested.get()
                override fun end() = Unit
            }
            val job = streamScope.launch {
                try {
                    channel.get(normalized, output, monitor, ChannelSftp.RESUME, offset.coerceAtLeast(0))
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    // Signals EOF to the reader; on abort this unblocks a blocked read().
                    runCatching { output.close() }
                }
            }
            object : InputStream() {
                @Volatile
                private var closed = false

                override fun read(): Int {
                    check(!closed) { "流已关闭" }
                    failure.get()?.let { throw it.asSftpFailure() }
                    return buffer.read()
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    check(!closed) { "流已关闭" }
                    failure.get()?.let { throw it.asSftpFailure() }
                    return buffer.read(b, off, len)
                }

                override fun close() {
                    closed = true
                    abortRequested.set(true)
                    runCatching { buffer.close() }
                    runCatching { output.close() }
                    // Wait for the transfer coroutine to settle so the channel is quiescent
                    // before any follow-up request; the pipe close unblocks a stuck writer.
                    runBlocking { withTimeoutOrNull(2_000) { job.join() } }
                    job.cancel()
                }
            }
        }
        return RemoteRead(size, stream)
    }

    override fun close() {
        streamScope.cancel()
        channel.disconnect()
    }

    private fun deleteInternal(path: String, recursive: Boolean) {
        val attrs = channel.lstat(path)
        if (!attrs.isDir || attrs.isLink) {
            channel.rm(path)
            return
        }
        if (!recursive) {
            channel.rmdir(path)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val children = channel.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
        children.filterNot { it.filename == "." || it.filename == ".." }.forEach { child ->
            deleteInternal(joinRemotePath(path, child.filename), recursive = true)
        }
        channel.rmdir(path)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching(block).getOrElse { throw it.asSftpFailure() } }
    }

    private suspend fun ioUnit(block: () -> Unit): Unit = io(block)
}

private class CountingMonitor(private val callback: (Long) -> Boolean) : com.jcraft.jsch.SftpProgressMonitor {
    private var transferred = 0L
    override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
    override fun count(count: Long): Boolean {
        transferred += count
        return callback(transferred)
    }
    override fun end() = Unit
}

private const val PIPE_BUFFER_SIZE = 256 * 1024

private fun SftpATTRS.toRemote(path: String, name: String, target: String?): RemoteFile = RemoteFile(
    path = normalizeRemotePath(path),
    name = name,
    type = when {
        isLink -> RemoteFileType.SYMLINK
        isDir -> RemoteFileType.DIRECTORY
        isReg -> RemoteFileType.FILE
        else -> RemoteFileType.OTHER
    },
    size = size,
    modifiedAt = mTime.toLong() * 1000L,
    permissions = permissions and 0xFFF,
    uid = uId,
    gid = gId,
    linkTarget = target,
)

internal fun normalizeRemotePath(path: String): String {
    val absolute = path.startsWith('/')
    val parts = ArrayDeque<String>()
    path.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(part)
        }
    }
    val joined = parts.joinToString("/")
    return when {
        absolute && joined.isEmpty() -> "/"
        absolute -> "/$joined"
        joined.isEmpty() -> "."
        else -> joined
    }
}

internal fun joinRemotePath(parent: String, child: String): String =
    normalizeRemotePath(if (parent == "/") "/$child" else "${parent.trimEnd('/')}/$child")

private fun Throwable.asSftpFailure(): Throwable = if (this is SftpException) {
    val prefix = when (id) {
        ChannelSftp.SSH_FX_NO_SUCH_FILE -> "文件或目录不存在"
        ChannelSftp.SSH_FX_PERMISSION_DENIED -> "权限不足"
        ChannelSftp.SSH_FX_CONNECTION_LOST, ChannelSftp.SSH_FX_NO_CONNECTION -> "SFTP 连接已断开"
        ChannelSftp.SSH_FX_OP_UNSUPPORTED -> "服务器不支持此操作"
        else -> "SFTP 操作失败"
    }
    IllegalStateException("$prefix${message?.let { "：$it" }.orEmpty()}", this)
} else this
