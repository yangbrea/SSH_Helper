package com.yang136.sshhelper.preview

import androidx.media3.common.C
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.yang136.sshhelper.sftp.JschSftpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking

/**
 * Validates the Media3 bridge against a real embedded SFTP server: full reads, offset
 * reads (seek), EOF signaling and abort-on-close. ExoPlayer itself is not exercised here;
 * the tests go through the internal [SftpDataSource.open] seam so no Android framework
 * (DataSpec / Uri) is needed on the JVM.
 */
class SftpDataSourceTest {
    private lateinit var server: SshServer
    private lateinit var root: java.nio.file.Path

    @Before
    fun startServer() {
        root = Files.createTempDirectory("ssh-helper-preview-test")
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
            passwordAuthenticator = PasswordAuthenticator { username, password, _ -> username == "test" && password == "secret" }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
            start()
        }
    }

    @After
    fun stopServer() {
        runCatching { server.stop(true) }
        root.toFile().deleteRecursively()
    }

    private fun newClient(): JschSftpClient {
        val session = JSch().getSession("test", "127.0.0.1", server.port).apply {
            setPassword("secret")
            setConfig("StrictHostKeyChecking", "no")
            connect(5_000)
        }
        return JschSftpClient((session.openChannel("sftp") as ChannelSftp).apply { connect(5_000) })
    }

    private fun drain(source: SftpDataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    @Test
    fun openReadsWholeFile() {
        val payload = "0123456789".repeat(2048).encodeToByteArray()
        newClient().use { client ->
            runBlocking { client.upload(ByteArrayInputStream(payload), "/stream.bin") }
            val source = SftpDataSource(client, "/stream.bin", "testhost")
            try {
                val length = source.open(0L, C.LENGTH_UNSET.toLong())
                assertEquals(payload.size.toLong(), length)
                assertArrayEquals(payload, drain(source))
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun openAtOffsetReturnsTail() {
        val payload = "0123456789".repeat(2048).encodeToByteArray()
        newClient().use { client ->
            runBlocking { client.upload(ByteArrayInputStream(payload), "/stream.bin") }
            val source = SftpDataSource(client, "/stream.bin", "testhost")
            try {
                val offset = 5000L
                val length = source.open(offset, C.LENGTH_UNSET.toLong())
                assertEquals(payload.size.toLong() - offset, length)
                assertArrayEquals(payload.copyOfRange(offset.toInt(), payload.size), drain(source))
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun closeAbortsActiveRead() {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        newClient().use { client ->
            runBlocking { client.upload(ByteArrayInputStream(payload), "/big.bin") }
            val source = SftpDataSource(client, "/big.bin", "testhost")
            try {
                source.open(0L, C.LENGTH_UNSET.toLong())
                val buffer = ByteArray(8192)
                assertEquals(8192, source.read(buffer, 0, buffer.size))
                source.close()
                // A read after close must fail fast instead of leaking a blocked thread.
                val elapsed = measureTimeMillis {
                    val error = runCatching { source.read(buffer, 0, buffer.size) }.exceptionOrNull()
                    assertTrue("关闭后读取应快速失败而非阻塞", error is java.io.IOException)
                }
                assertTrue("关闭后读取应在 1s 内返回，实际 ${elapsed}ms", elapsed < 1_000)
            } finally {
                source.close()
            }
        }
    }
}
