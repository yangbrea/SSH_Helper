package com.yang136.sshhelper.discovery

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocketBannerReaderTest {
    @Test
    fun readsBannerFromLocalTcpServer() {
        ServerSocket(0).use { server ->
            val writer = thread {
                server.accept().use { client ->
                    client.getOutputStream().write("Authorized access only\r\nSSH-2.0-TestServer_1.0\r\n".toByteArray())
                    client.getOutputStream().flush()
                }
            }
            Socket("127.0.0.1", server.localPort).use { client ->
                assertEquals("TestServer_1.0", SocketBannerReader.read(client, 500)?.softwareVersion)
            }
            writer.join()
        }
    }

    @Test
    fun returnsNullWhenOpenServerDoesNotSendBanner() {
        ServerSocket(0).use { server ->
            val holder = thread {
                server.accept().use { Thread.sleep(150) }
            }
            Socket("127.0.0.1", server.localPort).use { client ->
                assertNull(SocketBannerReader.read(client, 30))
            }
            holder.join()
        }
    }
}
