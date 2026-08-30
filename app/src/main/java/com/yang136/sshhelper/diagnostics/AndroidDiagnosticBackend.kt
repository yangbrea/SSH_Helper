package com.yang136.sshhelper.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.yang136.sshhelper.discovery.SocketBannerReader
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDiagnosticBackend(context: Context) : DiagnosticBackend {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networks = ConcurrentHashMap<String, Network>()
    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    override suspend fun availableNetworks(): List<DiagnosticNetwork> = withContext(Dispatchers.IO) {
        val active = connectivity.activeNetwork
        connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val properties = connectivity.getLinkProperties(network)
            val id = network.networkHandle.toString()
            networks[id] = network
            val transports = capabilities.toDiagnosticTransports()
            val baseLabel = transports.joinToString(" + ") { it.displayName() }.ifBlank { "其他网络" }
            val interfaceName = properties?.interfaceName
            DiagnosticNetwork(
                id = id,
                label = if (interfaceName.isNullOrBlank()) baseLabel else "$baseLabel · $interfaceName",
                transports = transports,
                isDefault = network == active,
            )
        }.sortedWith(compareByDescending<DiagnosticNetwork> { it.isDefault }.thenBy { it.label })
    }

    override suspend fun snapshot(networkId: String): NetworkSnapshot? = withContext(Dispatchers.IO) {
        val network = networkFor(networkId) ?: return@withContext null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@withContext null
        val properties = connectivity.getLinkProperties(network) ?: return@withContext null
        val descriptor = descriptor(network, capabilities, properties)
        NetworkSnapshot(
            network = descriptor,
            interfaceName = properties.interfaceName,
            addresses = properties.linkAddresses.map { "${it.address.hostAddress}/${it.prefixLength}" },
            gateways = properties.routes.mapNotNull { it.gateway?.hostAddress }.distinct(),
            dnsServers = properties.dnsServers.mapNotNull(InetAddress::getHostAddress),
            mtu = properties.mtu.takeIf { it > 0 },
            privateDnsActive = Build.VERSION.SDK_INT >= 28 && properties.isPrivateDnsActive,
            privateDnsServerName = if (Build.VERSION.SDK_INT >= 28) properties.privateDnsServerName else null,
            httpProxy = properties.httpProxy?.let { proxy -> "${proxy.host}:${proxy.port}" },
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        )
    }

    override suspend fun resolve(networkId: String, hostname: String): List<String> = withContext(Dispatchers.IO) {
        val network = requireNotNull(networkFor(networkId)) { "所选网络已断开" }
        network.getAllByName(hostname).mapNotNull(InetAddress::getHostAddress)
    }

    override suspend fun connect(
        networkId: String,
        address: String,
        port: Int,
        timeoutMillis: Int,
        readSshBanner: Boolean,
    ): DiagnosticConnectResult = withContext(Dispatchers.IO) {
        val network = networkFor(networkId)
            ?: return@withContext DiagnosticConnectResult.Failed(DiagnosticFailureKind.UNREACHABLE, "所选网络已断开")
        val socket = try {
            network.socketFactory.createSocket()
        } catch (security: SecurityException) {
            return@withContext DiagnosticConnectResult.Failed(
                DiagnosticFailureKind.PERMISSION,
                security.message ?: "系统拒绝网络访问",
            )
        } catch (failure: Exception) {
            return@withContext DiagnosticConnectResult.Failed(DiagnosticFailureKind.IO, failure.message ?: "无法创建网络连接")
        }
        sockets += socket
        try {
            val targetAddress = InetAddress.getByName(address)
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(targetAddress, port), timeoutMillis.coerceAtLeast(1))
            val duration = (System.nanoTime() - started) / 1_000_000.0
            val banner = if (readSshBanner) {
                SocketBannerReader.read(socket, DEFAULT_BANNER_TIMEOUT_MILLIS)
            } else null
            DiagnosticConnectResult.Connected(DiagnosticConnection(address, duration, banner))
        } catch (security: SecurityException) {
            DiagnosticConnectResult.Failed(DiagnosticFailureKind.PERMISSION, security.message ?: "系统拒绝网络访问")
        } catch (timeout: SocketTimeoutException) {
            DiagnosticConnectResult.Failed(DiagnosticFailureKind.TIMEOUT, "连接超时")
        } catch (refused: ConnectException) {
            val message = refused.message ?: "连接被拒绝"
            DiagnosticConnectResult.Failed(
                if (message.contains("refused", ignoreCase = true)) DiagnosticFailureKind.REFUSED else DiagnosticFailureKind.IO,
                if (message.contains("refused", ignoreCase = true)) "连接被拒绝" else message,
            )
        } catch (unreachable: NoRouteToHostException) {
            DiagnosticConnectResult.Failed(DiagnosticFailureKind.UNREACHABLE, unreachable.message ?: "没有到目标的路由")
        } catch (failure: Exception) {
            DiagnosticConnectResult.Failed(DiagnosticFailureKind.IO, failure.message ?: "网络连接失败")
        } finally {
            sockets -= socket
            runCatching { socket.close() }
        }
    }

    override fun cancel() {
        sockets.toList().forEach { socket -> runCatching { socket.close() } }
        sockets.clear()
    }

    private fun networkFor(id: String): Network? = networks[id] ?: connectivity.allNetworks.firstOrNull {
        it.networkHandle.toString() == id
    }?.also { networks[id] = it }

    private fun descriptor(
        network: Network,
        capabilities: NetworkCapabilities,
        properties: LinkProperties,
    ): DiagnosticNetwork {
        val transports = capabilities.toDiagnosticTransports()
        val baseLabel = transports.joinToString(" + ") { it.displayName() }.ifBlank { "其他网络" }
        return DiagnosticNetwork(
            id = network.networkHandle.toString(),
            label = properties.interfaceName?.let { "$baseLabel · $it" } ?: baseLabel,
            transports = transports,
            isDefault = network == connectivity.activeNetwork,
        )
    }
}

private fun NetworkCapabilities.toDiagnosticTransports(): Set<DiagnosticTransport> = buildSet {
    if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(DiagnosticTransport.WIFI)
    if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(DiagnosticTransport.ETHERNET)
    if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(DiagnosticTransport.CELLULAR)
    if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(DiagnosticTransport.VPN)
    if (isEmpty()) add(DiagnosticTransport.OTHER)
}

private fun DiagnosticTransport.displayName(): String = when (this) {
    DiagnosticTransport.WIFI -> "Wi-Fi"
    DiagnosticTransport.ETHERNET -> "以太网"
    DiagnosticTransport.CELLULAR -> "蜂窝网络"
    DiagnosticTransport.VPN -> "VPN"
    DiagnosticTransport.OTHER -> "其他网络"
}
