package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.data.KnownHostEntity
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JschShellStabilityIntegrationTest {
    private lateinit var server: SshServer
    private lateinit var session: JschSshSession
    private lateinit var root: java.nio.file.Path

    @Before
    fun startServer() {
        root = Files.createTempDirectory("ssh-helper-shell-test")
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
            passwordAuthenticator = PasswordAuthenticator { username, password, _ -> username == "test" && password == "secret" }
            shellFactory = ProcessShellFactory("/bin/sh -i", listOf("/bin/sh", "-i"))
            start()
        }
        session = JschSshSession(MemoryKnownHostDao())
    }

    @After
    fun stopServer() {
        runCatching { session.close() }
        runCatching { server.stop(true) }
        root.toFile().deleteRecursively()
    }

    @Test
    fun sustainedAnsiOutputAndResizeKeepShellConnected() = runBlocking {
        val profile = HostProfile(
            name = "test",
            hostname = "127.0.0.1",
            port = server.port,
            username = "test",
            authType = AuthType.PASSWORD,
        )
        val hostKey = async { session.hostKeyRequest.filterNotNull().first() }
        val connection = async {
            session.connect(
                SshRoute(profile, null),
                RouteCredentials(Credential.Password("secret".toCharArray()), null),
            )
        }
        withTimeout(5_000) { hostKey.await() }
        session.respondToHostKey(true)
        withTimeout(5_000) { connection.await() }
        assertTrue(session.state.value is ConnectionState.Connected)

        val received = ByteArrayOutputStream()
        val completed = CompletableDeferred<Unit>()
        val collector = launch {
            session.output.collect { bytes ->
                received.write(bytes)
                if (received.size() >= OUTPUT_BYTES && received.toString(Charsets.UTF_8).contains("STRESS_DONE")) {
                    completed.complete(Unit)
                }
            }
        }
        repeat(80) { index -> session.resize(60 + index, 20 + index % 30) }
        session.write("yes '\u001B[32mVIM-REDRAW\u001B[0m' | head -c $OUTPUT_BYTES; printf '\\nSTRESS_DONE\\n'\n".encodeToByteArray())
        withTimeout(15_000) { completed.await() }

        assertTrue(received.size() >= OUTPUT_BYTES)
        assertTrue(session.state.value is ConnectionState.Connected)
        collector.cancelAndJoin()
        session.disconnect()
    }

    private class MemoryKnownHostDao : KnownHostDao {
        private var value: KnownHostEntity? = null
        override suspend fun find(hostname: String, port: Int): KnownHostEntity? = value
        override suspend fun insert(knownHost: KnownHostEntity) { value = knownHost }
        override suspend fun delete(hostname: String, port: Int) { value = null }
    }

    private companion object {
        const val OUTPUT_BYTES = 2 * 1024 * 1024
    }
}
