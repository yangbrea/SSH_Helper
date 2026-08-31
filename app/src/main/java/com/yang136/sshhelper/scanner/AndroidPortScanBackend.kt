package com.yang136.sshhelper.scanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class AndroidPortScanBackend(context: Context) : PortScanBackend {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networks = ConcurrentHashMap<String, Network>()
    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    override suspend fun availableNetworks(): List<PortScanNetwork> = withContext(Dispatchers.IO) {
        val active = connectivity.activeNetwork
        connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val id = network.networkHandle.toString()
            networks[id] = network
            PortScanNetwork(id, capabilities.label(), network == active)
        }.sortedWith(compareByDescending<PortScanNetwork>(PortScanNetwork::isDefault).thenBy(PortScanNetwork::label))
    }

    override suspend fun resolve(networkId: String, target: String): List<String> = runInterruptible(Dispatchers.IO) {
        val network = requireNotNull(networkFor(networkId)) { "所选网络已断开" }
        network.getAllByName(normalizeTarget(target)).mapNotNull(InetAddress::getHostAddress).distinct()
    }

    override suspend fun probe(
        networkId: String,
        targetHost: String,
        address: String,
        port: Int,
        connectTimeoutMillis: Int,
        bannerTimeoutMillis: Int,
    ): PortProbeResult = withContext(Dispatchers.IO) {
        val network = networkFor(networkId)
            ?: return@withContext PortProbeResult(address, port, PortState.UNREACHABLE, message = "所选网络已断开")
        val socket = try {
            network.socketFactory.createSocket()
        } catch (security: SecurityException) {
            return@withContext PortProbeResult(address, port, PortState.ERROR, message = security.message ?: "系统拒绝网络访问")
        } catch (failure: Exception) {
            return@withContext PortProbeResult(address, port, PortState.ERROR, message = failure.message ?: "无法创建 Socket")
        }
        sockets += socket
        try {
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(InetAddress.getByName(address), port), connectTimeoutMillis)
            val latency = (System.nanoTime() - started) / 1_000_000.0
            socket.soTimeout = bannerTimeoutMillis
            val fingerprint = runCatching { probeService(socket, normalizeTarget(targetHost), port) }
                .getOrElse { fallbackFingerprint(port) }
            PortProbeResult(address, port, PortState.OPEN, latency, fingerprint)
        } catch (_: SocketTimeoutException) {
            PortProbeResult(address, port, PortState.TIMEOUT_FILTERED, message = "连接超时或被过滤")
        } catch (failure: ConnectException) {
            if (failure.message.orEmpty().contains("refused", ignoreCase = true)) {
                PortProbeResult(address, port, PortState.REFUSED, message = "连接被拒绝")
            } else {
                PortProbeResult(address, port, PortState.ERROR, message = failure.message ?: "连接失败")
            }
        } catch (failure: NoRouteToHostException) {
            PortProbeResult(address, port, PortState.UNREACHABLE, message = failure.message ?: "目标不可达")
        } catch (failure: SecurityException) {
            PortProbeResult(address, port, PortState.ERROR, message = failure.message ?: "系统拒绝网络访问")
        } catch (failure: SocketException) {
            PortProbeResult(address, port, PortState.ERROR, message = failure.message ?: "Socket 已关闭")
        } catch (failure: Exception) {
            PortProbeResult(address, port, PortState.ERROR, message = failure.message ?: "网络探测失败")
        } finally {
            sockets -= socket
            runCatching { socket.close() }
        }
    }

    override fun cancel() {
        sockets.toList().forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private fun probeService(socket: Socket, targetHost: String, port: Int): ServiceFingerprint = when (port) {
        in TLS_PORTS -> probeTls(socket, targetHost, port)
        in HTTP_PORTS -> {
            socket.getOutputStream().write("HEAD / HTTP/1.0\r\nHost: $targetHost\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            readBanner(socket)?.let { fingerprintFromBanner(port, it) } ?: fallbackFingerprint(port)
        }
        in PASSIVE_BANNER_PORTS -> readBanner(socket)?.let { fingerprintFromBanner(port, it) } ?: fallbackFingerprint(port)
        else -> fallbackFingerprint(port)
    }

    private fun probeTls(socket: Socket, targetHost: String, port: Int): ServiceFingerprint {
        val trustManager = CapturingTrustManager()
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        val ssl = context.socketFactory.createSocket(socket, targetHost, port, true) as SSLSocket
        ssl.soTimeout = socket.soTimeout
        ssl.startHandshake()
        val session = ssl.session
        val certificate = trustManager.chain?.firstOrNull()
            ?: runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        val alpn = if (Build.VERSION.SDK_INT >= 29) ssl.applicationProtocol.takeIf(String::isNotBlank) else null
        return tlsFingerprint(
            port = port,
            protocol = session.protocol,
            cipherSuite = session.cipherSuite,
            subject = certificate?.subjectX500Principal?.name,
            issuer = certificate?.issuerX500Principal?.name,
            applicationProtocol = alpn,
        )
    }

    private fun readBanner(socket: Socket): String? {
        val buffer = ByteArray(MAX_PORT_BANNER_BYTES)
        var size = 0
        while (size < buffer.size) {
            val count = try {
                socket.getInputStream().read(buffer, size, buffer.size - size)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (count <= 0) break
            size += count
            if (buffer.take(size).contains('\n'.code.toByte())) break
        }
        return if (size == 0) null else String(buffer, 0, size, Charsets.ISO_8859_1)
    }

    private fun networkFor(id: String): Network? = networks[id] ?: connectivity.allNetworks.firstOrNull {
        it.networkHandle.toString() == id
    }?.also { networks[id] = it }
}

private class CapturingTrustManager : X509TrustManager {
    var chain: Array<out X509Certificate>? = null
        private set
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { this.chain = chain }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun NetworkCapabilities.label(): String = buildList {
    if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
    if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("以太网")
    if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("蜂窝网络")
    if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
}.joinToString(" + ").ifBlank { "其他网络" }

private fun normalizeTarget(value: String): String = value.trim().removePrefix("[").removeSuffix("]")
