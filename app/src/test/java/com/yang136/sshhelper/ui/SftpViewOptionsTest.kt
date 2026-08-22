package com.yang136.sshhelper.ui

import com.yang136.sshhelper.sftp.RemoteFile
import com.yang136.sshhelper.sftp.RemoteFileType
import org.junit.Assert.assertEquals
import org.junit.Test

class SftpViewOptionsTest {
    private val files = listOf(
        remote("/visible.txt", "visible.txt", 20),
        remote("/.hidden", ".hidden", 10),
        remote("/folder", "folder", 0, RemoteFileType.DIRECTORY),
    )

    @Test
    fun remoteFiltering_usesOnlyRemoteOptions() {
        val state = SftpUiState(
            remoteView = FileViewOptions(query = "visible"),
            localView = FileViewOptions(query = "does-not-match", showHidden = true),
        )

        assertEquals(listOf("visible.txt"), files.filteredRemote(state).map(RemoteFile::name))
    }

    @Test
    fun remoteSortAndHiddenOptions_areAppliedIndependently() {
        val state = SftpUiState(
            remoteView = FileViewOptions(showHidden = true, sort = FileSort.SIZE, descending = true),
        )

        assertEquals(listOf("folder", "visible.txt", ".hidden"), files.filteredRemote(state).map(RemoteFile::name))
    }

    private fun remote(path: String, name: String, size: Long, type: RemoteFileType = RemoteFileType.FILE) = RemoteFile(
        path = path,
        name = name,
        type = type,
        size = size,
        modifiedAt = 0,
        permissions = 0,
        uid = 0,
        gid = 0,
    )
}
