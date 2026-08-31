package com.yang136.sshhelper.discovery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

val GENERAL_PHASE_ONE_PORTS = setOf(22, 80, 443, 445, 554, 9100)
val GENERAL_PHASE_TWO_PORTS = setOf(21, 23, 53, 139, 631, 1883, 3389, 8000, 8080, 8443)

class DefaultLanDiscoveryEngine(
    private val networkEnvironment: NetworkEnvironment,
    private val tcpProbe: TcpServiceProbe,
    private val mdnsDiscovery: MdnsDiscovery,
    private val arpTableReader: ArpTableReader,
    private val macVendorResolver: MacVendorResolver,
    private val ssdpDiscovery: SsdpDiscovery = NoOpSsdpDiscovery,
    private val concurrency: Int = 48,
    private val sshMdnsWindowMillis: Long = 5_000,
    private val generalMdnsWindowMillis: Long = 7_000,
    /** Retains source compatibility with the original SSH-only engine tests. */
    mdnsWindowMillis: Long? = null,
) : LanDiscoveryEngine {
    private val activeJob = AtomicReference<Job?>(null)
    private val effectiveSshMdnsWindow = mdnsWindowMillis ?: sshMdnsWindowMillis
    private val effectiveGeneralMdnsWindow = mdnsWindowMillis ?: generalMdnsWindowMillis

    override fun scan(request: ScanRequest): Flow<DiscoveryEvent> = channelFlow {
        val job = currentCoroutineContext()[Job] ?: error("扫描缺少协程任务")
        activeJob.getAndSet(job)?.cancel()
        val network = networkEnvironment.availableNetworks().firstOrNull { it.id == request.networkId }
            ?: error("所选局域网已断开")
        val addresses = request.cidr.usableAddresses(request.ownAddress)
        val initialPorts = when (request.mode) {
            ScanMode.SSH -> request.ports
            ScanMode.GENERAL -> GENERAL_PHASE_ONE_PORTS + request.ports.take(MAX_GENERAL_CUSTOM_PORTS)
        }.sorted()
        val initialTargets = addresses.flatMap { address -> initialPorts.map { port -> address to port } }
        val discoveredAddresses = ConcurrentHashMap.newKeySet<String>()
        val scheduled = ConcurrentHashMap.newKeySet<String>()
        val completed = AtomicInteger(0)
        val total = AtomicInteger(initialTargets.size)
        send(DiscoveryEvent.Started(total.get()))

        suspend fun probe(
            address: String,
            port: Int,
            dynamic: Boolean,
            advertisedKind: ServiceKind? = null,
        ) {
            val key = "$address:$port"
            if (!scheduled.add(key)) return
            if (dynamic) {
                total.incrementAndGet()
                send(DiscoveryEvent.Progress(completed.get(), total.get()))
            }
            val kind = advertisedKind ?: if (request.mode == ScanMode.SSH) {
                ServiceKind.SSH
            } else {
                serviceKindForPort(port)
            }
            val result = tcpProbe.probe(
                request.networkId,
                address,
                port,
                readSshBanner = request.mode == ScanMode.SSH || kind == ServiceKind.SSH,
            )
            if (result != null) {
                discoveredAddresses += address
                send(DiscoveryEvent.Evidence(DiscoveryEvidence.Tcp(address, port, result.banner, kind)))
            }
            send(DiscoveryEvent.Progress(completed.incrementAndGet(), total.get()))
        }

        suspend fun runTargets(targets: List<Pair<String, Int>>, dynamic: Boolean) {
            if (targets.isEmpty()) return
            val tasks = Channel<Pair<String, Int>>(capacity = concurrency.coerceAtLeast(1) * 2)
            val workers = List(concurrency.coerceAtLeast(1)) {
                launch(Dispatchers.IO) {
                    for ((address, port) in tasks) probe(address, port, dynamic)
                }
            }
            targets.forEach { tasks.send(it) }
            tasks.close()
            workers.joinAll()
        }

        try {
            val mdnsTypes = if (request.mode == ScanMode.SSH) SSH_MDNS_SERVICE_TYPES else GENERAL_MDNS_SERVICE_TYPES
            val mdnsWindow = if (request.mode == ScanMode.SSH) effectiveSshMdnsWindow else effectiveGeneralMdnsWindow
            val mdnsJob = launch {
                try {
                    withTimeoutOrNull(mdnsWindow) {
                        mdnsDiscovery.discover(request.networkId, mdnsTypes).collect { service ->
                            if (!request.cidr.contains(service.address)) return@collect
                            val kind = serviceKindForMdns(service.serviceType)
                            if (request.mode == ScanMode.SSH && kind != ServiceKind.SSH) return@collect
                            discoveredAddresses += service.address
                            send(DiscoveryEvent.Evidence(
                                DiscoveryEvidence.Mdns(
                                    address = service.address,
                                    port = service.port,
                                    serviceName = service.serviceName,
                                    serviceType = service.serviceType,
                                    serviceKind = kind,
                                ),
                            ))
                            launch { probe(service.address, service.port, dynamic = true, advertisedKind = kind) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    send(DiscoveryEvent.Notice("mDNS 服务发现不可用，已继续执行 TCP 扫描"))
                }
            }

            val ssdpJob = if (request.mode == ScanMode.GENERAL) launch {
                try {
                    ssdpDiscovery.discover(request.networkId, request.cidr).collect { record ->
                        if (!request.cidr.contains(record.address)) return@collect
                        discoveredAddresses += record.address
                        send(DiscoveryEvent.Evidence(DiscoveryEvidence.Ssdp(record)))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    send(DiscoveryEvent.Notice("SSDP/UPnP 发现不可用，已继续执行其他扫描"))
                }
            } else null

            runTargets(initialTargets, dynamic = false)
            mdnsJob.join()
            ssdpJob?.join()

            if (request.mode == ScanMode.GENERAL) {
                val secondPhaseTargets = discoveredAddresses.toList().flatMap { address ->
                    GENERAL_PHASE_TWO_PORTS.map { port -> address to port }
                }.filterNot { (address, port) -> "$address:$port" in scheduled }
                runTargets(secondPhaseTargets, dynamic = true)
            }

            val arpResult = withContext(Dispatchers.IO) { arpTableReader.read(network.interfaceName) }
            arpResult.onSuccess { entries ->
                entries.filter { request.cidr.contains(it.address) }
                    .filter { request.mode == ScanMode.GENERAL || it.address in discoveredAddresses }
                    .forEach { entry ->
                        send(DiscoveryEvent.Evidence(
                            DiscoveryEvidence.Arp(
                                address = entry.address,
                                macAddress = entry.macAddress,
                                vendor = macVendorResolver.vendorFor(entry.macAddress),
                            ),
                        ))
                    }
            }.onFailure {
                val scope = if (request.mode == ScanMode.SSH) "SSH 扫描结果" else "设备发现结果"
                send(DiscoveryEvent.Notice("系统限制，无法读取 ARP/MAC；不影响$scope"))
            }
            // Concurrent workers can deliver two progress events out of order even though the
            // atomic counters themselves are correct. Publish one canonical terminal snapshot
            // so collectors never finish on a stale completed/total pair.
            send(DiscoveryEvent.Progress(completed.get(), total.get()))
            send(DiscoveryEvent.Completed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            tcpProbe.cancel()
            mdnsDiscovery.cancel()
            ssdpDiscovery.cancel()
            activeJob.compareAndSet(job, null)
        }
    }

    override fun cancel() {
        activeJob.getAndSet(null)?.cancel()
        tcpProbe.cancel()
        mdnsDiscovery.cancel()
        ssdpDiscovery.cancel()
    }
}
