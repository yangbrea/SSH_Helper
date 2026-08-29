package com.yang136.sshhelper.discovery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLanDiscoveryEngineTest {
    private val network = LanNetwork("n1", "Wi-Fi", "wlan0", "192.168.1.1", 30)

    @Test
    fun combinesTcpMdnsAndArpAndProbesAdvertisedPort() = runTest {
        val banner = SshBanner("SSH-2.0-OpenSSH_9.9", "2.0", "OpenSSH_9.9")
        val tcp = FakeTcpProbe(mapOf(
            "192.168.1.2:22" to TcpProbeResult(banner),
            "192.168.1.2:2222" to TcpProbeResult(banner),
        ))
        val mdns = FakeMdnsDiscovery(flowOf(MdnsService("192.168.1.2", 2222, "NAS", "_ssh._tcp.")))
        val arp = FakeArpReader(Result.success(listOf(ArpEntry("192.168.1.2", "00:11:22:33:44:55"))))
        val engine = DefaultLanDiscoveryEngine(
            FakeNetworkEnvironment(network), tcp, mdns, arp,
            OuiIndex.parse(sequenceOf("001122/24\tExample")),
            concurrency = 2,
            mdnsWindowMillis = 1_000,
        )

        val events = engine.scan(
            ScanRequest("n1", Ipv4Cidr.parse("192.168.1.0/30").getOrThrow(), setOf(22), "192.168.1.1"),
        ).toList()

        assertEquals(DiscoveryEvent.Started(1), events.first())
        assertTrue(events.any { it is DiscoveryEvent.Evidence && it.value is DiscoveryEvidence.Mdns })
        assertTrue(events.any { event ->
            val value = (event as? DiscoveryEvent.Evidence)?.value as? DiscoveryEvidence.Tcp
            value?.port == 2222
        })
        assertTrue(events.any { event ->
            val value = (event as? DiscoveryEvent.Evidence)?.value as? DiscoveryEvidence.Arp
            value?.vendor == "Example"
        })
        assertEquals(DiscoveryEvent.Completed, events.last())
        assertTrue(tcp.cancelled)
        assertTrue(mdns.cancelled)
    }

    @Test
    fun limitsConcurrentTcpProbes() = runTest {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val tcp = object : TcpSshProbe {
            override suspend fun probe(networkId: String, address: String, port: Int): TcpProbeResult? {
                val now = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, now) }
                delay(20)
                active.decrementAndGet()
                return null
            }
            override fun cancel() = Unit
        }
        val engine = DefaultLanDiscoveryEngine(
            FakeNetworkEnvironment(network.copy(ipv4Address = "192.168.1.1", prefixLength = 29)),
            tcp,
            FakeMdnsDiscovery(flowOf()),
            FakeArpReader(Result.success(emptyList())),
            OuiIndex.Empty,
            concurrency = 2,
            mdnsWindowMillis = 1,
        )

        engine.scan(
            ScanRequest("n1", Ipv4Cidr.parse("192.168.1.0/29").getOrThrow(), setOf(22), "192.168.1.1"),
        ).toList()

        assertTrue(maximum.get() <= 2)
        assertTrue(maximum.get() > 0)
    }

    @Test
    fun mdnsFailureIsNonFatal() = runTest {
        val failingMdns = object : MdnsDiscovery {
            override fun discover(networkId: String): Flow<MdnsService> = kotlinx.coroutines.flow.flow {
                error("NSD unavailable")
            }
            override fun cancel() = Unit
        }
        val engine = DefaultLanDiscoveryEngine(
            FakeNetworkEnvironment(network), FakeTcpProbe(emptyMap()), failingMdns,
            FakeArpReader(Result.failure(SecurityException("denied"))), OuiIndex.Empty,
            concurrency = 1,
            mdnsWindowMillis = 100,
        )

        val events = engine.scan(
            ScanRequest("n1", Ipv4Cidr.parse("192.168.1.0/30").getOrThrow(), setOf(22), "192.168.1.1"),
        ).toList()

        assertTrue(events.filterIsInstance<DiscoveryEvent.Notice>().any { it.message.contains("mDNS") })
        assertTrue(events.filterIsInstance<DiscoveryEvent.Notice>().any { it.message.contains("ARP") })
        assertEquals(DiscoveryEvent.Completed, events.last())
    }

    private class FakeNetworkEnvironment(private val network: LanNetwork) : NetworkEnvironment {
        override suspend fun availableNetworks() = listOf(network)
    }

    private class FakeTcpProbe(private val results: Map<String, TcpProbeResult>) : TcpSshProbe {
        val probed = ConcurrentHashMap.newKeySet<String>()
        var cancelled = false
        override suspend fun probe(networkId: String, address: String, port: Int): TcpProbeResult? {
            probed += "$address:$port"
            return results["$address:$port"]
        }
        override fun cancel() { cancelled = true }
    }

    private class FakeMdnsDiscovery(private val services: Flow<MdnsService>) : MdnsDiscovery {
        var cancelled = false
        override fun discover(networkId: String) = services
        override fun cancel() { cancelled = true }
    }

    private class FakeArpReader(private val result: Result<List<ArpEntry>>) : ArpTableReader {
        override suspend fun read(interfaceName: String) = result
    }
}
