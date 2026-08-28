package com.yang136.sshhelper.documents

import android.content.pm.ProviderInfo
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.sftp.RemoteFile
import com.yang136.sshhelper.sftp.RemoteFileType
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshDocumentsProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fake: FakeDocumentsBackend
    private lateinit var provider: SshDocumentsProvider

    @Before
    fun setUp() {
        fake = FakeDocumentsBackend(File(context.cacheDir, "provider-test").apply { mkdirs() })
        SshDocumentsProvider.backendOverride = fake
        provider = SshDocumentsProvider().also {
            it.attachInfo(context, ProviderInfo().apply {
                authority = "${context.packageName}.documents"
                exported = true
                grantUriPermissions = true
            })
        }
    }

    @After
    fun tearDown() {
        SshDocumentsProvider.backendOverride = null
        fake.directory.deleteRecursively()
    }

    @Test
    fun rootsDisappearWhileLockedAndReturnAfterUnlock() {
        provider.queryRoots(null).use { assertEquals(1, it.count) }
        fake.unlocked = false
        provider.queryRoots(null).use { assertEquals(0, it.count) }
        fake.unlocked = true
        provider.queryRoots(null).use { assertEquals(1, it.count) }
    }

    @Test
    fun traversesCreatesRenamesAndRecursivelyDeletes() {
        val root = DocumentIdCodec.root(1)
        provider.queryChildDocuments(root, null, null as String?).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("hello.txt", cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
            assertEquals("text/plain", cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)))
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS))
            assertTrue(flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0)
            assertTrue(flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0)
            assertTrue(flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0)
        }
        val directory = provider.createDocument(root, DocumentsContract.Document.MIME_TYPE_DIR, "work")
        val file = provider.createDocument(directory, "text/plain", "note.txt")
        val duplicate = provider.createDocument(directory, "text/plain", "note.txt")
        assertEquals("note (1).txt", DocumentIdCodec.decode(duplicate).path?.substringAfterLast('/'))
        val renamed = provider.renameDocument(file, "renamed.txt")
        assertTrue(provider.isChildDocument(directory, renamed))
        provider.deleteDocument(directory)
        assertFalse(fake.nodes.keys.any { it.startsWith("/home/user/work") })
    }

    @Test
    fun proxyDescriptorSupportsRandomReadWriteAndCommitsOnClose() {
        val id = DocumentIdCodec.encode(DocumentId(1, "/home/user/hello.txt"))
        val descriptor = provider.openDocument(id, "rw", null)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write("world".encodeToByteArray()) }
        assertTrue(fake.committed.await(5, TimeUnit.SECONDS))
        assertEquals("world", fake.nodes.getValue("/home/user/hello.txt").bytes.decodeToString())

        val read = provider.openDocument(id, "r", null)
        val content = ParcelFileDescriptor.AutoCloseInputStream(read).use { it.readBytes().decodeToString() }
        assertEquals("world", content)
    }

    @Test(expected = FileNotFoundException::class)
    fun cancellationIsMappedToProviderError() {
        provider.openDocument(
            DocumentIdCodec.encode(DocumentId(1, "/home/user/hello.txt")),
            "r",
            CancellationSignal().apply { cancel() },
        )
    }
}

private class FakeDocumentsBackend(val directory: File) : DocumentsBackend {
    data class Node(val directory: Boolean, var bytes: ByteArray = ByteArray(0), var modifiedAt: Long = 1)

    var unlocked = true
    val committed = CountDownLatch(1)
    val nodes = linkedMapOf(
        "/home/user" to Node(true),
        "/home/user/hello.txt" to Node(false, "hello".encodeToByteArray()),
    )
    private val host = HostProfile(1, "测试主机", "example.test", 22, "user", AuthType.PASSWORD, true)

    override fun isDeviceUnlocked(): Boolean = unlocked
    override suspend fun roots(): List<DocumentRootInfo> = if (unlocked) listOf(DocumentRootInfo(host)) else emptyList()
    override suspend fun home(hostId: Long): String = "/home/user"
    override suspend fun stat(hostId: Long, path: String?): RemoteFile = remote(path ?: "/home/user")
    override suspend fun children(hostId: Long, path: String?): List<RemoteFile> {
        val parent = (path ?: "/home/user").trimEnd('/')
        return nodes.keys.filter { it != parent && it.substringBeforeLast('/') == parent }.map(::remote)
    }

    override suspend fun create(hostId: Long, parentPath: String?, name: String, directory: Boolean): RemoteFile {
        val parent = (parentPath ?: "/home/user").trimEnd('/')
        var path = "$parent/$name"
        var index = 1
        while (path in nodes) path = "$parent/${name.substringBeforeLast('.', name)} (${index++})${name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }}"
        nodes[path] = Node(directory)
        return remote(path)
    }

    override suspend fun rename(hostId: Long, path: String, newName: String): RemoteFile {
        val replacement = "${path.substringBeforeLast('/')}/$newName"
        val affected = nodes.filterKeys { it == path || it.startsWith("$path/") }.toList()
        affected.forEach { (old, node) ->
            nodes.remove(old)
            nodes[replacement + old.removePrefix(path)] = node
        }
        return remote(replacement)
    }

    override suspend fun delete(hostId: Long, path: String) {
        nodes.keys.filter { it == path || it.startsWith("$path/") }.toList().forEach(nodes::remove)
    }

    override suspend fun isChild(hostId: Long, parentPath: String?, childPath: String): Boolean {
        val parent = (parentPath ?: "/home/user").trimEnd('/')
        return childPath.startsWith("$parent/")
    }

    override suspend fun prepareOpen(hostId: Long, path: String, download: Boolean): OpenedDocument {
        val node = nodes.getValue(path)
        val cache = File.createTempFile("provider-", ".cache", directory)
        if (download) cache.writeBytes(node.bytes)
        return OpenedDocument(hostId, remote(path), cache, node.bytes.size.toLong(), node.modifiedAt)
    }

    override suspend fun commit(opened: OpenedDocument) {
        nodes.getValue(opened.remote.path).apply {
            bytes = opened.cache.readBytes()
            modifiedAt++
        }
        committed.countDown()
    }

    override suspend fun release(opened: OpenedDocument, keepRecovery: Boolean) {
        opened.cache.delete()
    }

    override fun close(hostId: Long?) = Unit

    private fun remote(path: String): RemoteFile {
        val node = nodes.getValue(path)
        return RemoteFile(
            path = path,
            name = if (path == "/home/user") "user" else path.substringAfterLast('/'),
            type = if (node.directory) RemoteFileType.DIRECTORY else RemoteFileType.FILE,
            size = node.bytes.size.toLong(),
            modifiedAt = node.modifiedAt,
            permissions = if (node.directory) 0x1ED else 0x1A4,
            uid = 1000,
            gid = 1000,
        )
    }
}
