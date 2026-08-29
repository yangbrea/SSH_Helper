package com.yang136.sshhelper.discovery

import java.net.Socket
import java.net.SocketTimeoutException

object SocketBannerReader {
    fun read(socket: Socket, timeoutMillis: Int = 750, limit: Int = 2_048): SshBanner? {
        socket.soTimeout = timeoutMillis
        val buffer = ByteArray(limit)
        var size = 0
        while (size < buffer.size) {
            val read = try {
                socket.getInputStream().read(buffer, size, buffer.size - size)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read <= 0) break
            size += read
            SshBannerParser.parse(buffer.copyOf(size))?.let { return it }
        }
        return SshBannerParser.parse(buffer.copyOf(size))
    }
}
