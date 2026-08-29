package com.yang136.sshhelper.preview

import androidx.media3.datasource.DataSource
import com.yang136.sshhelper.sftp.SftpClient
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.SessionManager
import kotlinx.coroutines.runBlocking

/**
 * Creates Media3 data sources that stream [path] over one dedicated SFTP channel per
 * playback session. [release] must be called when playback ends so the channel is closed
 * and the session slot is freed; without it the channel would leak until the SSH session
 * itself is torn down.
 */
class SftpDataSourceFactory(
    private val sessionManager: SessionManager,
    private val sessionId: SessionId,
    private val path: String,
    private val displayHost: String,
) : DataSource.Factory {

    private var client: SftpClient? = null

    override fun createDataSource(): DataSource {
        client?.close()
        val created = runBlocking { sessionManager.newSftpClient(sessionId) }
        client = created
        return SftpDataSource(created, path, displayHost)
    }

    fun release() {
        client?.close()
        client = null
    }
}
