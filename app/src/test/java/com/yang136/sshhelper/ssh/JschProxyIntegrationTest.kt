package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.data.KnownHostEntity
import com.yang136.sshhelper.data.ProxyType
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JschProxyIntegrationTest {
    private lateinit var server: SshServer
    private lateinit var session: JschSshSession
    private lateinit var root: java.nio.file.Path

    @Before
    fun start() {
        root = Files.createTempDirectory("ssh-helper-proxy-test")
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
            passwordAuthenticator = PasswordAuthenticator { username, pwd, _ -> username == "test" && pwd == "secret" }
            shellFactory = org.apache.sshd.server.shell.ProcessShellFactory("/bin/sh -i", listOf("/bin/sh", "-i"))
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(root)
            start()
        }
        session = JschSshSession(MemoryKnownHostDao())
    }

    @After
    fun stop() {
        runCatching { session.close() }
        runCatching { server.stop(true) }
        root.toFile().deleteRecursively()
    }

    @Test
    fun connectsThroughHttpConnectProxyAndListsSftp() = runBlocking {
        val proxy = HttpConnectProxy("127.0.0.1", server.port)
        val profile = HostProfile(
            name = "proxy-host", hostname = "target.internal", port = 22,
            username = "test", authType = AuthType.PASSWORD,
            proxyType = ProxyType.HTTP, proxyHost = "127.0.0.1", proxyPort = proxy.port,
        )
        val connection = async {
            session.connect(
                SshRoute(profile, null),
                RouteCredentials(Credential.Password("secret".toCharArray()), null),
                openShell = true,
            )
        }
        withTimeout(5_000) { session.hostKeyRequest.filterNotNull().first() }
        session.respondToHostKey(true)
        withTimeout(10_000) { connection.await() }
        assertTrue(session.state.value is ConnectionState.Connected)
        assertTrue("SSH 握手必须经过代理，代理连接数=${proxy.connections.get()}", proxy.connections.get() >= 1)

        Files.write(root.resolve("through-proxy.txt"), "ok".encodeToByteArray())
        val sftp = session.openSftpClient()
        try {
            assertTrue("SFTP 应能经代理列出目标文件", sftp.list(".").any { it.name == "through-proxy.txt" })
        } finally {
            sftp.close()
        }
        session.disconnect()
    }

    @Test
    fun unreachableProxySurfacesConnectionError() = runBlocking {
        // Reserve a port and close it so nothing is listening there.
        val dead = ServerSocket(0)
        val deadPort = dead.localPort
        dead.close()
        val profile = HostProfile(
            name = "bad-proxy", hostname = "10.255.255.1", port = 22,
            username = "test", authType = AuthType.PASSWORD,
            proxyType = ProxyType.HTTP, proxyHost = "127.0.0.1", proxyPort = deadPort,
        )
        val connection = async {
            session.connect(
                SshRoute(profile, null),
                RouteCredentials(Credential.Password("secret".toCharArray()), null),
                openShell = true,
            )
        }
        withTimeout(15_000) { connection.await() }
        assertTrue("expected Error, got ${session.state.value}", session.state.value is ConnectionState.Error)
    }

    private class MemoryKnownHostDao : KnownHostDao {
        private var value: KnownHostEntity? = null
        override suspend fun find(hostname: String, port: Int): KnownHostEntity? = value
        override suspend fun insert(knownHost: KnownHostEntity) { value = knownHost }
        override suspend fun delete(hostname: String, port: Int) { value = null }
    }

    /** Minimal HTTP CONNECT tunnel: accepts CONNECT, bridges bytes to the target. */
    private class HttpConnectProxy(private val targetHost: String, private val targetPort: Int) {
        private val server = ServerSocket(0)
        val connections = AtomicInteger(0)
        val port: Int get() = server.localPort

        init {
            Thread {
                while (true) {
                    val client = try { server.accept() } catch (e: Exception) { return@Thread }
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val requestLine = reader.readLine() ?: return@Thread
                            while (true) {
                                val line = reader.readLine()
                                if (line == null || line.isEmpty()) break
                            }
                            if (!requestLine.startsWith("CONNECT ")) {
                                client.close()
                                return@Thread
                            }
                            connections.incrementAndGet()
                            val remote = Socket(targetHost, targetPort)
                            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream()))
                            writer.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                            writer.flush()
                            pump(client, remote)
                        } catch (e: Exception) {
                            runCatching { client.close() }
                        }
                    }.also { it.isDaemon = true; it.start() }
                }
            }.also { it.isDaemon = true; it.start() }
        }

        private fun pump(client: Socket, remote: Socket) {
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val finish = {
                if (done.compareAndSet(false, true)) {
                    runCatching { client.close() }
                    runCatching { remote.close() }
                }
            }
            Thread {
                runCatching { client.getInputStream().use { input -> input.copyTo(remote.getOutputStream()) } }
                finish()
            }.also { it.isDaemon = true; it.start() }
            Thread {
                runCatching { remote.getInputStream().use { input -> input.copyTo(client.getOutputStream()) } }
                finish()
            }.also { it.isDaemon = true; it.start() }
        }
    }
}
