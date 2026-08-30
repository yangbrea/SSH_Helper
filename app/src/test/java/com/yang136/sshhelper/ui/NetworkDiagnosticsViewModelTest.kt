package com.yang136.sshhelper.ui

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.ProxyType
import com.yang136.sshhelper.diagnostics.DiagnosticConclusion
import com.yang136.sshhelper.diagnostics.DiagnosticConclusionKind
import com.yang136.sshhelper.diagnostics.DiagnosticEvent
import com.yang136.sshhelper.diagnostics.DiagnosticNetwork
import com.yang136.sshhelper.diagnostics.DiagnosticReport
import com.yang136.sshhelper.diagnostics.DiagnosticRequest
import com.yang136.sshhelper.diagnostics.DiagnosticSample
import com.yang136.sshhelper.diagnostics.DiagnosticTransport
import com.yang136.sshhelper.diagnostics.NetworkDiagnosticsEngine
import com.yang136.sshhelper.diagnostics.NetworkSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkDiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val network = DiagnosticNetwork("n1", "Wi-Fi · wlan0", setOf(DiagnosticTransport.WIFI), true)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun invalidHostIdFallsBackToEditableGlobalTarget() = runTest(dispatcher) {
        val vm = NetworkDiagnosticsViewModel(FakeDiagnosticsEngine(network), 999) { emptyList() }
        advanceUntilIdle()

        assertEquals(NetworkDiagnosticsStatus.IDLE, vm.state.value.status)
        assertFalse(vm.state.value.targetLocked)
        assertEquals("", vm.state.value.hostnameInput)
        assertEquals("22", vm.state.value.portInput)
        assertNull(vm.state.value.routeSummary)
    }

    @Test
    fun savedProxyTargetLocksFirstHopAndSkipsSshBanner() = runTest(dispatcher) {
        val target = host(1).copy(
            proxyType = ProxyType.SOCKS5,
            proxyHost = "proxy.example",
            proxyPort = 1080,
        )
        val engine = FakeDiagnosticsEngine(network)
        val vm = NetworkDiagnosticsViewModel(engine, target.id) { listOf(target) }
        advanceUntilIdle()

        assertTrue(vm.state.value.targetLocked)
        assertEquals("proxy.example", vm.state.value.hostnameInput)
        assertEquals("1080", vm.state.value.portInput)
        assertFalse(vm.state.value.expectsSsh)

        vm.startTest()
        advanceUntilIdle()
        assertFalse(engine.lastRequest!!.readSshBanner)
    }

    @Test
    fun reducesProgressAndCompletedReport() = runTest(dispatcher) {
        val engine = FakeDiagnosticsEngine(network)
        val request = DiagnosticRequest("n1", "example.com", 22)
        val samples = (1..5).map { DiagnosticSample.Success(it, "192.0.2.20", it.toDouble()) }
        val conclusion = DiagnosticConclusion(DiagnosticConclusionKind.HEALTHY, "正常", "稳定")
        val report = DiagnosticReport(request, snapshot(network), listOf("192.0.2.20"), 2.0, samples, null, conclusion)
        engine.events = flow {
            emit(DiagnosticEvent.Started(snapshot(network)))
            emit(DiagnosticEvent.DnsResolved(listOf("192.0.2.20"), 2.0))
            samples.forEach { emit(DiagnosticEvent.Sampled(it)) }
            emit(DiagnosticEvent.Completed(report))
        }
        val vm = NetworkDiagnosticsViewModel(engine) { emptyList() }
        advanceUntilIdle()
        vm.updateHostname("example.com")
        vm.startTest()
        advanceUntilIdle()

        assertEquals(NetworkDiagnosticsStatus.COMPLETED, vm.state.value.status)
        assertEquals(5, vm.state.value.completedSamples)
        assertEquals(listOf("192.0.2.20"), vm.state.value.resolvedAddresses)
        assertEquals(DiagnosticConclusionKind.HEALTHY, vm.state.value.conclusion?.kind)
    }

    @Test
    fun validatesInputAndCancelsRunningTest() = runTest(dispatcher) {
        val engine = FakeDiagnosticsEngine(network).apply { events = flow { awaitCancellation() } }
        val vm = NetworkDiagnosticsViewModel(engine) { emptyList() }
        advanceUntilIdle()
        vm.startTest()
        assertTrue(vm.state.value.error!!.contains("地址"))

        vm.updateHostname("192.0.2.20")
        vm.startTest()
        advanceUntilIdle()
        assertEquals(NetworkDiagnosticsStatus.RUNNING, vm.state.value.status)
        vm.cancelTest()
        advanceUntilIdle()

        assertEquals(NetworkDiagnosticsStatus.CANCELLED, vm.state.value.status)
        assertTrue(engine.cancelled > 0)
    }

    @Test
    fun selectingNetworkClearsPreviousResult() = runTest(dispatcher) {
        val second = DiagnosticNetwork("n2", "VPN · tun0", setOf(DiagnosticTransport.VPN), false)
        val engine = FakeDiagnosticsEngine(network, listOf(network, second))
        val vm = NetworkDiagnosticsViewModel(engine) { emptyList() }
        advanceUntilIdle()
        vm.updateHostname("host")
        vm.startTest()
        advanceUntilIdle()
        assertEquals(NetworkDiagnosticsStatus.COMPLETED, vm.state.value.status)

        vm.selectNetwork("n2")
        advanceUntilIdle()
        assertEquals("n2", vm.state.value.selectedNetworkId)
        assertEquals(NetworkDiagnosticsStatus.IDLE, vm.state.value.status)
        assertTrue(vm.state.value.samples.isEmpty())
    }

    @Test
    fun buildsStableNavigationRoutes() {
        assertEquals("diagnostics", networkDiagnosticsRoute())
        assertEquals("diagnostics?hostId=42", networkDiagnosticsRoute(42))
        assertEquals("diagnostics?hostId={hostId}", NETWORK_DIAGNOSTICS_ROUTE_PATTERN)
    }

    private fun host(id: Long) = HostProfile(
        id = id, name = "Server", hostname = "server.example", username = "user", authType = AuthType.PASSWORD,
    )
}

private class FakeDiagnosticsEngine(
    private val defaultNetwork: DiagnosticNetwork,
    private val networkList: List<DiagnosticNetwork> = listOf(defaultNetwork),
) : NetworkDiagnosticsEngine {
    var events: Flow<DiagnosticEvent>? = null
    var lastRequest: DiagnosticRequest? = null
    var cancelled = 0

    override suspend fun availableNetworks() = networkList
    override suspend fun snapshot(networkId: String): NetworkSnapshot? =
        networkList.firstOrNull { it.id == networkId }?.let(::snapshot)
    override fun diagnose(request: DiagnosticRequest): Flow<DiagnosticEvent> {
        lastRequest = request
        return events ?: run {
            val samples = (1..5).map { DiagnosticSample.Success(it, "192.0.2.20", 5.0) }
            val conclusion = DiagnosticConclusion(DiagnosticConclusionKind.NON_SSH_SERVICE, "可连接", "无 Banner")
            flowOf(DiagnosticEvent.Completed(
                DiagnosticReport(request, snapshot(defaultNetwork), listOf("192.0.2.20"), 1.0, samples, null, conclusion),
            ))
        }
    }
    override fun cancel() { cancelled++ }
}

private fun snapshot(network: DiagnosticNetwork) = NetworkSnapshot(
    network = network,
    interfaceName = network.label.substringAfter("·", "interface").trim(),
    addresses = listOf("192.0.2.2/24"),
    gateways = listOf("192.0.2.1"),
    dnsServers = listOf("192.0.2.1"),
    mtu = 1500,
    privateDnsActive = false,
    privateDnsServerName = null,
    httpProxy = null,
    metered = false,
    hasInternetCapability = true,
    validated = true,
    captivePortal = false,
)
