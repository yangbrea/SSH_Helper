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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmbeddedSftpIntegrationTest {
    private lateinit var server: SshServer
    private lateinit var root: java.nio.file.Path
    private lateinit var client: JschSftpClient

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
        val session = JSch().getSession("test", "127.0.0.1", server.port).apply {
            setPassword("secret")
            setConfig("StrictHostKeyChecking", "no")
            connect(5_000)
        }
        client = JschSftpClient((session.openChannel("sftp") as ChannelSftp).apply { connect(5_000) })
    }

    @After fun stopServer() {
        runCatching { client.close() }
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
}
