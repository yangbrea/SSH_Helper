package com.yang136.sshhelper.preview

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.yang136.sshhelper.sftp.SftpClient
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.runBlocking

/**
 * A Media3 [DataSource] that streams a remote file over SFTP. Each [open] starts a fresh
 * offset read on the caller-provided preview channel, which makes seeking cheap and keeps
 * the whole file out of memory. The [SftpClient] is owned by [SftpDataSourceFactory] and
 * must outlive this source: [close] only aborts the active byte stream.
 */
class SftpDataSource(
    private val client: SftpClient,
    private val path: String,
    private val displayHost: String,
) : DataSource {

    private var stream: InputStream? = null
    private var bytesRemaining = -1L
    private val transferListeners = CopyOnWriteArraySet<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long = open(dataSpec.position, dataSpec.length)

    /**
     * Internal seam that keeps the offset/EOF logic testable in plain JVM unit tests
     * (DataSpec itself requires a real android.net.Uri). Media3 only ever calls [open] above.
     */
    internal fun open(position: Long, length: Long): Long {
        close()
        val remote = try {
            runBlocking { client.openRead(path, position) }
        } catch (error: Throwable) {
            throw IOException("无法从 SFTP 读取远程文件：${error.message}", error)
        }
        stream = remote.stream
        bytesRemaining = (remote.size - position).coerceAtLeast(0L)
        if (length != C.LENGTH_UNSET.toLong()) {
            bytesRemaining = bytesRemaining.coerceAtMost(length)
        }
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val current = stream ?: throw IOException("SftpDataSource 未打开")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining > 0) length.toLong().coerceAtMost(bytesRemaining).toInt() else length
        val read = try {
            current.read(buffer, offset, toRead)
        } catch (error: Throwable) {
            throw IOException("SFTP 读取中断：${error.message}", error)
        }
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining > 0) bytesRemaining -= read
        return read
    }

    override fun getUri(): Uri = Uri.parse("sftp://$displayHost$path")

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }
}
