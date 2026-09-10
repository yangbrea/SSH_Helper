package com.yang136.sshhelper.sftp

import java.io.InputStream
import java.io.OutputStream

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
