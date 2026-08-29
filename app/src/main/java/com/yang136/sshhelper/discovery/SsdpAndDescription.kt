package com.yang136.sshhelper.discovery

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal const val SSDP_MAX_PACKET_BYTES = 8 * 1_024
internal const val SSDP_MAX_RESPONSES = 256
internal const val DESCRIPTION_MAX_BYTES = 64 * 1_024

object SsdpProtocol {
    val searchRequest: ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: ssdp:all\r\n\r\n"
        ).toByteArray(StandardCharsets.US_ASCII)

    fun parseResponse(address: String, bytes: ByteArray, length: Int = bytes.size): SsdpRecord? {
        if (length !in 1..SSDP_MAX_PACKET_BYTES || parseIpv4(address) == null) return null
        val text = bytes.copyOf(length).toString(StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n", "\n")
        if (!lines.firstOrNull().orEmpty().trim().startsWith("HTTP/1.1 200", ignoreCase = true)) return null
        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim().lowercase(Locale.US)
                val value = sanitizeHeader(line.substring(separator + 1)) ?: return@forEach
                headers.putIfAbsent(name, value)
            }
        }
        val st = headers["st"] ?: return null
        return SsdpRecord(
            address = address,
            st = st,
            usn = headers["usn"],
            server = headers["server"],
            location = headers["location"],
        )
    }

    private fun sanitizeHeader(value: String): String? = value
        .filter { it.code in 0x20..0x7e || it.code > 0x9f }
        .trim()
        .take(1_024)
        .takeIf(String::isNotEmpty)
}

class SsdpResponseAccumulator(
    private val cidr: Ipv4Cidr,
    private val limit: Int = SSDP_MAX_RESPONSES,
) {
    private val records = mutableListOf<SsdpRecord>()
    private val dedup = mutableSetOf<Triple<String, String?, String>>()

    fun add(address: String, bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (records.size >= limit || !cidr.contains(address)) return false
        val record = SsdpProtocol.parseResponse(address, bytes, length) ?: return false
        if (!dedup.add(Triple(address, record.usn, record.st))) return false
        records += record
        return true
    }

    fun snapshot(): List<SsdpRecord> = records.toList()
}

class AndroidSsdpDiscovery(
    private val networks: AndroidNetworkEnvironment,
    private val responseWindowMillis: Long = 2_500,
) : SsdpDiscovery {
    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<DatagramSocket, Boolean>())

    override fun discover(networkId: String, cidr: Ipv4Cidr): Flow<SsdpRecord> = flow {
        val records = withContext(Dispatchers.IO) { discoverBlocking(networkId, cidr) }
        records.forEach { emit(it) }
    }

    private fun discoverBlocking(networkId: String, cidr: Ipv4Cidr): List<SsdpRecord> {
        val network = networks.networkFor(networkId) ?: error("所选局域网已断开")
        val socket = DatagramSocket(null)
        sockets += socket
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            network.bindSocket(socket)
            val target = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)
            repeat(2) { index ->
                socket.send(DatagramPacket(SsdpProtocol.searchRequest, SsdpProtocol.searchRequest.size, target))
                if (index == 0) Thread.sleep(250)
            }

            val deadline = System.nanoTime() + responseWindowMillis * 1_000_000
            val accumulator = SsdpResponseAccumulator(cidr)
            var receivedPackets = 0
            while (receivedPackets < SSDP_MAX_RESPONSES) {
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMillis <= 0) break
                socket.soTimeout = remainingMillis.coerceIn(1, 250).toInt()
                val buffer = ByteArray(SSDP_MAX_PACKET_BYTES + 1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                receivedPackets++
                val source = (packet.address as? Inet4Address)?.hostAddress ?: continue
                accumulator.add(source, packet.data, packet.length)
            }
            accumulator.snapshot()
        } finally {
            sockets -= socket
            runCatching { socket.close() }
        }
    }

    override fun cancel() {
        sockets.toList().forEach { runCatching { it.close() } }
        sockets.clear()
    }
}

object DeviceDescriptionParser {
    fun validateLocation(sourceAddress: String, location: String): URL? {
        val uri = runCatching { URI(location.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.US) != "http" || uri.userInfo != null || uri.fragment != null) return null
        val host = uri.host ?: return null
        if (parseIpv4(host) == null || host != sourceAddress) return null
        if (uri.port !in -1..65_535 || uri.port == 0) return null
        return runCatching { uri.toURL() }.getOrNull()
    }

    fun parse(bytes: ByteArray): DeviceDescription {
        require(bytes.size <= DESCRIPTION_MAX_BYTES) { "设备描述超过 64 KiB" }
        val raw = bytes.toString(StandardCharsets.UTF_8)
        val asciiView = bytes.toString(StandardCharsets.ISO_8859_1)
        require(!asciiView.contains("<!DOCTYPE", ignoreCase = true)) { "设备描述包含不允许的 DOCTYPE" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            runCatching { setExpandEntityReferences(false) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val document = factory.newDocumentBuilder().parse(raw.byteInputStream())
        val values = mutableMapOf<String, String>()
        val nodes = document.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.localName ?: node.nodeName.substringAfter(':')
            if (name in DESCRIPTION_FIELDS) {
                val text = node.textContent
                    ?.filter { it.code >= 0x20 || it == '\n' || it == '\t' }
                    ?.trim()
                    ?.take(256)
                if (!text.isNullOrEmpty()) values.putIfAbsent(name, text)
            }
        }
        return DeviceDescription(
            friendlyName = values["friendlyName"],
            manufacturer = values["manufacturer"],
            modelName = values["modelName"],
            modelNumber = values["modelNumber"],
            deviceType = values["deviceType"],
        )
    }

    private val DESCRIPTION_FIELDS = setOf(
        "friendlyName", "manufacturer", "modelName", "modelNumber", "deviceType",
    )
}

class AndroidDeviceDescriptionRepository(
    private val networks: AndroidNetworkEnvironment,
) : DeviceDescriptionRepository {
    private val cache = ConcurrentHashMap<String, DeviceDescription>()
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    override suspend fun load(
        networkId: String,
        address: String,
        location: String,
    ): Result<DeviceDescription> = withContext(Dispatchers.IO) {
        val key = "$networkId|$address|$location"
        cache[key]?.let { return@withContext Result.success(it) }
        runCatching {
            val url = requireNotNull(DeviceDescriptionParser.validateLocation(address, location)) {
                "SSDP LOCATION 必须是与响应来源一致的 HTTP IPv4 地址"
            }
            val network = requireNotNull(networks.networkFor(networkId)) { "所选局域网已断开" }
            val socket = network.socketFactory.createSocket()
            activeSockets += socket
            try {
                val port = if (url.port == -1) 80 else url.port
                socket.connect(InetSocketAddress(address, port), 1_500)
                socket.soTimeout = 1_500
                socket.getOutputStream().apply {
                    write(DeviceDescriptionHttp.request(url))
                    flush()
                }
                val rawResponse = DeviceDescriptionHttp.readResponse(socket)
                val bytes = DeviceDescriptionHttp.parseResponse(rawResponse)
                DeviceDescriptionParser.parse(bytes).also { cache[key] = it }
            } finally {
                activeSockets -= socket
                runCatching { socket.close() }
            }
        }
    }

    override fun clear() {
        activeSockets.toList().forEach { runCatching { it.close() } }
        activeSockets.clear()
        cache.clear()
    }
}

object DeviceDescriptionHttp {
    private const val MAX_HEADER_BYTES = 16 * 1_024

    fun request(url: URL): ByteArray {
        val path = url.file.takeIf(String::isNotEmpty) ?: "/"
        val host = if (url.port == -1 || url.port == 80) url.host else "${url.host}:${url.port}"
        return (
            "GET $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "Accept: application/xml, text/xml\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
    }

    fun readResponse(socket: Socket): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val read = try {
                socket.getInputStream().read(buffer)
            } catch (_: SocketTimeoutException) {
                if (output.size() == 0) throw SocketTimeoutException("设备描述读取超时") else break
            }
            if (read < 0) break
            require(output.size() + read <= DESCRIPTION_MAX_BYTES + MAX_HEADER_BYTES) {
                "设备描述响应超过限制"
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun parseResponse(raw: ByteArray): ByteArray {
        val text = raw.toString(StandardCharsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        require(headerEnd in 1..MAX_HEADER_BYTES) { "设备描述 HTTP 响应头无效" }
        val headerLines = text.substring(0, headerEnd).split("\r\n")
        val status = headerLines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
            ?: error("设备描述 HTTP 状态无效")
        require(status in 200..299) { "设备描述请求失败（HTTP $status）" }
        val headers = headerLines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase(Locale.US) to
                line.substring(separator + 1).trim()
        }.toMap()
        val body = raw.copyOfRange(headerEnd + 4, raw.size)
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return decodeChunked(body)
        }
        val length = headers["content-length"]?.toLongOrNull()
        if (length != null) {
            require(length in 0..DESCRIPTION_MAX_BYTES.toLong()) { "设备描述超过 64 KiB" }
            require(body.size >= length) { "设备描述响应不完整" }
            return body.copyOf(length.toInt())
        }
        require(body.size <= DESCRIPTION_MAX_BYTES) { "设备描述超过 64 KiB" }
        return body
    }

    private fun decodeChunked(body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (true) {
            val lineEnd = indexOfCrlf(body, offset)
            require(lineEnd >= 0) { "设备描述分块响应无效" }
            val size = body.copyOfRange(offset, lineEnd).toString(StandardCharsets.US_ASCII)
                .substringBefore(';').trim().toIntOrNull(16)
                ?: error("设备描述分块长度无效")
            offset = lineEnd + 2
            if (size == 0) return output.toByteArray()
            require(size >= 0 && output.size() + size <= DESCRIPTION_MAX_BYTES) { "设备描述超过 64 KiB" }
            require(offset + size + 2 <= body.size) { "设备描述分块响应不完整" }
            output.write(body, offset, size)
            offset += size
            require(body[offset] == '\r'.code.toByte() && body[offset + 1] == '\n'.code.toByte()) {
                "设备描述分块响应无效"
            }
            offset += 2
        }
    }

    private fun indexOfCrlf(bytes: ByteArray, start: Int): Int {
        for (index in start until bytes.size - 1) {
            if (bytes[index] == '\r'.code.toByte() && bytes[index + 1] == '\n'.code.toByte()) return index
        }
        return -1
    }
}
