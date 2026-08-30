package com.yang136.sshhelper.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SftpSearchTest {
    private lateinit var server: SshServer
    private lateinit var root: java.nio.file.Path
    private lateinit var client: JschSftpClient

    @Before fun startServer() {
        root = Files.createTempDirectory("ssh-helper-sftp-search")
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
            passwordAuthenticator = PasswordAuthenticator { username, password, _ -> username == "test" && password == "secret" }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
            start()
        }
        val jschSession = JSch().getSession("test", "127.0.0.1", server.port).apply {
            setPassword("secret")
            setConfig("StrictHostKeyChecking", "no")
            connect(5_000)
        }
        client = JschSftpClient((jschSession.openChannel("sftp") as ChannelSftp).apply { connect(5_000) })
    }

    @After fun stopServer() {
        runCatching { client.close() }
        runCatching { server.stop(true) }
        root.toFile().deleteRecursively()
    }

    private fun write(relative: String) {
        val target = root.resolve(relative.trimStart('/'))
        Files.createDirectories(target.parent)
        Files.write(target, relative.encodeToByteArray())
    }

    @Test
    fun recursiveSearch_findsFilesAcrossNestedDirs() = runBlocking {
        write("/notes.txt")
        write("/docs/notes.md")
        write("/docs/sub/deep.log")

        val notes = searchRemoteSftp(client, "/", "notes")
        assertEquals(setOf("/notes.txt", "/docs/notes.md"), notes.map { it.path }.toSet())
        assertEquals("/docs", notes.first { it.path == "/docs/notes.md" }.parentDir)

        val deep = searchRemoteSftp(client, "/", "deep")
        assertEquals(listOf("/docs/sub/deep.log"), deep.map { it.path })
        assertEquals(RemoteFileType.FILE, deep.single().type)
    }

    @Test
    fun recursiveSearch_skipsHiddenUnlessConfigured() = runBlocking {
        write("/.secret.txt")
        write("/docs/.hidden.log")

        assertEquals(0, searchRemoteSftp(client, "/", "secret").size)
        assertEquals(0, searchRemoteSftp(client, "/", "hidden").size)

        val included = searchRemoteSftp(client, "/", "secret", SftpSearchConfig(includeHidden = true))
        assertEquals(listOf("/.secret.txt"), included.map { it.path })
    }

    @Test
    fun recursiveSearch_doesNotFollowSymlinkDirs() = runBlocking {
        write("/a/notes.txt")
        write("/a/sub/other.txt")
        // 循环符号链接: b -> a, b/loop -> b(自环)
        Files.createSymbolicLink(root.resolve("b"), root.resolve("a"))
        Files.createSymbolicLink(root.resolve("a/loop"), root.resolve("a"))

        val hits = searchRemoteSftp(client, "/", "notes")
        assertEquals(1, hits.size)
        assertEquals("/a/notes.txt", hits.single().path)
    }

    @Test
    fun recursiveSearch_respectsMaxDepth() = runBlocking {
        write("/d1/d2/d3/d4/d5/deep.txt")

        assertEquals(0, searchRemoteSftp(client, "/", "deep", SftpSearchConfig(maxDepth = 3)).size)
        assertEquals(1, searchRemoteSftp(client, "/", "deep", SftpSearchConfig(maxDepth = 6)).size)
    }

    @Test
    fun recursiveSearch_respectsMaxResults() = runBlocking {
        repeat(20) { write("/dir$it/match-$it.txt") }

        val capped = searchRemoteSftp(client, "/", "match", SftpSearchConfig(maxResults = 5))
        assertEquals(5, capped.size)

        val all = searchRemoteSftp(client, "/", "match")
        assertEquals(20, all.size)
    }

    @Test
    fun recursiveSearch_blankQueryReturnsEmpty() = runBlocking {
        write("/notes.txt")
        assertEquals(0, searchRemoteSftp(client, "/", "   ").size)
        assertTrue(searchRemoteSftp(client, "/", "").isEmpty())
    }

    @Test
    fun recursiveSearch_directoriesMatchAndAreNotDescendedWhenCapped() = runBlocking {
        // 目录名匹配也计入结果,且命中后不再下钻其内容(由 depth/结果上限约束)
        write("/match-dir/inside.txt")
        val hits = searchRemoteSftp(client, "/", "match", SftpSearchConfig(maxResults = 1))
        assertEquals(1, hits.size)
        assertEquals(RemoteFileType.DIRECTORY, hits.single().type)
    }
}
