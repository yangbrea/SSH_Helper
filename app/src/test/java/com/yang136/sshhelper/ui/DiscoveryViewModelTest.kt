package com.yang136.sshhelper.ui

import com.yang136.sshhelper.discovery.DiscoveryEvent
import com.yang136.sshhelper.discovery.DiscoveryEvidence
import com.yang136.sshhelper.discovery.DiscoveryStatus
import com.yang136.sshhelper.discovery.LanDiscoveryEngine
import com.yang136.sshhelper.discovery.LanNetwork
import com.yang136.sshhelper.discovery.NetworkEnvironment
import com.yang136.sshhelper.discovery.ScanRequest
import com.yang136.sshhelper.discovery.SshBanner
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

    private class FakeNetworks(private val values: List<LanNetwork>) : NetworkEnvironment {
        override suspend fun availableNetworks() = values
    }

    private class FakeEngine(private val events: Flow<DiscoveryEvent>) : LanDiscoveryEngine {
        var started = 0
        var cancelled = false
        override fun scan(request: ScanRequest): Flow<DiscoveryEvent> {
            started++
            return events
        }
        override fun cancel() { cancelled = true }
    }
}
