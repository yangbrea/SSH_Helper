package com.yang136.sshhelper.documents

import com.yang136.sshhelper.sftp.RemoteFile
import com.yang136.sshhelper.sftp.RemoteFileSystem
import com.yang136.sshhelper.sftp.RemoteFileType
import com.yang136.sshhelper.sftp.SftpClient
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicWritebackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun rejectsConcurrentRemoteModificationBeforeUpload() = runTest {
        val client = FakeAtomicSftp().apply { put("/home/file.txt", "remote-new", modifiedAt = 2) }
        val local = temporary.newFile().apply { writeText("local") }
        val result = runCatching {
            client.atomicReplace(local, "/home/file.txt", "file.txt", baselineSize = 3, baselineModifiedAt = 1, token = "test")
        }
        assertTrue(result.isFailure)
        assertArrayEquals("remote-new".encodeToByteArray(), client.bytes("/home/file.txt"))
        assertFalse(client.paths().any { "sshhelper-docs" in it })
    }

    @Test
    fun failedReplacementRollsOriginalBackAndRemovesTemporary() = runTest {
        val client = FakeAtomicSftp().apply {
            put("/home/file.txt", "old", modifiedAt = 1, permissions = 0x1A0)
            failRenameSource = "/home/.file.txt.sshhelper-docs-test.part"
        }
        val local = temporary.newFile().apply { writeText("new") }
        val result = runCatching {
            client.atomicReplace(local, "/home/file.txt", "file.txt", baselineSize = 3, baselineModifiedAt = 1, token = "test")
        }
        assertTrue(result.isFailure)
        assertArrayEquals("old".encodeToByteArray(), client.bytes("/home/file.txt"))
        assertEquals(setOf("/home/file.txt"), client.paths())
    }

    @Test
    fun successfulReplacementPreservesPermissionsAndDeletesBackup() = runTest {
        val client = FakeAtomicSftp().apply { put("/home/file.txt", "old", modifiedAt = 1, permissions = 0x1A0) }
        val local = temporary.newFile().apply { writeText("updated") }
        val updated = client.atomicReplace(
            local, "/home/file.txt", "file.txt", baselineSize = 3, baselineModifiedAt = 1, token = "test",
        )
        assertArrayEquals("updated".encodeToByteArray(), client.bytes("/home/file.txt"))
        assertEquals(0x1A0, updated.permissions)
        assertEquals(setOf("/home/file.txt"), client.paths())
    }
}

private class FakeAtomicSftp : SftpClient {
    private data class Entry(var bytes: ByteArray, var modifiedAt: Long, var permissions: Int)
    private val files = linkedMapOf<String, Entry>()
    var failRenameSource: String? = null

    fun put(path: String, text: String, modifiedAt: Long, permissions: Int = 0x1A4) {
        files[path] = Entry(text.encodeToByteArray(), modifiedAt, permissions)
    }
    fun bytes(path: String): ByteArray = files.getValue(path).bytes
    fun paths(): Set<String> = files.keys

    override suspend fun stat(path: String, followLinks: Boolean): RemoteFile {
        val entry = files[path] ?: error("not found: $path")
        return RemoteFile(path, path.substringAfterLast('/'), RemoteFileType.FILE, entry.bytes.size.toLong(), entry.modifiedAt, entry.permissions, 1, 1)
    }
    override suspend fun upload(input: InputStream, path: String, offset: Long, progress: (Long) -> Boolean) {
        files[path] = Entry(input.readBytes(), 3, 0)
    }
    override suspend fun chmod(path: String, mode: Int) { files.getValue(path).permissions = mode }
    override suspend fun rename(source: String, target: String) {
        if (source == failRenameSource) error("injected rename failure")
        files[target] = files.remove(source) ?: error("not found: $source")
    }
    override suspend fun delete(path: String, recursive: Boolean) { files.remove(path) }

    override suspend fun home() = "/home"
    override suspend fun realPath(path: String) = path
    override suspend fun list(path: String) = emptyList<RemoteFile>()
    override suspend fun fileSystem(path: String) = RemoteFileSystem(0, 0, 0, 0)
    override suspend fun mkdir(path: String) = Unit
    override suspend fun chown(path: String, uid: Int) = Unit
    override suspend fun chgrp(path: String, gid: Int) = Unit
    override suspend fun symlink(target: String, linkPath: String) = Unit
    override suspend fun readlink(path: String) = error("not a link")
    override suspend fun download(path: String, output: OutputStream, offset: Long, progress: (Long) -> Boolean) {
        output.write(files.getValue(path).bytes)
    }
    override fun close() = Unit
}
