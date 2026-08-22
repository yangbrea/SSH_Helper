package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.data.KnownHostEntity
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JschJumpIntegrationTest {
    private lateinit var jumpServer: SshServer
    private lateinit var targetServer: SshServer
    private lateinit var session: JschSshSession
    private lateinit var root: java.nio.file.Path

    @Before
    fun startServers() {
        root = Files.createTempDirectory("ssh-helper-jump-test")
        jumpServer = sshd("secret-jump", shell = false)
        targetServer = sshd("secret-target", shell = true)
        Files.write(root.resolve("跳板可见.txt"), "hello through jump".encodeToByteArray())
        session = JschSshSession(MemoryKnownHostDao())
    }

    private fun sshd(password: String, shell: Boolean): SshServer =
        SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host-key-$password"))
            passwordAuthenticator = PasswordAuthenticator { username, pwd, _ -> username == "test" && pwd == password }
            // MINA sshd defaults to RejectAllForwardingFilter for direct-tcpip; the jump server
            // must accept the tunnel channel that carries the target SSH handshake.
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            if (shell) {
                shellFactory = ProcessShellFactory("/bin/sh -i", listOf("/bin/sh", "-i"))
                subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            }
            fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(root)
            start()
        }

    @After
    fun stopServers() {
        runCatching { session.close() }
        runCatching { jumpServer.stop(true) }
        runCatching { targetServer.stop(true) }
        root.toFile().deleteRecursively()
    }

    private fun profile(name: String, port: Int) = HostProfile(
        name = name,
        hostname = "127.0.0.1",
        port = port,
        username = "test",
        authType = AuthType.PASSWORD,
    )

    @Test
    fun connectsThroughJumpWithIndependentHostKeyChecksAndSftpWorks() = runBlocking {
        val jump = profile("跳板机", jumpServer.port)
        val target = profile("目标机", targetServer.port)
        val connection = async {
            session.connect(
                SshRoute(target, jump),
                RouteCredentials(Credential.Password("secret-target".toCharArray()), Credential.Password("secret-jump".toCharArray())),
            )
        }

        val jumpRequest = withTimeout(5_000) { session.hostKeyRequest.filterNotNull().first { it.subject == HostKeySubject.JUMP } }
        assertEquals(HostKeyIssue.UNKNOWN, jumpRequest.issue)
        assertEquals(ConnectionStage.JUMP_HOST_KEY, session.stage.value)
        session.respondToHostKey(true)

        val targetRequest = withTimeout(5_000) { session.hostKeyRequest.filterNotNull().first { it.subject == HostKeySubject.TARGET } }
        assertEquals(HostKeyIssue.UNKNOWN, targetRequest.issue)
        assertEquals(ConnectionStage.TARGET_HOST_KEY, session.stage.value)
        session.respondToHostKey(true)

        withTimeout(10_000) { connection.await() }
        assertTrue(session.state.value is ConnectionState.Connected)
        assertEquals(ConnectionStage.READY, session.stage.value)

        val sftp = session.openSftpClient()
        try {
            assertTrue(sftp.list(".").any { it.name == "跳板可见.txt" && it.size > 0 })
        } finally {
            sftp.close()
        }
        session.disconnect()
    }

    @Test
    fun wrongJumpCredentialFailsBeforeTargetAuth() = runBlocking {
        val jump = profile("跳板机", jumpServer.port)
        val target = profile("目标机", targetServer.port)
        val connection = async {
            session.connect(
                SshRoute(target, jump),
                RouteCredentials(Credential.Password("secret-target".toCharArray()), Credential.Password("wrong".toCharArray())),
            )
        }
        withTimeout(5_000) { session.hostKeyRequest.filterNotNull().first { it.subject == HostKeySubject.JUMP } }
        session.respondToHostKey(true)
        withTimeout(10_000) { connection.await() }
        val state = session.state.value
        assertTrue("expected Error, got $state", state is ConnectionState.Error)
        assertTrue((state as ConnectionState.Error).message.contains("认证"))
    }

    private class MemoryKnownHostDao : KnownHostDao {
        private val values = mutableMapOf<String, KnownHostEntity>()
        override suspend fun find(hostname: String, port: Int): KnownHostEntity? = values["$hostname:$port"]
        override suspend fun insert(knownHost: KnownHostEntity) { values["${knownHost.hostname}:${knownHost.port}"] = knownHost }
        override suspend fun delete(hostname: String, port: Int) { values.remove("$hostname:$port") }
    }
}
