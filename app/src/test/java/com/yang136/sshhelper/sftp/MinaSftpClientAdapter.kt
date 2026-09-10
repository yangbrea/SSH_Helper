package com.yang136.sshhelper.sftp

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.client.SftpClient as MinaSftpClient

/**
 * Test-only [SftpClient] backed by Apache MINA's SFTP client.
 *
 * The JVM tests that exercise production code (`searchRemoteSftp`, `SftpDataSource`)
 * need *a* concrete client to drive a real SFTP server, and the embedded server they
 * already spin up is Apache MINA SSHD. Driving it with the SFTP client from the same
 * library avoids keeping JSch around purely as test plumbing once the JSch backend is
 * gone.
 *
 * This lives in `src/test` and is not part of the app. Semantics deliberately mirror
 * the removed `JschSftpClient` so the tests keep asserting the same behaviour:
 * paths go through [normalizeRemotePath], listing skips `.`/`..`, deletion removes a
 * symlink rather than the directory it points at, and a closed read stream fails fast
 * instead of blocking.
 */
class MinaSftpClientAdapter(
    private val client: MinaSftpClient,
    private val session: ClientSession? = null,
    private val onClose: () -> Unit = {},
) : SftpClient {

    /**
     * Opens an additional SFTP channel on the same SSH session.
     *
     * `EmbeddedSftpIntegrationTest` relies on this: it aborts a transfer, discards the
     * aborted channel and proves the *session* survived by opening a fresh channel on it.
     * A whole new connection would not prove that.
     */
    fun openSiblingChannel(): MinaSftpClientAdapter {
        val active = session ?: throw IllegalStateException("该适配器未持有可复用的 SSH 会话")
        return MinaSftpClientAdapter(SftpClientFactory.instance().createSftpClient(active))
    }

    override suspend fun home(): String = io { client.canonicalPath(".") }

    override suspend fun realPath(path: String): String = io { client.canonicalPath(normalizeRemotePath(path)) }

    override suspend fun list(path: String): List<RemoteFile> = io {
        val base = normalizeRemotePath(path)
        client.openDir(base).use { handle ->
            client.readDir(handle)
                .asSequence()
                .filterNot { it.filename == "." || it.filename == ".." }
                .map { entry ->
                    val childPath = joinRemotePath(base, entry.filename)
                    entry.attributes.toRemote(
                        path = childPath,
                        name = entry.filename,
                        target = if (entry.attributes.isSymbolicLink) {
                            runCatching { client.readLink(childPath) }.getOrNull()
                        } else {
                            null
                        },
                    )
                }
                .toList()
        }
    }

    override suspend fun stat(path: String, followLinks: Boolean): RemoteFile = io {
        val normalized = normalizeRemotePath(path)
        val attributes = if (followLinks) client.stat(normalized) else client.lstat(normalized)
        attributes.toRemote(
            path = normalized,
            name = normalized.substringAfterLast('/').ifEmpty { "/" },
            target = if (attributes.isSymbolicLink) {
                runCatching { client.readLink(normalized) }.getOrNull()
            } else {
                null
            },
        )
    }

    /**
     * MINA's SFTP client exposes no `statvfs` extension, so this cannot be answered the
     * way the JSch client did. Nothing under test calls it: the only production caller
     * (`SftpViewModel`) already wraps it in `runCatching` and degrades to no usage bar.
     */
    override suspend fun fileSystem(path: String): RemoteFileSystem =
        throw UnsupportedOperationException("MinaSftpClientAdapter 不提供 statvfs")

    override suspend fun mkdir(path: String) = ioUnit { client.mkdir(normalizeRemotePath(path)) }

    override suspend fun rename(source: String, target: String) = ioUnit {
        client.rename(normalizeRemotePath(source), normalizeRemotePath(target))
    }

    override suspend fun delete(path: String, recursive: Boolean) = ioUnit {
        deleteInternal(normalizeRemotePath(path), recursive)
    }

    override suspend fun chmod(path: String, mode: Int) = ioUnit {
        client.setStat(normalizeRemotePath(path), MinaSftpClient.Attributes().perms(mode and 0xFFF))
    }

    override suspend fun chown(path: String, uid: Int) = ioUnit {
        // Attributes exposes owner(uid, gid) as a pair only, so the untouched half is
        // carried over from the current attributes.
        val normalized = normalizeRemotePath(path)
        client.setStat(normalized, MinaSftpClient.Attributes().owner(uid, client.stat(normalized).groupId))
    }

    override suspend fun chgrp(path: String, gid: Int) = ioUnit {
        val normalized = normalizeRemotePath(path)
        client.setStat(normalized, MinaSftpClient.Attributes().owner(client.stat(normalized).userId, gid))
    }

    override suspend fun symlink(target: String, linkPath: String) = ioUnit {
        client.symLink(normalizeRemotePath(linkPath), target)
    }

    override suspend fun readlink(path: String): String = io { client.readLink(normalizeRemotePath(path)) }

    override suspend fun download(
        path: String,
        output: OutputStream,
        offset: Long,
        progress: (Long) -> Boolean,
    ) = ioUnit {
        val handle = client.open(normalizeRemotePath(path), MinaSftpClient.OpenMode.Read)
        try {
            var position = offset.coerceAtLeast(0)
            var transferred = 0L
            val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
            while (true) {
                val read = client.read(handle, position, buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                position += read
                transferred += read
                if (!progress(transferred)) break
            }
            output.flush()
        } finally {
            runCatching { client.close(handle) }
        }
    }

    override suspend fun upload(
        input: InputStream,
        path: String,
        offset: Long,
        progress: (Long) -> Boolean,
    ) = ioUnit {
        val modes = if (offset > 0) {
            listOf(MinaSftpClient.OpenMode.Write, MinaSftpClient.OpenMode.Create)
        } else {
            listOf(MinaSftpClient.OpenMode.Write, MinaSftpClient.OpenMode.Create, MinaSftpClient.OpenMode.Truncate)
        }
        val handle = client.open(normalizeRemotePath(path), modes)
        try {
            var position = offset.coerceAtLeast(0)
            var transferred = 0L
            val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                client.write(handle, position, buffer, 0, read)
                position += read
                transferred += read
                if (!progress(transferred)) break
            }
        } finally {
            runCatching { client.close(handle) }
        }
    }

    override suspend fun openRead(path: String, offset: Long): RemoteRead {
        val normalized = normalizeRemotePath(path)
        // Stat first so the caller gets the full size and a real error for a missing file,
        // matching what the JSch client reported before any bytes moved.
        val size = stat(normalized).size
        return io { RemoteRead(size, MinaRemoteStream(client, normalized, offset.coerceAtLeast(0))) }
    }

    override fun close() {
        runCatching { client.close() }
        runCatching { onClose() }
    }

    private fun deleteInternal(path: String, recursive: Boolean) {
        val attributes = client.lstat(path)
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            client.remove(path)
            return
        }
        if (!recursive) {
            client.rmdir(path)
            return
        }
        client.openDir(path).use { handle ->
            client.readDir(handle)
                .filterNot { it.filename == "." || it.filename == ".." }
                .forEach { child -> deleteInternal(joinRemotePath(path, child.filename), recursive = true) }
        }
        client.rmdir(path)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        runCatching(block).getOrElse { throw it.asSftpFailure() }
    }

    private suspend fun ioUnit(block: () -> Unit): Unit = io(block)
}

/**
 * A sequential read of a remote file. Every call is a synchronous round-trip on
 * [Dispatchers.IO]; once closed, further reads fail immediately rather than blocking,
 * which is what `SftpDataSource` asserts after Media3 closes and re-seeks.
 */
private class MinaRemoteStream(
    private val client: MinaSftpClient,
    path: String,
    offset: Long,
) : InputStream() {
    private val handle = client.open(path, MinaSftpClient.OpenMode.Read)
    private val closed = AtomicBoolean(false)
    private var position = offset

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed.get()) throw IOException("SFTP 读取已关闭")
        if (length == 0) return 0
        val read = client.read(handle, position, buffer, offset, length)
        if (read <= 0) return -1
        position += read
        return read
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { client.close(handle) }
        }
    }
}

private fun MinaSftpClient.Attributes.toRemote(path: String, name: String, target: String?): RemoteFile = RemoteFile(
    path = normalizeRemotePath(path),
    name = name,
    type = when {
        isSymbolicLink -> RemoteFileType.SYMLINK
        isDirectory -> RemoteFileType.DIRECTORY
        isRegularFile -> RemoteFileType.FILE
        else -> RemoteFileType.OTHER
    },
    size = size,
    modifiedAt = modifyTime?.toMillis() ?: 0L,
    permissions = permissions and 0xFFF,
    uid = userId,
    gid = groupId,
    linkTarget = target,
)

private const val TRANSFER_BUFFER_SIZE = 64 * 1024

/**
 * Mirrors the error surface of the real implementations. Both `NativeSftpClient` and the
 * removed `JschSftpClient` translate transport failures into `IllegalStateException` with
 * a Chinese summary, and the tests assert that contract — a missing file must fail
 * `openRead` early with a meaningful error rather than leaking a raw transport exception.
 */
private fun Throwable.asSftpFailure(): Throwable {
    if (this !is IOException) return this
    val raw = message.orEmpty()
    val prefix = when {
        raw.contains("No such file", ignoreCase = true) -> "文件或目录不存在"
        raw.contains("Permission denied", ignoreCase = true) -> "权限不足"
        raw.contains("not supported", ignoreCase = true) -> "服务器不支持此操作"
        raw.contains("No space left", ignoreCase = true) -> "远端存储空间不足"
        raw.contains("directory not empty", ignoreCase = true) -> "目录不为空"
        raw.contains("Connection", ignoreCase = true) -> "SFTP 连接已断开"
        else -> "SFTP 操作失败"
    }
    val detail = raw.substringAfter(':', "").trim()
    return IllegalStateException(prefix + if (detail.isEmpty()) "" else "：$detail", this)
}

/**
 * Connects to a local SFTP server and returns an adapter owning the whole chain, so a
 * single [SftpClient.close] releases the channel, the session and the client.
 */
fun openMinaSftp(
    port: Int,
    username: String = "test",
    password: String = "secret",
    host: String = "127.0.0.1",
): MinaSftpClientAdapter {
    val sshClient = SshClient.setUpDefaultClient()
    sshClient.start()
    // `connect()` yields a future whose verify() returns the future itself, so the
    // session has to be taken off it explicitly.
    val session = sshClient.connect(username, host, port)
        .verify(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .session
    session.addPasswordIdentity(password)
    session.auth().verify(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    val sftp = SftpClientFactory.instance().createSftpClient(session)
    return MinaSftpClientAdapter(sftp, session) {
        runCatching { session.close() }
        runCatching { sshClient.stop() }
    }
}

private const val CONNECT_TIMEOUT_SECONDS = 10L
