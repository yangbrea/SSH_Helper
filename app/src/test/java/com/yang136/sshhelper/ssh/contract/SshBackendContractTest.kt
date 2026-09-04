package com.yang136.sshhelper.ssh.contract

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.data.KnownHostEntity
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.HostKeyIssue
import com.yang136.sshhelper.ssh.HostKeySubject
import com.yang136.sshhelper.ssh.RouteCredentials
import com.yang136.sshhelper.ssh.SshRoute
import com.yang136.sshhelper.ssh.SshSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
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
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.server.shell.ProcessShellFactory
import org.junit.After
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
}
