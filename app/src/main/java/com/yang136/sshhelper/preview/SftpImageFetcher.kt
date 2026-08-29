package com.yang136.sshhelper.preview

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.SessionManager
import okio.FileSystem
import okio.buffer
import okio.source

/**
 * Model for streaming a remote image over SFTP. The [size] of the file is part of the
 * identity so Coil's memory cache cannot serve a stale frame after the file changed.
 */
data class SftpImage(
    val path: String,
    val hostName: String,
    val size: Long,
)

/**
 * Coil 3 [Fetcher] that streams a remote image over SFTP into Coil's pipeline, so
 * downsampling, EXIF orientation and memory caching are handled by Coil. Each fetch opens
 * one dedicated SFTP channel and closes it when the source is closed (including cancels).
 */
class SftpImageFetcher(
    private val sessionManager: SessionManager,
    private val sessionId: SessionId,
    private val data: SftpImage,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val client = sessionManager.newSftpClient(sessionId)
        try {
            val remote = client.openRead(data.path, 0)
            // Closing the okio source (Coil always closes what it opens) must also return
            // the dedicated SFTP channel; the pipe stream aborts the transfer on close.
            val input = object : java.io.FilterInputStream(remote.stream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
            val imageSource = ImageSource(
                input.source().buffer(),
                FileSystem.SYSTEM,
            )
            return SourceFetchResult(imageSource, imageMimeType(data.path), DataSource.NETWORK)
        } catch (error: Throwable) {
            runCatching { client.close() }
            throw error
        }
    }

    class Factory(
        private val sessionManager: SessionManager,
        private val sessionId: SessionId,
    ) : Fetcher.Factory<SftpImage> {
        override fun create(data: SftpImage, options: Options, imageLoader: ImageLoader): Fetcher =
            SftpImageFetcher(sessionManager, sessionId, data)
    }
}

private fun imageMimeType(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "heic", "heif" -> "image/heic"
    "avif" -> "image/avif"
    else -> null
}
