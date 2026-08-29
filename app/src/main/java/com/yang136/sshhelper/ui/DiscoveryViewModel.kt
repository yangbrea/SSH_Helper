package com.yang136.sshhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yang136.sshhelper.AppContainer
import com.yang136.sshhelper.discovery.DEFAULT_SSH_PORT
import com.yang136.sshhelper.discovery.DiscoveredSshDevice
import com.yang136.sshhelper.discovery.DiscoveryEvent
import com.yang136.sshhelper.discovery.DiscoveryReducer
import com.yang136.sshhelper.discovery.DiscoveryStatus
import com.yang136.sshhelper.discovery.Ipv4Cidr
import com.yang136.sshhelper.discovery.LanNetwork
import com.yang136.sshhelper.discovery.ScanRequest
import com.yang136.sshhelper.discovery.parseIpv4
import com.yang136.sshhelper.discovery.parsePortList
import com.yang136.sshhelper.discovery.validateScanCidr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val networks: List<LanNetwork> = emptyList(),
    val selectedNetworkId: String? = null,
    val cidrInput: String = "",
    val portsInput: String = DEFAULT_SSH_PORT.toString(),
    val status: DiscoveryStatus = DiscoveryStatus.IDLE,
    val completedProbes: Int = 0,
    val totalProbes: Int = 0,
    val devices: List<DiscoveredSshDevice> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
)

class DiscoveryViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(DiscoveryUiState())
    val state: StateFlow<DiscoveryUiState> = mutableState.asStateFlow()
    private var scanJob: Job? = null
    private var deviceMap = emptyMap<String, DiscoveredSshDevice>()

    init {
        refreshNetworks()
    }

    fun refreshNetworks() {
        if (mutableState.value.status == DiscoveryStatus.SCANNING) return
        viewModelScope.launch {
            val networks = runCatching { container.networkEnvironment.availableNetworks() }.getOrDefault(emptyList())
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
        val ports = parsePortList(current.portsInput).getOrElse { failure ->
            mutableState.update { it.copy(error = failure.message ?: "端口列表格式不正确") }
            return
        }
        scanJob?.cancel()
        deviceMap = emptyMap()
        mutableState.update {
            it.copy(
                status = DiscoveryStatus.SCANNING,
                completedProbes = 0,
                totalProbes = 0,
                devices = emptyList(),
                notice = null,
                error = null,
            )
        }
        scanJob = viewModelScope.launch {
            try {
                container.lanDiscoveryEngine.scan(
                    ScanRequest(network.id, cidr, ports, network.ipv4Address),
                ).collect { event -> handleEvent(network.id, event) }
            } catch (_: CancellationException) {
                // cancelScan owns the visible cancelled state; lifecycle cancellation needs no UI update.
            } catch (failure: Throwable) {
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
        container.lanDiscoveryEngine.cancel()
        scanJob?.cancel()
        scanJob = null
        mutableState.update { it.copy(status = DiscoveryStatus.CANCELLED) }
    }

    private fun handleEvent(networkId: String, event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.Started -> mutableState.update { it.copy(totalProbes = event.totalProbes) }
            is DiscoveryEvent.Progress -> mutableState.update {
                it.copy(completedProbes = event.completedProbes, totalProbes = event.totalProbes)
            }
            is DiscoveryEvent.Evidence -> {
                deviceMap = DiscoveryReducer.apply(deviceMap, networkId, event.value)
                val visible = deviceMap.values.filter { it.endpoints.isNotEmpty() }.sortedWith(
                    compareByDescending<DiscoveredSshDevice> { it.bestConfidence }
                        .thenBy { it.displayName.orEmpty().lowercase() }
                        .thenBy { parseIpv4(it.address) },
                )
                mutableState.update { it.copy(devices = visible) }
            }
            is DiscoveryEvent.Notice -> mutableState.update { it.copy(notice = event.message) }
            DiscoveryEvent.Completed -> mutableState.update { it.copy(status = DiscoveryStatus.COMPLETED) }
        }
    }

    override fun onCleared() {
        container.lanDiscoveryEngine.cancel()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoveryViewModel(container) as T
        }
    }
}
