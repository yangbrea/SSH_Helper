package com.yang136.sshhelper.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal SOCKS5 (RFC 1928) server bound to a loopback interface. Every CONNECT request opens a
 * `direct-tcpip` channel on the connected SSH session, so destination resolution happens on the
 * final SSH server (useful for private DNS). UDP and BIND commands are rejected; no traffic
 * content, destination data, or request logs are recorded.
 *
 * Concurrency is bounded: at most [MAX_CONCURRENT_CONNECTIONS] live connections; excess
 * connections are closed immediately instead of unbounded thread growth. All sockets/channels
 * are tracked and closed on [close].
 */
class Socks5Server(
    private val sshSession: Session,
    private val bindAddress: String,
    private val listenPort: Int,
) {
    private val serverSocket = ServerSocket()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ssh-helper-socks5").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(MAX_CONCURRENT_CONNECTIONS)

    @Volatile
    var actualPort: Int = listenPort
        private set

    fun start() {
        serverSocket.bind(InetSocketAddress(bindAddress, listenPort))
        actualPort = serverSocket.localPort
        executor.submit { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (error: Exception) {
                if (closed.get()) return else continue
            }
            if (!slots.tryAcquire()) {
                // 并发连接数已达上限：直接拒绝，避免线程与内存膨胀。
                runCatching { socket.close() }
                continue
            }
            connections.add(socket)
            try {
                executor.submit { handle(socket) }
            } catch (error: Throwable) {
                // close() 与 accept 的竞态：executor 已关闭，丢弃该连接。
                slots.release()
                connections.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        var tunnel: ChannelDirectTCPIP? = null
        try {
            socket.soTimeout = 30_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // Greeting: VER + NMETHODS + METHODS. Reply "no authentication required".
            val version = input.readUnsignedByte()
            if (version != SOCKS_VERSION) return socket.close()
            val methodCount = input.readUnsignedByte()
            repeat(methodCount) { input.readUnsignedByte() }
            output.writeByte(SOCKS_VERSION)
            output.writeByte(0x00)
            output.flush()

            // Request: VER CMD RSV ATYP DST.ADDR DST.PORT
            val requestVersion = input.readUnsignedByte()
            if (requestVersion != SOCKS_VERSION) return socket.close()
            val command = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val addressType = input.readUnsignedByte()
            val host = when (addressType) {
                0x01 -> {
                    val octets = ByteArray(4)
                    input.readFully(octets)
                    octets.joinToString(".") { byte -> (byte.toInt() and 0xFF).toString() }
                }
                0x04 -> {
                    val octets = ByteArray(16)
                    input.readFully(octets)
                    octets.joinToString(":") { byte -> "%02x".format(byte) }
                }
                0x03 -> {
                    val length = input.readUnsignedByte()
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    bytes.toString(Charsets.UTF_8)
                }
                else -> {
                    reject(input, output, 0x08)
                    return
                }
            }
            val port = input.readUnsignedShort()

            if (command != 0x01) {
                // Only TCP CONNECT is supported; UDP (0x03) and BIND (0x02) are refused.
                reject(input, output, 0x07)
                return
            }

            // Same wiring as JSch's own local forwarding: hand the client socket streams to the
            // direct-tcpip channel; connect() only opens it and starts the channel's pump thread.
            val channel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
            tunnel = channel
            channel.setHost(host)
            channel.setPort(port)
            channel.setInputStream(socket.getInputStream())
            channel.setOutputStream(socket.getOutputStream())
            channel.connect(TUNNEL_CONNECT_TIMEOUT_MS)
            // Success reply: VER REP RSV ATYP=IPv4 BND.ADDR BND.PORT.
            output.writeByte(SOCKS_VERSION)
            output.writeByte(0x00)
            output.writeByte(0x00)
            output.writeByte(0x01)
            output.write(ByteArray(4))
            output.writeShort(0)
            output.flush()
            // Keep the client connection alive until the channel's run loop ends.
            while (!channel.isClosed && !closed.get()) {
                Thread.sleep(50)
            }
            runCatching { socket.close() }
        } catch (_: Exception) {
            runCatching { socket.close() }
        } finally {
            // 无论正常结束、异常还是服务器关闭，都释放资源与并发名额。
            runCatching { tunnel?.disconnect() }
            runCatching { socket.close() }
            connections.remove(socket)
            slots.release()
        }
    }

    private fun reject(input: DataInputStream, output: DataOutputStream, reason: Int) {
        runCatching {
            output.writeByte(SOCKS_VERSION)
            output.writeByte(reason)
            output.writeByte(0x00)
            output.writeByte(0x01)
            output.write(ByteArray(4))
            output.writeShort(0)
            output.flush()
        }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { serverSocket.close() }
            // 关闭所有仍在活动的客户端连接，使各自 handle() 的忙等循环退出。
            connections.forEach { runCatching { it.close() } }
            connections.clear()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val SOCKS_VERSION = 0x05
        const val TUNNEL_CONNECT_TIMEOUT_MS = 15_000
        const val MAX_CONCURRENT_CONNECTIONS = 64
    }
}
