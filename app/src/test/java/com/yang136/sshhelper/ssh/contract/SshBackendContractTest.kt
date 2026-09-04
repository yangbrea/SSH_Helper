package com.yang136.sshhelper.ssh.contract

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.ForwardType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.data.KnownHostEntity
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.ForwardRequest
import com.yang136.sshhelper.ssh.HostKeyIssue
import com.yang136.sshhelper.ssh.HostKeySubject
import com.yang136.sshhelper.ssh.RouteCredentials
import com.yang136.sshhelper.ssh.PortForwardCapableSession
import com.yang136.sshhelper.ssh.SftpCapableSession
import com.yang136.sshhelper.ssh.SshRoute
import com.yang136.sshhelper.ssh.SshSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Backend-neutral SSH contract tests.
 *
 * Concrete subclasses supply a backend-specific [createSession]; the tests only
 * depend on the public `SshSession` contract. JSch runs this suite today;
 * libssh2 will enable the same suite once `Libssh2SshSession` exists.
 */
abstract class SshBackendContractTest {
    protected lateinit var server: SshServer
    protected lateinit var root: Path
    protected val passwordAttempts = AtomicInteger(0)

    protected abstract fun createSession(knownHostDao: KnownHostDao): SshSession

    @Before
    fun startServer() {
        root = Files.createTempDirectory("ssh-backend-contract")
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
            passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                passwordAttempts.incrementAndGet()
                username == "test" && password == "secret"
            }
            shellFactory = ProcessShellFactory("/bin/sh -i", listOf("/bin/sh", "-i"))
            commandFactory = ProcessShellCommandFactory.INSTANCE
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(root)
            start()
        }
    }

    @After
    fun stopServer() {
        runCatching { server.stop(true) }
        root.toFile().deleteRecursively()
    }

    protected fun profile(port: Int = server.port): HostProfile = HostProfile(
        name = "contract",
        hostname = "127.0.0.1",
        port = port,
        username = "test",
        authType = AuthType.PASSWORD,
    )

    protected suspend fun connectAndConfirm(
        session: SshSession,
        hostProfile: HostProfile = profile(),
        password: String = "secret",
        openShell: Boolean = true,
    ) = coroutineScope {
        val connection = async {
            session.connect(
                SshRoute(hostProfile, null),
                RouteCredentials(Credential.Password(password.toCharArray()), null),
                openShell = openShell,
            )
        }
        val request = withTimeout(5_000) { session.hostKeyRequest.filterNotNull().first() }
        assertEquals(HostKeySubject.TARGET, request.subject)
        session.respondToHostKey(true)
        withTimeout(10_000) { connection.await() }
        assertTrue("expected Connected, got ${session.state.value}", session.state.value is ConnectionState.Connected)
    }

    @Test
    fun directPasswordConnectSurfacesUnknownHostKeyThenConnects() = runBlocking {
        val session = createSession(MemoryKnownHostDao())
        try {
            connectAndConfirm(session)
            session.disconnect()
        } finally {
            session.close()
        }
    }

    @Test
    fun headlessSessionExecutesCommandAndReturnsStdout() = runBlocking {
        val session = createSession(MemoryKnownHostDao())
        try {
            connectAndConfirm(session, openShell = false)
            val result = session.execute("printf 'contract-ok\\n'", timeoutMillis = 5_000)
            assertEquals(0, result.exitCode)
            assertEquals("contract-ok\n", result.stdout)
            assertEquals("", result.stderr)
            assertTrue(result.isSuccess)
        } finally {
            session.close()
        }
    }

    @Test
    fun shellSessionKeepsTransportAliveAcrossExecute() = runBlocking {
        val session = createSession(MemoryKnownHostDao())
        try {
            connectAndConfirm(session, openShell = true)
            val result = session.execute("printf 'shell-plus-exec\\n'", timeoutMillis = 5_000)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("shell-plus-exec"))
            session.write("printf 'still-alive\\n'\n".encodeToByteArray())
            // The shell is not read here; this test only verifies that writing to
            // an active shell after an exec channel does not tear down the transport.
            assertTrue(session.state.value is ConnectionState.Connected)
        } finally {
            session.close()
        }
    }

    private suspend fun connectWithPasswordAndExpectError(
        session: SshSession,
        password: String,
    ) = coroutineScope {
        val connection = async {
            session.connect(
                SshRoute(profile(), null),
                RouteCredentials(Credential.Password(password.toCharArray()), null),
                openShell = false,
            )
        }
        // Host-key confirmation still happens before authentication.
        val request = withTimeout(5_000) { session.hostKeyRequest.filterNotNull().first() }
        assertEquals(HostKeySubject.TARGET, request.subject)
        session.respondToHostKey(true)
        withTimeout(10_000) { connection.await() }
        assertTrue("expected Error, got ${session.state.value}", session.state.value is ConnectionState.Error)
    }

    private suspend fun connectExpectingNoHostKeyPrompt(
        session: SshSession,
        dao: KnownHostDao,
    ) = coroutineScope {
        val connection = async {
            session.connect(
                SshRoute(profile(), null),
                RouteCredentials(Credential.Password("secret".toCharArray()), null),
                openShell = false,
            )
        }
        // A matching known host must not surface a second host-key prompt.
        val unexpectedPrompt = withTimeoutOrNull(1_000) {
            session.hostKeyRequest.filterNotNull().first()
        }
        assertEquals(null, unexpectedPrompt)
        withTimeout(10_000) { connection.await() }
        assertTrue("expected Connected, got ${session.state.value}", session.state.value is ConnectionState.Connected)
        assertTrue("known host must be stored", dao.find("127.0.0.1", server.port) != null)
    }

    @Test
    fun wrongPasswordFailsAfterSingleAuthenticationAttempt() = runBlocking {
        val session = createSession(MemoryKnownHostDao())
        try {
            connectWithPasswordAndExpectError(session, "wrong-password")
            assertEquals("wrong password must be tried exactly once", 1, passwordAttempts.get())
        } finally {
            session.close()
        }
    }

    @Test
    fun acceptedHostKeyIsReusedWithoutSecondPrompt() = runBlocking {
        val dao = MemoryKnownHostDao()
        val first = createSession(dao)
        try {
            connectAndConfirm(first, openShell = false)
            first.disconnect()
        } finally {
            first.close()
        }

        val second = createSession(dao)
        try {
            connectExpectingNoHostKeyPrompt(second, dao)
        } finally {
            second.close()
        }
    }


    @Test
    fun changedHostKeyBlocksConnectionAndKeepsChangedRequest() = runBlocking {
        val dao = MemoryKnownHostDao(
            KnownHostEntity(
                id = "127.0.0.1:${server.port}",
                hostname = "127.0.0.1",
                port = server.port,
                keyType = "ssh-ed25519",
                keyBase64 = Base64.getEncoder().encodeToString("stale-wrong-key".encodeToByteArray()),
                fingerprintSha256 = "SHA256:stale",
            ),
        )
        val session = createSession(dao)
        try {
            val connection = async {
                session.connect(
                    SshRoute(profile(), null),
                    RouteCredentials(Credential.Password("secret".toCharArray()), null),
                    openShell = false,
                )
            }
            val request = withTimeout(5_000) { session.hostKeyRequest.filterNotNull().first() }
            assertEquals(HostKeyIssue.CHANGED, request.issue)
            withTimeout(10_000) { connection.await() }
            assertTrue("expected Error, got ${session.state.value}", session.state.value is ConnectionState.Error)
            assertEquals(HostKeyIssue.CHANGED, session.hostKeyRequest.value?.issue)
        } finally {
            session.close()
        }
    }


    @Test
    fun sftpLifecycleThroughBackendNeutralSession() = runBlocking {
        val session = createSession(MemoryKnownHostDao())
        val sftpCapable = session as? SftpCapableSession
            ?: error("contract backend must implement SftpCapableSession")
        try {
            connectAndConfirm(session, openShell = false)
            val sftp = sftpCapable.openSftpClient()
            try {
                val payload = "sftp-contract-data".encodeToByteArray()
                sftp.upload(ByteArrayInputStream(payload), "contract.bin")
                assertTrue(sftp.list(".").any { it.name == "contract.bin" })
                val downloaded = ByteArrayOutputStream()
                sftp.download("contract.bin", downloaded)
                assertArrayEquals(payload, downloaded.toByteArray())
                sftp.delete("contract.bin")
            } finally {
                sftp.close()
            }
        } finally {
            session.close()
        }
    }


    @Test
    fun localForwardRoundTripsThroughBackendNeutralSession() = runBlocking {
        val session = createSession(MemoryKnownHostDao())
        val forwardCapable = session as? PortForwardCapableSession
            ?: error("contract backend must implement PortForwardCapableSession")
        val echo = EchoServer()
        try {
            connectAndConfirm(session, openShell = false)
            val handle = forwardCapable.registerForward(
                ForwardRequest(ForwardType.LOCAL, "127.0.0.1", 0, "127.0.0.1", echo.port),
            )
            try {
                Socket("127.0.0.1", handle.actualListenPort).use { socket ->
                    val payload = "forward-contract".encodeToByteArray()
                    socket.getOutputStream().write(payload)
                    socket.getOutputStream().flush()
                    val reply = ByteArray(payload.size)
                    DataInputStream(socket.getInputStream()).readFully(reply)
                    assertArrayEquals(payload, reply)
                }
            } finally {
                handle.close()
                handle.close()
            }
        } finally {
            echo.close()
            session.close()
        }
    }



    protected class MemoryKnownHostDao(initial: KnownHostEntity? = null) : KnownHostDao {
        private var value: KnownHostEntity? = initial
        override suspend fun find(hostname: String, port: Int): KnownHostEntity? = value
        override suspend fun insert(knownHost: KnownHostEntity) {
            value = knownHost
        }
        override suspend fun delete(hostname: String, port: Int) {
            value = null
        }
    }

    private class EchoServer {
        private val server = ServerSocket(0)
        private val running = AtomicBoolean(true)
        val port: Int get() = server.localPort

        init {
            Thread {
                while (running.get()) {
                    val socket = try { server.accept() } catch (e: Exception) { return@Thread }
                    Thread {
                        try {
                            socket.getInputStream().use { input ->
                                socket.getOutputStream().use { output -> input.copyTo(output) }
                            }
                        } catch (_: Exception) {
                        }
                    }.also { it.isDaemon = true; it.start() }
                }
            }.also { it.isDaemon = true; it.start() }
        }

        fun close() {
            running.set(false)
            runCatching { server.close() }
        }
    }
}
