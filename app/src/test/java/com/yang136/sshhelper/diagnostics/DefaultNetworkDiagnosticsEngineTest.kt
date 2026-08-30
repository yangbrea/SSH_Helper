package com.yang136.sshhelper.diagnostics

import com.yang136.sshhelper.discovery.SshBanner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkDiagnosticsEngineTest {
    @Test
    fun emitsFiveSamplesAndReusesFirstReachableAddress() = runTest {
        val backend = FakeBackend().apply {
            addresses = listOf("2001:db8::1", "192.0.2.20")
            results["2001:db8::1"] = ArrayDeque<DiagnosticConnectResult>().apply {
                add(DiagnosticConnectResult.Failed(DiagnosticFailureKind.UNREACHABLE, "unreachable"))
            }
            results["192.0.2.20"] = ArrayDeque<DiagnosticConnectResult>().apply {
                repeat(5) { index ->
                    add(DiagnosticConnectResult.Connected(
                        DiagnosticConnection(
                            "192.0.2.20",
                            10.0 + index,
                            if (index == 0) SshBanner("SSH-2.0-Test", "2.0", "Test") else null,
                        ),
                    ))
                }
            }
        }
        var clock = 0L
        val engine = DefaultNetworkDiagnosticsEngine(backend) { clock.also { clock += 1_000_000 } }
        val events = engine.diagnose(
            DiagnosticRequest("n1", "host.example", 22, sampleIntervalMillis = 0),
        ).toList()

        assertEquals(5, events.filterIsInstance<DiagnosticEvent.Sampled>().size)
        val report = events.filterIsInstance<DiagnosticEvent.Completed>().single().report
        assertEquals(DiagnosticConclusionKind.HEALTHY, report.conclusion.kind)
        assertEquals("Test", report.banner?.softwareVersion)
        assertEquals(10.0, report.minimumMillis!!, 0.001)
        assertEquals(14.0, report.maximumMillis!!, 0.001)
        assertEquals(listOf("2001:db8::1", "192.0.2.20") + List(4) { "192.0.2.20" }, backend.connectAddresses)
        assertEquals(1, backend.bannerAttempts)
        assertTrue(backend.cancelled)
    }

    @Test
    fun reportsMissingNetworkWithoutResolving() = runTest {
        val backend = FakeBackend().apply { currentSnapshot = null }
        val events = DefaultNetworkDiagnosticsEngine(backend)
            .diagnose(DiagnosticRequest("gone", "host", 22, sampleIntervalMillis = 0))
            .toList()

        val failed = events.single() as DiagnosticEvent.Failed
        assertEquals(DiagnosticConclusionKind.NO_NETWORK, failed.conclusion.kind)
        assertEquals(0, backend.resolveCalls)
    }

    @Test
    fun classifiesDnsFailureAndAlwaysCancelsBackend() = runTest {
        val backend = FakeBackend().apply { resolveFailure = java.net.UnknownHostException("bad.invalid") }
        val events = DefaultNetworkDiagnosticsEngine(backend)
            .diagnose(DiagnosticRequest("n1", "bad.invalid", 22, sampleIntervalMillis = 0))
            .toList()

        assertEquals(DiagnosticConclusionKind.DNS_FAILURE, (events.last() as DiagnosticEvent.Failed).conclusion.kind)
        assertTrue(backend.cancelled)
    }

    @Test
    fun classifiesPermissionFailuresFromSamples() = runTest {
        val backend = FakeBackend().apply {
            results["192.0.2.20"] = ArrayDeque<DiagnosticConnectResult>().apply {
                repeat(5) { add(DiagnosticConnectResult.Failed(DiagnosticFailureKind.PERMISSION, "denied")) }
            }
        }
        val events = DefaultNetworkDiagnosticsEngine(backend)
            .diagnose(DiagnosticRequest("n1", "host", 22, sampleIntervalMillis = 0))
            .toList()
        val report = (events.last() as DiagnosticEvent.Completed).report
        assertEquals(DiagnosticConclusionKind.PERMISSION_DENIED, report.conclusion.kind)
    }
}

private class FakeBackend : DiagnosticBackend {
    val network = DiagnosticNetwork("n1", "Wi-Fi · wlan0", setOf(DiagnosticTransport.WIFI), true)
    var currentSnapshot: NetworkSnapshot? = NetworkSnapshot(
        network, "wlan0", listOf("192.0.2.2/24"), listOf("192.0.2.1"), listOf("192.0.2.1"),
        1500, false, null, null, false, true, true, false,
    )
    var addresses = listOf("192.0.2.20")
    var resolveFailure: Throwable? = null
    var resolveCalls = 0
    val results = mutableMapOf<String, ArrayDeque<DiagnosticConnectResult>>()
    val connectAddresses = mutableListOf<String>()
    var bannerAttempts = 0
    var cancelled = false

    override suspend fun availableNetworks() = listOf(network)
    override suspend fun snapshot(networkId: String) = currentSnapshot
    override suspend fun resolve(networkId: String, hostname: String): List<String> {
        resolveCalls++
        resolveFailure?.let { throw it }
        return addresses
    }

    override suspend fun connect(
        networkId: String,
        address: String,
        port: Int,
        timeoutMillis: Int,
        readSshBanner: Boolean,
    ): DiagnosticConnectResult {
        connectAddresses += address
        val result = results[address]?.removeFirstOrNull()
            ?: DiagnosticConnectResult.Connected(DiagnosticConnection(address, 8.0, null))
        if (readSshBanner && result is DiagnosticConnectResult.Connected) bannerAttempts++
        return result
    }

    override fun cancel() { cancelled = true }
}
