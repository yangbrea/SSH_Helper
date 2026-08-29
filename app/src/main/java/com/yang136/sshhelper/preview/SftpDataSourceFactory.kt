package com.yang136.sshhelper.preview

import androidx.media3.datasource.DataSource
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.SessionManager
import kotlinx.coroutines.runBlocking

/**
 * Creates [SftpDataSource]s that stream [path] over the session's SSH connection.
 * Stateless on purpose: each data source opens and closes its own dedicated SFTP channel
 * per read session, so seeks and cache-driven reopens never share a channel with a
 * concurrent read (which would deadlock JSch's ChannelSftp).
 */
class SftpDataSourceFactory(
    private val sessionManager: SessionManager,
    private val sessionId: SessionId,
    private val path: String,
    private val displayHost: String,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = SftpDataSource(
        clientFactory = { runBlocking { sessionManager.newSftpClient(sessionId) } },
        path = path,
        displayHost = displayHost,
    )

    /** Kept for API compatibility; channels are owned by the data sources themselves. */
    fun release() = Unit
}
