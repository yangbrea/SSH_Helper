package com.yang136.sshhelper.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

class EmbeddedSftpIntegrationTest {
    private lateinit var server: SshServer
    private lateinit var root: java.nio.file.Path
    private lateinit var client: JschSftpClient
    private lateinit var jschSession: com.jcraft.jsch.Session

    @Before fun startServer() {
        root = Files.createTempDirectory("ssh-helper-sftp-test")
        Files.write(root.resolve("中文.txt"), "你好，SFTP".encodeToByteArray())
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
            passwordAuthenticator = PasswordAuthenticator { username, password, _ -> username == "test" && password == "secret" }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
            start()
        }
        jschSession = JSch().getSession("test", "127.0.0.1", server.port).apply {
            setPassword("secret")
            setConfig("StrictHostKeyChecking", "no")
            connect(5_000)
        }
        client = JschSftpClient((jschSession.openChannel("sftp") as ChannelSftp).apply { connect(5_000) })
    }

    @After fun stopServer() {
        runCatching { client.close() }
        runCatching { jschSession.disconnect() }
        runCatching { server.stop(true) }
        root.toFile().deleteRecursively()
    }

    @Test fun listsUnicodeAndPerformsFileLifecycle() = runBlocking {
        assertTrue(client.list(".").any { it.name == "中文.txt" && it.size > 0 })

        val payload = "uploaded-data".encodeToByteArray()
        client.mkdir("目录")
        client.upload(ByteArrayInputStream(payload), "目录/source.bin")
        assertEquals(payload.size.toLong(), client.stat("目录/source.bin").size)

        client.rename("目录/source.bin", "目录/renamed.bin")
        val downloaded = ByteArrayOutputStream()
        client.download("目录/renamed.bin", downloaded)
        assertArrayEquals(payload, downloaded.toByteArray())

        client.delete("目录", recursive = true)
        assertTrue(runCatching { client.stat("目录") }.isFailure)
    }

    @Test
    fun openRead_readsWholeFileFromZeroOffset() = runBlocking {
        val payload = "0123456789".repeat(2048).encodeToByteArray()
        client.upload(ByteArrayInputStream(payload), "stream.bin")

        val read = client.openRead("stream.bin", 0)
        assertEquals(payload.size.toLong(), read.size)
        read.stream.use { assertArrayEquals(payload, it.readBytes()) }
    }

    @Test
    fun openRead_skipsToOffset() = runBlocking {
        val payload = "0123456789".repeat(2048).encodeToByteArray()
        client.upload(ByteArrayInputStream(payload), "stream.bin")

        val offset = 5000L
        client.openRead("stream.bin", offset).stream.use {
            assertArrayEquals(payload.copyOfRange(offset.toInt(), payload.size), it.readBytes())
        }
    }

    @Test
    fun openRead_closeAbortsTransferAndChannelSurvives() = runBlocking {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        client.upload(ByteArrayInputStream(payload), "big.bin")

        val read = client.openRead("big.bin", 0)
        val first = ByteArray(4096)
        assertEquals(4096, read.stream.read(first))
        read.stream.close()

        // Reading a closed stream must fail fast instead of blocking; the abort can surface
        // as either the pipe IOException or the recorded SFTP failure depending on timing.
        val elapsed = measureTimeMillis {
            val error = runCatching { read.stream.read() }.exceptionOrNull()
            assertTrue("关闭后读取应抛异常而非阻塞", error != null)
        }
        assertTrue("关闭后读取应在 1s 内返回，实际 ${elapsed}ms", elapsed < 1_000)
        // The aborted channel is deliberately discarded (never reused). The SSH session must
        // survive: opening a fresh channel proves the seek/abort did not kill the connection.
        val fresh = JschSftpClient((jschSession.openChannel("sftp") as ChannelSftp).apply { connect(5_000) })
        try {
            assertTrue(fresh.list(".").any { it.name == "big.bin" })
        } finally {
            fresh.close()
        }
    }

    @Test
    fun openRead_missingFileFailsEarly() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { client.openRead("nope.bin", 0) }
        }
    }
}
