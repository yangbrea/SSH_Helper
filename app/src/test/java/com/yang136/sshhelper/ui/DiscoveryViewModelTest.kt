package com.yang136.sshhelper.ui

import com.yang136.sshhelper.discovery.DiscoveryEvent
import com.yang136.sshhelper.discovery.DiscoveryEvidence
import com.yang136.sshhelper.discovery.DiscoveryStatus
import com.yang136.sshhelper.discovery.DeviceDescription
import com.yang136.sshhelper.discovery.DeviceDescriptionRepository
import com.yang136.sshhelper.discovery.DiscoveredService
import com.yang136.sshhelper.discovery.LanDiscoveryEngine
import com.yang136.sshhelper.discovery.LanNetwork
import com.yang136.sshhelper.discovery.NetworkEnvironment
import com.yang136.sshhelper.discovery.ScanRequest
import com.yang136.sshhelper.discovery.ScanMode
import com.yang136.sshhelper.discovery.ServiceKind
import com.yang136.sshhelper.discovery.SshBanner
import com.yang136.sshhelper.discovery.SsdpRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val network = LanNetwork("n1", "Wi-Fi", "wlan0", "192.168.1.10", 24)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsNetworkAndReducesScanEvents() = runTest(dispatcher) {
        val banner = SshBanner("SSH-2.0-Test", "2.0", "Test")
        val engine = FakeEngine(flowOf(
            DiscoveryEvent.Started(1),
            DiscoveryEvent.Evidence(DiscoveryEvidence.Tcp("192.168.1.20", 22, banner)),
            DiscoveryEvent.Progress(1, 1),
            DiscoveryEvent.Completed,
        ))
        val vm = DiscoveryViewModel(FakeNetworks(listOf(network)), engine)
        advanceUntilIdle()

        assertEquals("192.168.1.0/24", vm.state.value.cidrInput)
        vm.startScan()
        advanceUntilIdle()

        assertEquals(DiscoveryStatus.COMPLETED, vm.state.value.status)
        assertEquals(1, vm.state.value.devices.size)
        assertEquals("192.168.1.20", vm.state.value.devices.single().address)
        assertEquals(1, vm.state.value.completedProbes)
    }

    @Test
    fun rejectsPublicCidrWithoutStartingEngine() = runTest(dispatcher) {
        val engine = FakeEngine(flowOf(DiscoveryEvent.Completed))
        val vm = DiscoveryViewModel(FakeNetworks(listOf(network)), engine)
        advanceUntilIdle()
        vm.updateCidr("8.8.8.0/24")

        vm.startScan()
        advanceUntilIdle()

        assertTrue(vm.state.value.error!!.contains("私有"))
        assertEquals(0, engine.started)
    }

    @Test
    fun reportsNoLanAndCancelsActiveEngine() = runTest(dispatcher) {
        val engine = FakeEngine(flow { kotlinx.coroutines.awaitCancellation() })
        val vm = DiscoveryViewModel(FakeNetworks(emptyList()), engine)
        advanceUntilIdle()
        assertEquals(DiscoveryStatus.NO_NETWORK, vm.state.value.status)

        vm.cancelScan()
        assertTrue(engine.cancelled)
        assertEquals(DiscoveryStatus.CANCELLED, vm.state.value.status)
    }

    @Test
    fun modeSwitchClearsResultsAndGeneralModeShowsArpOnlyDevices() = runTest(dispatcher) {
        val engine = FakeEngine(flowOf(
            DiscoveryEvent.Evidence(
                DiscoveryEvidence.Arp("192.168.1.50", "00:11:22:33:44:55", "Example"),
            ),
            DiscoveryEvent.Completed,
        ))
        val vm = DiscoveryViewModel(FakeNetworks(listOf(network)), engine)
        advanceUntilIdle()

        vm.selectMode(ScanMode.GENERAL)
        assertEquals("", vm.state.value.portsInput)
        vm.startScan()
        advanceUntilIdle()

        assertEquals(ScanMode.GENERAL, engine.lastRequest?.mode)
        assertEquals(1, vm.state.value.devices.size)
        assertTrue(vm.state.value.devices.single().services.isEmpty())

        vm.selectMode(ScanMode.SSH)
        assertEquals("22", vm.state.value.portsInput)
        assertTrue(vm.state.value.devices.isEmpty())
    }

    @Test
    fun modeCannotChangeDuringScanAndGeneralPortsAreLimited() = runTest(dispatcher) {
        val engine = FakeEngine(flow { kotlinx.coroutines.awaitCancellation() })
        val vm = DiscoveryViewModel(FakeNetworks(listOf(network)), engine)
        advanceUntilIdle()
        vm.selectMode(ScanMode.GENERAL)
        vm.updatePorts("1,2,3,4,5")
        vm.startScan()
        assertTrue(vm.state.value.error!!.contains("最多"))
        assertEquals(0, engine.started)

        vm.updatePorts("")
        vm.startScan()
        advanceUntilIdle()
        vm.selectMode(ScanMode.SSH)
        assertEquals(ScanMode.GENERAL, vm.state.value.mode)
        vm.cancelScan()
    }

    @Test
    fun detailsLoadDescriptionOnceAndMergePreferredName() = runTest(dispatcher) {
        val record = SsdpRecord(
            "192.168.1.60",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            location = "http://192.168.1.60/device.xml",
        )
        val engine = FakeEngine(flowOf(
            DiscoveryEvent.Evidence(DiscoveryEvidence.Ssdp(record)),
            DiscoveryEvent.Completed,
        ))
        val descriptions = FakeDescriptions(DeviceDescription(friendlyName = "Living Room", modelName = "Player"))
        val vm = DiscoveryViewModel(FakeNetworks(listOf(network)), engine, descriptions)
        advanceUntilIdle()
        vm.selectMode(ScanMode.GENERAL)
        vm.startScan()
        advanceUntilIdle()

        vm.openDetails("192.168.1.60")
        advanceUntilIdle()
        assertEquals("Living Room", vm.state.value.selectedDevice?.displayName)
        assertEquals(1, descriptions.loads)

        vm.openDetails("192.168.1.60")
        advanceUntilIdle()
        assertEquals(1, descriptions.loads)
        vm.closeDetails()
        assertNull(vm.state.value.selectedDevice)
    }

    @Test
    fun webUrlsOnlyUseLiteralDeviceAddressAndKnownWebServices() {
        assertEquals(
            "http://192.168.1.8:8080/",
            webUrlFor("192.168.1.8", DiscoveredService(8080, ServiceKind.HTTP)),
        )
        assertEquals(
            "https://192.168.1.8:8443/",
            webUrlFor("192.168.1.8", DiscoveredService(8443, ServiceKind.HTTPS)),
        )
        assertNull(webUrlFor("example.com", DiscoveredService(80, ServiceKind.HTTP)))
        assertNull(webUrlFor("192.168.1.8", DiscoveredService(22, ServiceKind.SSH)))
    }

    private class FakeNetworks(private val values: List<LanNetwork>) : NetworkEnvironment {
        override suspend fun availableNetworks() = values
    }

    private class FakeEngine(private val events: Flow<DiscoveryEvent>) : LanDiscoveryEngine {
        var started = 0
        var cancelled = false
        var lastRequest: ScanRequest? = null
        override fun scan(request: ScanRequest): Flow<DiscoveryEvent> {
            started++
            lastRequest = request
            return events
        }
        override fun cancel() { cancelled = true }
    }

    private class FakeDescriptions(private val value: DeviceDescription) : DeviceDescriptionRepository {
        var loads = 0
        override suspend fun load(
            networkId: String,
            address: String,
            location: String,
        ): Result<DeviceDescription> {
            loads++
            return Result.success(value)
        }
        override fun clear() = Unit
    }
}
