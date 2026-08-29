package com.yang136.sshhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yang136.sshhelper.AppContainer
import com.yang136.sshhelper.discovery.DEFAULT_SSH_PORT
import com.yang136.sshhelper.discovery.DeviceDescriptionRepository
import com.yang136.sshhelper.discovery.DiscoveredDevice
import com.yang136.sshhelper.discovery.DiscoveredService
import com.yang136.sshhelper.discovery.DiscoveryEvent
import com.yang136.sshhelper.discovery.DiscoveryEvidence
import com.yang136.sshhelper.discovery.DiscoveryReducer
import com.yang136.sshhelper.discovery.DiscoveryStatus
import com.yang136.sshhelper.discovery.Ipv4Cidr
import com.yang136.sshhelper.discovery.LanDiscoveryEngine
import com.yang136.sshhelper.discovery.LanNetwork
import com.yang136.sshhelper.discovery.NetworkEnvironment
import com.yang136.sshhelper.discovery.NoOpDeviceDescriptionRepository
import com.yang136.sshhelper.discovery.ScanMode
import com.yang136.sshhelper.discovery.ScanRequest
import com.yang136.sshhelper.discovery.ServiceKind
import com.yang136.sshhelper.discovery.TransportProtocol
import com.yang136.sshhelper.discovery.parseGeneralPortList
import com.yang136.sshhelper.discovery.parseIpv4
import com.yang136.sshhelper.discovery.parsePortList
import com.yang136.sshhelper.discovery.validateScanCidr
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val mode: ScanMode = ScanMode.SSH,
    val networks: List<LanNetwork> = emptyList(),
    val selectedNetworkId: String? = null,
    val cidrInput: String = "",
    val portsInput: String = DEFAULT_SSH_PORT.toString(),
    val status: DiscoveryStatus = DiscoveryStatus.IDLE,
    val completedProbes: Int = 0,
    val totalProbes: Int = 0,
    val devices: List<DiscoveredDevice> = emptyList(),
    val selectedDetailAddress: String? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val notice: String? = null,
    val error: String? = null,
) {
    val selectedDevice: DiscoveredDevice?
        get() = devices.firstOrNull { it.address == selectedDetailAddress }
}

class DiscoveryViewModel(
    private val networkEnvironment: NetworkEnvironment,
    private val discoveryEngine: LanDiscoveryEngine,
    private val descriptionRepository: DeviceDescriptionRepository = NoOpDeviceDescriptionRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiscoveryUiState())
    val state: StateFlow<DiscoveryUiState> = mutableState.asStateFlow()
    private var scanJob: Job? = null
    private var detailJob: Job? = null
    private var deviceMap = emptyMap<String, DiscoveredDevice>()
    private val generation = AtomicInteger(0)

    init {
        refreshNetworks()
    }

    fun refreshNetworks() {
        if (mutableState.value.status == DiscoveryStatus.SCANNING) return
        viewModelScope.launch {
            val networks = runCatching { networkEnvironment.availableNetworks() }.getOrDefault(emptyList())
            val selected = networks.firstOrNull { it.id == mutableState.value.selectedNetworkId } ?: networks.firstOrNull()
            mutableState.update { current ->
                current.copy(
                    networks = networks,
                    selectedNetworkId = selected?.id,
                    cidrInput = selected?.let { Ipv4Cidr.defaultFor(it.ipv4Address, it.prefixLength).toString() }.orEmpty(),
                    status = if (networks.isEmpty()) DiscoveryStatus.NO_NETWORK else DiscoveryStatus.IDLE,
                    error = if (networks.isEmpty()) "未找到可用的 Wi-Fi 或以太网 IPv4 网络" else null,
                )
            }
        }
    }

    fun selectMode(mode: ScanMode) {
        if (mutableState.value.status == DiscoveryStatus.SCANNING || mutableState.value.mode == mode) return
        generation.incrementAndGet()
        detailJob?.cancel()
        descriptionRepository.clear()
        deviceMap = emptyMap()
        mutableState.update {
            it.copy(
                mode = mode,
                portsInput = if (mode == ScanMode.SSH) DEFAULT_SSH_PORT.toString() else "",
                status = if (it.networks.isEmpty()) DiscoveryStatus.NO_NETWORK else DiscoveryStatus.IDLE,
                devices = emptyList(),
                completedProbes = 0,
                totalProbes = 0,
                selectedDetailAddress = null,
                detailLoading = false,
                detailError = null,
                notice = null,
                error = null,
            )
        }
    }

    fun selectNetwork(id: String) {
        val network = mutableState.value.networks.firstOrNull { it.id == id } ?: return
        mutableState.update {
            it.copy(
                selectedNetworkId = id,
                cidrInput = Ipv4Cidr.defaultFor(network.ipv4Address, network.prefixLength).toString(),
                error = null,
            )
        }
    }

    fun updateCidr(value: String) = mutableState.update { it.copy(cidrInput = value, error = null) }
    fun updatePorts(value: String) = mutableState.update { it.copy(portsInput = value, error = null) }

    fun startScan() {
        val current = mutableState.value
        val network = current.networks.firstOrNull { it.id == current.selectedNetworkId }
        if (network == null) {
            mutableState.update { it.copy(status = DiscoveryStatus.NO_NETWORK, error = "所选局域网不可用") }
            return
        }
        val cidr = Ipv4Cidr.parse(current.cidrInput).getOrElse { failure ->
            mutableState.update { it.copy(error = failure.message ?: "扫描范围格式不正确") }
            return
        }
        validateScanCidr(cidr)?.let { error ->
            mutableState.update { it.copy(error = error) }
            return
        }
        val ports = when (current.mode) {
            ScanMode.SSH -> parsePortList(current.portsInput)
            ScanMode.GENERAL -> parseGeneralPortList(current.portsInput)
        }.getOrElse { failure ->
            mutableState.update { it.copy(error = failure.message ?: "端口列表格式不正确") }
            return
        }
        scanJob?.cancel()
        detailJob?.cancel()
        descriptionRepository.clear()
        val scanGeneration = generation.incrementAndGet()
        deviceMap = emptyMap()
        mutableState.update {
            it.copy(
                status = DiscoveryStatus.SCANNING,
                completedProbes = 0,
                totalProbes = 0,
                devices = emptyList(),
                selectedDetailAddress = null,
                detailLoading = false,
                detailError = null,
                notice = null,
                error = null,
            )
        }
        scanJob = viewModelScope.launch {
            try {
                discoveryEngine.scan(
                    ScanRequest(network.id, cidr, ports, network.ipv4Address, current.mode),
                ).collect { event ->
                    if (generation.get() == scanGeneration) handleEvent(network.id, event)
                }
            } catch (_: CancellationException) {
                // cancelScan owns the visible cancelled state; lifecycle cancellation needs no UI update.
            } catch (failure: Throwable) {
                if (generation.get() != scanGeneration) return@launch
                mutableState.update {
                    it.copy(
                        status = DiscoveryStatus.ERROR,
                        error = if (failure is SecurityException) {
                            "系统拒绝局域网访问，请检查附近设备或本地网络权限"
                        } else failure.message ?: "局域网扫描失败",
                    )
                }
            }
        }
    }

    fun cancelScan() {
        generation.incrementAndGet()
        discoveryEngine.cancel()
        scanJob?.cancel()
        scanJob = null
        detailJob?.cancel()
        descriptionRepository.clear()
        mutableState.update { it.copy(status = DiscoveryStatus.CANCELLED, detailLoading = false) }
    }

    fun openDetails(address: String) {
        val current = deviceMap[address] ?: return
        mutableState.update {
            it.copy(selectedDetailAddress = address, detailError = null, detailLoading = false)
        }
        if (mutableState.value.mode != ScanMode.GENERAL || current.description != null) return
        val locations = current.ssdpRecords.mapNotNull { it.location }.distinct()
        if (locations.isEmpty()) return
        val detailGeneration = generation.get()
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            mutableState.update { it.copy(detailLoading = true, detailError = null) }
            var lastFailure: Throwable? = null
            var loaded = false
            for (location in locations) {
                val result = descriptionRepository.load(current.networkId, current.address, location)
                result.onSuccess { description ->
                    if (generation.get() != detailGeneration || mutableState.value.selectedDetailAddress != address) return@launch
                    deviceMap = DiscoveryReducer.apply(
                        deviceMap,
                        current.networkId,
                        DiscoveryEvidence.Description(address, description),
                    )
                    publishDevices()
                    loaded = true
                }.onFailure { lastFailure = it }
                if (loaded) break
            }
            if (generation.get() == detailGeneration && mutableState.value.selectedDetailAddress == address) {
                mutableState.update {
                    it.copy(
                        detailLoading = false,
                        detailError = if (loaded) null else lastFailure?.message ?: "设备描述读取失败",
                    )
                }
            }
        }
    }

    fun closeDetails() {
        detailJob?.cancel()
        mutableState.update {
            it.copy(selectedDetailAddress = null, detailLoading = false, detailError = null)
        }
    }

    private fun handleEvent(networkId: String, event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.Started -> mutableState.update { it.copy(totalProbes = event.totalProbes) }
            is DiscoveryEvent.Progress -> mutableState.update {
                it.copy(completedProbes = event.completedProbes, totalProbes = event.totalProbes)
            }
            is DiscoveryEvent.Evidence -> {
                deviceMap = DiscoveryReducer.apply(deviceMap, networkId, event.value)
                publishDevices()
            }
            is DiscoveryEvent.Notice -> mutableState.update { it.copy(notice = event.message) }
            DiscoveryEvent.Completed -> mutableState.update { it.copy(status = DiscoveryStatus.COMPLETED) }
        }
    }

    private fun publishDevices() {
        val mode = mutableState.value.mode
        val visible = deviceMap.values
            .filter { mode == ScanMode.GENERAL || it.hasSsh }
            .sortedWith(
                compareByDescending<DiscoveredDevice> { it.classification.confidence }
                    .thenByDescending { it.bestConfidence }
                    .thenBy { it.displayName.orEmpty().lowercase() }
                    .thenBy { parseIpv4(it.address) },
            )
        mutableState.update { it.copy(devices = visible) }
    }

    override fun onCleared() {
        generation.incrementAndGet()
        discoveryEngine.cancel()
        detailJob?.cancel()
        descriptionRepository.clear()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoveryViewModel(
                container.networkEnvironment,
                container.lanDiscoveryEngine,
                container.deviceDescriptionRepository,
            ) as T
        }
    }
}

fun webUrlFor(address: String, service: DiscoveredService): String? {
    if (parseIpv4(address) == null || service.transport != TransportProtocol.TCP) return null
    val scheme = when {
        service.kind == ServiceKind.HTTPS || service.port in setOf(443, 8443) -> "https"
        service.kind == ServiceKind.HTTP || service.port in setOf(80, 8000, 8080) -> "http"
        else -> return null
    }
    return "$scheme://$address:${service.port}/"
}
