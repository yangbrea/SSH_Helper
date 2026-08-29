package com.yang136.sshhelper.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION")
class AndroidNetworkEnvironment(context: Context) : NetworkEnvironment {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networks = ConcurrentHashMap<String, Network>()

    override suspend fun availableNetworks(): List<LanNetwork> = withContext(Dispatchers.IO) {
        val active = connectivity.activeNetwork
        connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isWifi && !isEthernet) return@mapNotNull null
            val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val address = properties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return@mapNotNull null
            val id = network.networkHandle.toString()
            networks[id] = network
            LanNetwork(
                id = id,
                label = if (isEthernet) "以太网" else "Wi-Fi",
                interfaceName = properties.interfaceName ?: return@mapNotNull null,
                ipv4Address = address.address.hostAddress ?: return@mapNotNull null,
                prefixLength = address.prefixLength,
            ) to (network == active)
        }.sortedWith(compareByDescending<Pair<LanNetwork, Boolean>> { it.second }.thenBy { it.first.label })
            .map(Pair<LanNetwork, Boolean>::first)
    }

    fun networkFor(id: String): Network? = networks[id] ?: connectivity.allNetworks.firstOrNull {
        it.networkHandle.toString() == id
    }?.also { networks[id] = it }
}

class AndroidTcpSshProbe(
    private val networks: AndroidNetworkEnvironment,
    private val connectTimeoutMillis: Int = 500,
    private val bannerTimeoutMillis: Int = 750,
) : TcpSshProbe {
    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    override suspend fun probe(networkId: String, address: String, port: Int): TcpProbeResult? =
        withContext(Dispatchers.IO) {
            val network = networks.networkFor(networkId) ?: return@withContext null
            val socket = runCatching { network.socketFactory.createSocket() }.getOrNull() ?: return@withContext null
            sockets += socket
            try {
                socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
                TcpProbeResult(SocketBannerReader.read(socket, bannerTimeoutMillis))
            } catch (security: SecurityException) {
                throw security
            } catch (_: Exception) {
                null
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

class AndroidArpTableReader : ArpTableReader {
    override suspend fun read(interfaceName: String): Result<List<ArpEntry>> = withContext(Dispatchers.IO) {
        runCatching { ArpTableParser.parse(File("/proc/net/arp").readText(), interfaceName) }
    }
}

class AssetMacVendorResolver(context: Context) : MacVendorResolver {
    private val index by lazy {
        runCatching {
            context.assets.open("discovery/oui.tsv").bufferedReader().use { reader ->
                OuiIndex.parse(reader.lineSequence())
            }
        }.getOrDefault(OuiIndex.Empty)
    }

    override fun vendorFor(macAddress: String): String? = index.vendorFor(macAddress)
}

class AndroidMdnsDiscovery(
    context: Context,
    private val networks: AndroidNetworkEnvironment,
) : MdnsDiscovery {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val activeStops = CopyOnWriteArraySet<() -> Unit>()

    override fun discover(networkId: String): Flow<MdnsService> = callbackFlow {
        val targetNetwork = networks.networkFor(networkId) ?: run {
            close(IllegalStateException("所选局域网已断开"))
            return@callbackFlow
        }
        val multicastLock = wifiManager.createMulticastLock("ssh-helper-mdns-scan").apply {
            setReferenceCounted(false)
        }
        runCatching { multicastLock.acquire() }.onFailure {
            close(it)
            return@callbackFlow
        }

        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val resolver = launch(Dispatchers.IO) {
            for (service in resolveQueue) {
                val resolved = resolve(service) ?: continue
                if (Build.VERSION.SDK_INT >= 33 && resolved.network != null && resolved.network != targetNetwork) continue
                val addresses = if (Build.VERSION.SDK_INT >= 34) {
                    resolved.hostAddresses
                } else {
                    @Suppress("DEPRECATION")
                    listOfNotNull(resolved.host)
                }
                addresses.filterIsInstance<Inet4Address>().forEach { address ->
                    trySend(
                        MdnsService(
                            address = address.hostAddress ?: return@forEach,
                            port = resolved.port,
                            serviceName = resolved.serviceName,
                            serviceType = resolved.serviceType,
                        ),
                    )
                }
            }
        }

        val localStops = mutableListOf<() -> Unit>()
        var started = 0
        listOf("_ssh._tcp.", "_sftp-ssh._tcp.").forEach { type ->
            lateinit var listener: NsdManager.DiscoveryListener
            val stopped = AtomicBoolean(false)
            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    resolveQueue.trySend(serviceInfo)
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    stop()
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    stop()
                }

                private fun stop() {
                    if (stopped.compareAndSet(false, true)) {
                        runCatching { nsdManager.stopServiceDiscovery(listener) }
                    }
                }
            }
            val stop: () -> Unit = {
                if (stopped.compareAndSet(false, true)) {
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                }
            }
            localStops += stop
            activeStops += stop
            runCatching {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onSuccess { started++ }.onFailure { stop() }
        }
        if (started == 0) close(IllegalStateException("mDNS 服务发现启动失败"))

        awaitClose {
            localStops.forEach { stop -> stop(); activeStops -= stop }
            resolveQueue.close()
            resolver.cancel()
            if (multicastLock.isHeld) runCatching { multicastLock.release() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            runCatching {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(serviceInfo)
                    }
                })
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    override fun cancel() {
        activeStops.toList().forEach { it() }
        activeStops.clear()
    }
}
