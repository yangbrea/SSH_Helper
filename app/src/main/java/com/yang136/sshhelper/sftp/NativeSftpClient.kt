package com.yang136.sshhelper.sftp

import com.yang136.sshhelper.ssh.native.NativeSshRuntime
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * libssh2-backed SFTP facade. The SSH runtime owns all native SFTP resources;
 * this class only retains opaque ids and serializes each logical client's
 * control operations. File data crosses JNI in bounded chunks.
 */
class NativeSftpClient internal constructor(
    private val runtime: NativeSshRuntime,
    private val clientHandle: Long,
    private val onClose: (NativeSftpClient) -> Unit = {},
) : SftpClient {
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val openHandles = ConcurrentHashMap.newKeySet<Long>()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun home(): String = realPath(".")

    override suspend fun realPath(path: String): String = io {
        command(CMD_REALPATH, normalizeRemotePath(path))
    }

    override suspend fun list(path: String): List<RemoteFile> = io {
        val normalized = normalizeRemotePath(path)
        command(CMD_LIST, normalized).lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
                val fields = line.split('\t')
                check(fields.size == 7) { "SFTP 目录条目格式无效" }
                val name = decodeNativeSftpField(fields[0])
                val type = fields[1].toRemoteFileType()
                val childPath = joinRemotePath(normalized, name)
                RemoteFile(
                    path = childPath,
                    name = name,
                    type = type,
                    size = fields[2].toLongOrZero(),
                    modifiedAt = fields[3].toLongOrZero() * 1_000L,
                    permissions = fields[4].toIntOrZero(),
                    uid = fields[5].toIntOrZero(),
                    gid = fields[6].toIntOrZero(),
                    linkTarget = if (type == RemoteFileType.SYMLINK) {
                        runCatching { command(CMD_READLINK, childPath) }.getOrNull()
                    } else null,
                )
            }
            .toList()
    }

    override suspend fun stat(path: String, followLinks: Boolean): RemoteFile = io {
        val normalized = normalizeRemotePath(path)
        val fields = command(
            CMD_STAT,
            normalized,
            value = if (followLinks) 1 else 0,
        ).split('\t')
        check(fields.size == 6) { "SFTP 文件属性格式无效" }
        val type = fields[0].toRemoteFileType()
        RemoteFile(
            path = normalized,
            name = normalized.substringAfterLast('/').ifEmpty { "/" },
            type = type,
            size = fields[1].toLongOrZero(),
            modifiedAt = fields[2].toLongOrZero() * 1_000L,
            permissions = fields[3].toIntOrZero(),
            uid = fields[4].toIntOrZero(),
            gid = fields[5].toIntOrZero(),
            linkTarget = if (type == RemoteFileType.SYMLINK) {
                runCatching { command(CMD_READLINK, normalized) }.getOrNull()
            } else null,
        )
    }

    override suspend fun fileSystem(path: String): RemoteFileSystem = io {
        val fields = command(CMD_STATVFS, normalizeRemotePath(path)).split('\t')
        check(fields.size == 4) { "SFTP 文件系统属性格式无效" }
        RemoteFileSystem(
            size = fields[0].toLongOrZero(),
            used = fields[1].toLongOrZero(),
            available = fields[2].toLongOrZero(),
            capacityPercent = fields[3].toIntOrZero().coerceIn(0, 100),
        )
    }

    override suspend fun mkdir(path: String) = ioUnit {
        command(CMD_MKDIR, normalizeRemotePath(path), value = 0x1ED)
    }

    override suspend fun rename(source: String, target: String) = ioUnit {
        command(
            CMD_RENAME,
            normalizeRemotePath(source),
            normalizeRemotePath(target),
        )
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        ioUnit { deleteInternal(normalizeRemotePath(path), recursive) }
    }

    override suspend fun chmod(path: String, mode: Int) = ioUnit {
        command(CMD_CHMOD, normalizeRemotePath(path), value = (mode and 0xFFF).toLong())
    }

    override suspend fun chown(path: String, uid: Int) = ioUnit {
        require(uid >= 0) { "uid 不能为负数" }
        command(CMD_CHOWN, normalizeRemotePath(path), value = uid.toLong())
    }

    override suspend fun chgrp(path: String, gid: Int) = ioUnit {
        require(gid >= 0) { "gid 不能为负数" }
        command(CMD_CHGRP, normalizeRemotePath(path), value = gid.toLong())
    }

    override suspend fun symlink(target: String, linkPath: String) = ioUnit {
        command(CMD_SYMLINK, target, normalizeRemotePath(linkPath))
    }

    override suspend fun readlink(path: String): String = io {
        command(CMD_READLINK, normalizeRemotePath(path))
    }

    override suspend fun download(
        path: String,
        output: OutputStream,
        offset: Long,
        progress: (Long) -> Boolean,
    ) = ioUnit {
        require(offset >= 0) { "offset 不能为负数" }
        val opened = openFile(normalizeRemotePath(path), offset, write = false, truncate = false)
        var transferred = 0L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val data = nativeCall { runtime.runSftpRead(opened.handle, TRANSFER_CHUNK_SIZE) }
                if (data.isEmpty()) break
                output.write(data)
                transferred += data.size
                if (!progress(transferred)) throw CancellationException("SFTP 下载已取消")
            }
        } finally {
            closeHandleNow(opened.handle)
        }
    }

    override suspend fun upload(
        input: InputStream,
        path: String,
        offset: Long,
        progress: (Long) -> Boolean,
    ) = ioUnit {
        require(offset >= 0) { "offset 不能为负数" }
        val opened = openFile(
            normalizeRemotePath(path),
            offset,
            write = true,
            truncate = offset == 0L,
        )
        var transferred = 0L
        val buffer = ByteArray(TRANSFER_CHUNK_SIZE)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val data = if (count == buffer.size) buffer else buffer.copyOf(count)
                val written = nativeCall { runtime.runSftpWrite(opened.handle, data) }
                check(written == count) { "SFTP 写入长度不完整" }
                transferred += written
                if (!progress(transferred)) throw CancellationException("SFTP 上传已取消")
            }
        } finally {
            closeHandleNow(opened.handle)
        }
    }

    override suspend fun openRead(path: String, offset: Long): RemoteRead = io {
        require(offset >= 0) { "offset 不能为负数" }
        val opened = openFile(normalizeRemotePath(path), offset, write = false, truncate = false)
        RemoteRead(opened.size, NativeReadStream(opened.handle))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onClose(this)
        val handles = openHandles.toList()
        openHandles.clear()
        closeScope.launch {
            handles.forEach { handle -> runCatching { runtime.runSftpClose(handle) } }
            runCatching { runtime.closeSftpClient(clientHandle) }
            closeScope.cancel()
        }
    }

    private fun command(command: Int, path: String, target: String = "", value: Long = 0): String =
        nativeCall { runtime.runSftpCommand(clientHandle, command, path, target, value) }

    private fun deleteInternal(path: String, recursive: Boolean) {
        val file = statBlocking(path, followLinks = false)
        if (file.type != RemoteFileType.DIRECTORY || file.type == RemoteFileType.SYMLINK) {
            command(CMD_UNLINK, path)
            return
        }
        if (recursive) {
            listBlocking(path).forEach { child -> deleteInternal(child.path, recursive = true) }
        }
        command(CMD_RMDIR, path)
    }

    private fun statBlocking(path: String, followLinks: Boolean): RemoteFile {
        val fields = command(CMD_STAT, path, value = if (followLinks) 1 else 0).split('\t')
        check(fields.size == 6) { "SFTP 文件属性格式无效" }
        return RemoteFile(
            path = path,
            name = path.substringAfterLast('/').ifEmpty { "/" },
            type = fields[0].toRemoteFileType(),
            size = fields[1].toLongOrZero(),
            modifiedAt = fields[2].toLongOrZero() * 1_000L,
            permissions = fields[3].toIntOrZero(),
            uid = fields[4].toIntOrZero(),
            gid = fields[5].toIntOrZero(),
        )
    }

    private fun listBlocking(path: String): List<RemoteFile> =
        command(CMD_LIST, path).lineSequence().filter(String::isNotBlank).map { line ->
            val fields = line.split('\t')
            check(fields.size == 7) { "SFTP 目录条目格式无效" }
            val name = decodeNativeSftpField(fields[0])
            RemoteFile(
                path = joinRemotePath(path, name),
                name = name,
                type = fields[1].toRemoteFileType(),
                size = fields[2].toLongOrZero(),
                modifiedAt = fields[3].toLongOrZero() * 1_000L,
                permissions = fields[4].toIntOrZero(),
                uid = fields[5].toIntOrZero(),
                gid = fields[6].toIntOrZero(),
            )
        }.toList()

    private fun openFile(
        path: String,
        offset: Long,
        write: Boolean,
        truncate: Boolean,
    ): NativeSftpOpenFile {
        val opened = parseNativeSftpOpenPayload(nativeCall {
            runtime.runSftpOpen(clientHandle, path, offset, write, truncate)
        })
        openHandles += opened.handle
        if (closed.get() && openHandles.remove(opened.handle)) {
            closeScope.launch { runCatching { runtime.runSftpClose(opened.handle) } }
            error("SFTP 客户端已关闭")
        }
        return opened
    }

    private fun closeHandleNow(handle: Long) {
        if (!openHandles.remove(handle)) return
        runCatching { runtime.runSftpClose(handle) }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed.get()) { "SFTP 客户端已关闭" }
            block()
        }
    }

    private suspend fun ioUnit(block: suspend () -> Unit): Unit = io(block)

    private fun <T> nativeCall(block: () -> T): T =
        runCatching(block).getOrElse { throw it.asNativeSftpFailure() }

    private inner class NativeReadStream(private val handle: Long) : InputStream() {
        private val streamClosed = AtomicBoolean(false)
        private val readLock = Any()

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
                "无效的读取区间"
            }
            if (length == 0) return 0
            check(!streamClosed.get()) { "流已关闭" }
            synchronized(readLock) {
                check(!streamClosed.get()) { "流已关闭" }
                val data = nativeCall {
                    runtime.runSftpRead(handle, length.coerceAtMost(TRANSFER_CHUNK_SIZE))
                }
                if (data.isEmpty()) return -1
                data.copyInto(buffer, offset)
                return data.size
            }
        }

        override fun close() {
            if (!streamClosed.compareAndSet(false, true)) return
            if (!openHandles.remove(handle)) return
            closeScope.launch { runCatching { runtime.runSftpClose(handle) } }
        }
    }

    private companion object {
        const val CMD_LIST = 0
        const val CMD_REALPATH = 1
        const val CMD_STAT = 2
        const val CMD_MKDIR = 3
        const val CMD_RENAME = 4
        const val CMD_UNLINK = 5
        const val CMD_RMDIR = 6
        const val CMD_CHMOD = 7
        const val CMD_CHOWN = 8
        const val CMD_CHGRP = 9
        const val CMD_SYMLINK = 10
        const val CMD_READLINK = 11
        const val CMD_STATVFS = 12
        const val TRANSFER_CHUNK_SIZE = 256 * 1024
    }
}

private fun String.toRemoteFileType(): RemoteFileType = when (firstOrNull()) {
    'F' -> RemoteFileType.FILE
    'D' -> RemoteFileType.DIRECTORY
    'L' -> RemoteFileType.SYMLINK
    else -> RemoteFileType.OTHER
}

private fun String.toLongOrZero(): Long = toLongOrNull() ?: 0L
private fun String.toIntOrZero(): Int = toIntOrNull() ?: 0

internal data class NativeSftpOpenFile(val handle: Long, val size: Long)

internal fun parseNativeSftpOpenPayload(raw: String): NativeSftpOpenFile {
    val fields = raw.lineSequence().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
    }.toMap()
    return NativeSftpOpenFile(
        handle = fields["handle"]?.toLongOrNull()
            ?: error("SFTP 打开文件未返回有效句柄"),
        size = fields["size"]?.toLongOrNull() ?: 0L,
    )
}

internal fun decodeNativeSftpField(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (decoded != null) {
                append(decoded.toChar())
                index += 3
                continue
            }
        }
        append(value[index++])
    }
}

private fun Throwable.asNativeSftpFailure(): Throwable {
    if (this is CancellationException) return this
    val raw = message.orEmpty()
    val prefix = when {
        raw.startsWith("sftp_no_such_file:") -> "文件或目录不存在"
        raw.startsWith("sftp_permission_denied:") -> "权限不足"
        raw.startsWith("sftp_connection_lost:") -> "SFTP 连接已断开"
        raw.startsWith("sftp_unsupported:") -> "服务器不支持此操作"
        raw.startsWith("sftp_already_exists:") -> "文件或目录已存在"
        raw.startsWith("sftp_no_space:") -> "远端存储空间不足"
        raw.startsWith("sftp_quota_exceeded:") -> "远端存储配额已用尽"
        raw.startsWith("sftp_directory_not_empty:") -> "目录不为空"
        raw.startsWith("sftp_not_a_directory:") -> "目标不是目录"
        raw.startsWith("sftp_invalid_filename:") -> "文件名无效"
        raw.startsWith("sftp_invalid_handle:") -> "SFTP 文件句柄已关闭"
        raw.startsWith("sftp_client_closed:") -> "SFTP 客户端已关闭"
        else -> "SFTP 操作失败"
    }
    return IllegalStateException("$prefix${raw.substringAfter(':', "").let { if (it.isBlank()) "" else "：$it" }}", this)
}
