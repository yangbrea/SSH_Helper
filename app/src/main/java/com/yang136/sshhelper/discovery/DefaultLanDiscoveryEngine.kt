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

class DefaultLanDiscoveryEngine(
    private val networkEnvironment: NetworkEnvironment,
    private val tcpProbe: TcpServiceProbe,
    private val mdnsDiscovery: MdnsDiscovery,
    private val arpTableReader: ArpTableReader,
    private val macVendorResolver: MacVendorResolver,
    private val concurrency: Int = 48,
    private val mdnsWindowMillis: Long = 5_000,
) : LanDiscoveryEngine {
    private val activeJob = AtomicReference<Job?>(null)

    override fun scan(request: ScanRequest): Flow<DiscoveryEvent> = channelFlow {
        val job = currentCoroutineContext()[Job] ?: error("扫描缺少协程任务")
        activeJob.getAndSet(job)?.cancel()
        val network = networkEnvironment.availableNetworks().firstOrNull { it.id == request.networkId }
            ?: error("所选局域网已断开")
        val addresses = request.cidr.usableAddresses(request.ownAddress)
        val ports = request.ports.sorted()
        val targets = addresses.flatMap { address -> ports.map { port -> address to port } }
        val seenAddresses = ConcurrentHashMap.newKeySet<String>()
        val scheduled = ConcurrentHashMap.newKeySet<String>()
        val completed = AtomicInteger(0)
        send(DiscoveryEvent.Started(targets.size))

        suspend fun probe(address: String, port: Int, countProgress: Boolean) {
            val key = "$address:$port"
            if (!scheduled.add(key)) return
            val result = tcpProbe.probe(request.networkId, address, port)
            if (result != null) {
                seenAddresses += address
                send(DiscoveryEvent.Evidence(DiscoveryEvidence.Tcp(address, port, result.banner)))
            }
            if (countProgress) {
                send(DiscoveryEvent.Progress(completed.incrementAndGet(), targets.size))
            }
        }

        try {
            val mdnsJob = launch {
                try {
                    withTimeoutOrNull(mdnsWindowMillis) {
                        mdnsDiscovery.discover(request.networkId).collect { service ->
                            if (!request.cidr.contains(service.address)) return@collect
                            seenAddresses += service.address
                            send(DiscoveryEvent.Evidence(
                                DiscoveryEvidence.Mdns(
                                    address = service.address,
                                    port = service.port,
                                    serviceName = service.serviceName,
                                    serviceType = service.serviceType,
                                ),
                            ))
                            if (service.port !in request.ports) launch {
                                probe(service.address, service.port, countProgress = false)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    send(DiscoveryEvent.Notice("mDNS 服务发现不可用，已继续执行 TCP 扫描"))
                }
            }

            val tasks = Channel<Pair<String, Int>>(capacity = concurrency * 2)
            val workers = List(concurrency.coerceAtLeast(1)) {
                launch(Dispatchers.IO) {
                    for ((address, port) in tasks) probe(address, port, countProgress = true)
                }
            }
            for (target in targets) tasks.send(target)
            tasks.close()
            workers.joinAll()
            mdnsJob.join()

            val arpResult = withContext(Dispatchers.IO) { arpTableReader.read(network.interfaceName) }
            arpResult.onSuccess { entries ->
                entries.filter { it.address in seenAddresses }.forEach { entry ->
                    send(DiscoveryEvent.Evidence(
                        DiscoveryEvidence.Arp(
                            address = entry.address,
                            macAddress = entry.macAddress,
                            vendor = macVendorResolver.vendorFor(entry.macAddress),
                        ),
                    ))
                }
            }.onFailure {
                send(DiscoveryEvent.Notice("系统限制，无法读取 ARP/MAC；不影响 SSH 扫描结果"))
            }
            send(DiscoveryEvent.Completed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            tcpProbe.cancel()
            mdnsDiscovery.cancel()
            activeJob.compareAndSet(job, null)
        }
    }

    override fun cancel() {
        activeJob.getAndSet(null)?.cancel()
        tcpProbe.cancel()
        mdnsDiscovery.cancel()
    }
}
