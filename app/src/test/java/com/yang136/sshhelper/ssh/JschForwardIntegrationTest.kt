package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.ForwardType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.data.KnownHostEntity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JschForwardIntegrationTest {
    private lateinit var server: SshServer
    private lateinit var echo: EchoServer
    private lateinit var session: JschSshSession
    private lateinit var root: java.nio.file.Path

    @Before
    fun start() {
        root = Files.createTempDirectory("ssh-helper-forward-test")
        echo = EchoServer()
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
            passwordAuthenticator = PasswordAuthenticator { username, pwd, _ -> username == "test" && pwd == "secret" }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            shellFactory = ProcessShellFactory("/bin/sh -i", listOf("/bin/sh", "-i"))
            start()
        }
        session = JschSshSession(MemoryKnownHostDao())
    }

    @After
    fun stop() {
        runCatching { session.close() }
        runCatching { server.stop(true) }
        runCatching { echo.close() }
        root.toFile().deleteRecursively()
    }

    private suspend fun connectSession() = kotlinx.coroutines.coroutineScope {
        val profile = HostProfile(
            name = "test", hostname = "127.0.0.1", port = server.port,
            username = "test", authType = AuthType.PASSWORD,
        )
        val connection = async {
            session.connect(
                SshRoute(profile, null),
                RouteCredentials(Credential.Password("secret".toCharArray()), null),
            )
        }
        withTimeout(5_000) { session.hostKeyRequest.filterNotNull().first() }
        session.respondToHostKey(true)
        withTimeout(10_000) { connection.await() }
        assertTrue(session.state.value is ConnectionState.Connected)
    }

    @Test
    fun localForwardCarriesBidirectionalData() = runBlocking {
        connectSession()
        val handle = session.registerForward(
            ForwardRequest(ForwardType.LOCAL, "127.0.0.1", 0, "127.0.0.1", echo.port),
        )
        try {
            assertTrue(handle.actualListenPort > 0)
            Socket("127.0.0.1", handle.actualListenPort).use { socket ->
                echoRoundTrip(socket, "ping-through-local")
            }
        } finally {
            handle.close()
        }
        assertTrue("port must be released after close", runCatching { Socket("127.0.0.1", handle.actualListenPort) }.isFailure)
    }

    @Test
    fun remoteForwardExposesServerSideListener() = runBlocking {
        connectSession()
        val remotePort = 19000 + (Math.random() * 1000).toInt()
        val handle = session.registerForward(
            ForwardRequest(ForwardType.REMOTE, "127.0.0.1", remotePort, "127.0.0.1", echo.port),
        )
        try {
            Socket("127.0.0.1", remotePort).use { socket ->
                echoRoundTrip(socket, "ping-through-remote")
            }
        } finally {
            handle.close()
        }
    }

    @Test
    fun dynamicSocks5ConnectsAndRejectsUdp() = runBlocking {
        connectSession()
        val handle = session.registerForward(
            ForwardRequest(ForwardType.DYNAMIC, "127.0.0.1", 0, null, null),
        )
        try {
            Socket("127.0.0.1", handle.actualListenPort).use { socket ->
                val output = socksConnect(socket, "127.0.0.1", echo.port)
                echoRoundTrip(socket, "ping-through-socks", output)
            }
            Socket("127.0.0.1", handle.actualListenPort).use { socket ->
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                output.writeByte(0x05); output.writeByte(0x01); output.writeByte(0x00); output.flush()
                input.readUnsignedByte(); input.readUnsignedByte()
                output.writeByte(0x05); output.writeByte(0x03) // UDP ASSOCIATE → must be rejected
                output.writeByte(0x00); output.writeByte(0x01)
                output.write(ByteArray(4)); output.writeShort(0); output.flush()
                assertEquals(0x05, input.readUnsignedByte())
                assertEquals(0x07, input.readUnsignedByte())
            }
        } finally {
            handle.close()
        }
    }

    private fun socksConnect(socket: Socket, host: String, port: Int): DataOutputStream {
        val output = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())
        output.writeByte(0x05); output.writeByte(0x01); output.writeByte(0x00); output.flush()
        assertEquals(0x05, input.readUnsignedByte())
        assertEquals(0x00, input.readUnsignedByte())
        output.writeByte(0x05); output.writeByte(0x01); output.writeByte(0x00) // CONNECT
        output.writeByte(0x01) // IPv4
        host.split(".").forEach { output.writeByte(it.toInt()) }
        output.writeShort(port); output.flush()
        assertEquals(0x05, input.readUnsignedByte())
        assertEquals(0x00, input.readUnsignedByte())
        input.readUnsignedByte() // RSV
        input.readUnsignedByte() // ATYP
        val bound = ByteArray(4); input.readFully(bound)
        input.readUnsignedShort()
        return output
    }

    private fun echoRoundTrip(socket: Socket, payload: String, output: OutputStream? = null) {
        val stream = output ?: socket.getOutputStream()
        stream.write(payload.encodeToByteArray())
        stream.flush()
        val reply = ByteArray(payload.length)
        DataInputStream(socket.getInputStream()).readFully(reply)
        assertEquals(payload, reply.toString(Charsets.UTF_8))
    }

    private class MemoryKnownHostDao : KnownHostDao {
        private var value: KnownHostEntity? = null
        override suspend fun find(hostname: String, port: Int): KnownHostEntity? = value
        override suspend fun insert(knownHost: KnownHostEntity) { value = knownHost }
        override suspend fun delete(hostname: String, port: Int) { value = null }
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
                            socket.getInputStream().use { input -> socket.getOutputStream().use { output -> input.copyTo(output) } }
                        } catch (_: Exception) { }
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
