package com.yang136.sshhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yang136.sshhelper.AppContainer
import com.yang136.sshhelper.scanner.PortProbeResult
import com.yang136.sshhelper.scanner.PortScanEvent
import com.yang136.sshhelper.scanner.PortScanNetwork
import com.yang136.sshhelper.scanner.PortScanRequest
import com.yang136.sshhelper.scanner.PortScanSummary
import com.yang136.sshhelper.scanner.PortScanner
import com.yang136.sshhelper.scanner.PortState
import com.yang136.sshhelper.scanner.commonPortScanInput
import com.yang136.sshhelper.scanner.parsePortScanList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PortScannerStatus { LOADING, IDLE, SCANNING, COMPLETED, CANCELLED, ERROR }

data class PortScannerUiState(
    val networks: List<PortScanNetwork> = emptyList(),
    val selectedNetworkId: String? = null,
    val targetInput: String = "",
    val portsInput: String = commonPortScanInput(),
    val resolvedAddresses: List<String> = emptyList(),
    val selectedAddress: String? = null,
    val status: PortScannerStatus = PortScannerStatus.LOADING,
    val completed: Int = 0,
    val total: Int = 0,
    val openPorts: List<PortProbeResult> = emptyList(),
    val refused: Int = 0,
    val timeoutFiltered: Int = 0,
    val unreachable: Int = 0,
    val errors: Int = 0,
    val summary: PortScanSummary? = null,
    val rescanningPort: Int? = null,
    val error: String? = null,
) {
    val canStart: Boolean get() = status != PortScannerStatus.SCANNING && selectedNetworkId != null && targetInput.isNotBlank()
}

class PortScannerViewModel(
    private val scanner: PortScanner,
    initialTarget: String = "",
    private val initialNetworkId: String = "",
    preselectFullScan: Boolean = false,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        PortScannerUiState(
            targetInput = initialTarget,
            portsInput = if (preselectFullScan && initialTarget.isNotBlank()) "1-65535" else commonPortScanInput(),
        ),
    )
    val state: StateFlow<PortScannerUiState> = mutableState.asStateFlow()
    private var scanJob: Job? = null

    init { refreshNetworks() }

    fun refreshNetworks() {
        if (mutableState.value.status == PortScannerStatus.SCANNING) return
        viewModelScope.launch {
            val networks = runCatching { scanner.availableNetworks() }.getOrDefault(emptyList())
            // 从局域网详情跳转时会传入 initialNetworkId，这里始终优先选中它；
            // 但只预填参数，不自动开始扫描。
            val requestedNetworkId = initialNetworkId.takeIf(String::isNotBlank)
            val requestedNetworkMissing = requestedNetworkId != null && networks.none { it.id == requestedNetworkId }
            val selected = networks.firstOrNull { it.id == requestedNetworkId }
                ?: networks.firstOrNull { it.id == mutableState.value.selectedNetworkId }
                ?: networks.firstOrNull(PortScanNetwork::isDefault) ?: networks.firstOrNull()
            mutableState.update {
                it.copy(
                    networks = networks,
                    selectedNetworkId = selected?.id,
                    status = PortScannerStatus.IDLE,
                    error = when {
                        selected == null -> "未找到可用网络"
                        requestedNetworkMissing -> "发现设备时使用的网络已断开，请重新选择网络后开始扫描"
                        else -> null
                    },
                )
            }
        }
    }

    fun selectNetwork(id: String) = mutableState.update { if (it.status == PortScannerStatus.SCANNING) it else it.copy(selectedNetworkId = id, error = null) }
    fun updateTarget(value: String) = mutableState.update { if (it.status == PortScannerStatus.SCANNING) it else it.copy(targetInput = value, resolvedAddresses = emptyList(), selectedAddress = null, error = null) }
    fun updatePorts(value: String) = mutableState.update { if (it.status == PortScannerStatus.SCANNING) it else it.copy(portsInput = value, error = null) }
    fun useCommonPorts() = updatePorts(commonPortScanInput())
    fun useAllPorts() = updatePorts("1-65535")
    fun selectAddress(value: String) = mutableState.update { if (it.status == PortScannerStatus.SCANNING) it else it.copy(selectedAddress = value) }

    fun startScan() {
        val current = mutableState.value
        val ports = parsePortScanList(current.portsInput).getOrElse { failure ->
            mutableState.update { it.copy(error = failure.message ?: "端口列表格式不正确") }
            return
        }
        startScan(ports)
    }

    fun rescanOpenPorts() {
        val ports = mutableState.value.openPorts.mapTo(sortedSetOf(), PortProbeResult::port)
        if (ports.isEmpty()) {
            mutableState.update { it.copy(error = "没有可重新扫描的开放端口") }
            return
        }
        startScan(ports)
    }

    fun rescanPort(port: Int) {
        val current = mutableState.value
        if (current.status == PortScannerStatus.SCANNING) return
        val request = scanRequest(current, setOf(port)) ?: return
        scanner.cancel()
        scanJob?.cancel()
        mutableState.update { it.copy(status = PortScannerStatus.SCANNING, rescanningPort = port, error = null) }
        scanJob = viewModelScope.launch {
            try {
                scanner.scan(request).collect(::handleEvent)
            } catch (_: CancellationException) {
                // cancelScan owns the visible state.
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(
                        status = PortScannerStatus.ERROR,
                        rescanningPort = null,
                        error = failure.message ?: "端口重新探测失败",
                    )
                }
            }
        }
    }

    private fun startScan(ports: Set<Int>) {
        val current = mutableState.value
        if (current.status == PortScannerStatus.SCANNING) return
        val request = scanRequest(current, ports) ?: return
        scanner.cancel()
        scanJob?.cancel()
        mutableState.update {
            it.copy(status = PortScannerStatus.SCANNING, completed = 0, total = ports.size, openPorts = emptyList(), refused = 0, timeoutFiltered = 0, unreachable = 0, errors = 0, summary = null, rescanningPort = null, error = null)
        }
        scanJob = viewModelScope.launch {
            try {
                scanner.scan(request).collect(::handleEvent)
            } catch (_: CancellationException) {
                // cancelScan owns the visible state.
            } catch (failure: Throwable) {
                mutableState.update { it.copy(status = PortScannerStatus.ERROR, error = failure.message ?: "端口扫描失败") }
            }
        }
    }

    private fun scanRequest(current: PortScannerUiState, ports: Set<Int>): PortScanRequest? {
        val networkId = current.selectedNetworkId ?: run {
            mutableState.update { it.copy(error = "请选择网络") }
            return null
        }
        if (current.targetInput.isBlank()) {
            mutableState.update { it.copy(error = "请输入目标地址") }
            return null
        }
        return PortScanRequest(networkId, current.targetInput.trim(), ports, current.selectedAddress)
    }

    private fun handleRescanEvent(port: Int, event: PortScanEvent) {
        when (event) {
            is PortScanEvent.Resolved -> mutableState.update {
                it.copy(resolvedAddresses = event.addresses, selectedAddress = event.selectedAddress)
            }
            is PortScanEvent.Result -> mutableState.update { state ->
                val remaining = state.openPorts.filterNot { it.port == port }
                val openPorts = if (event.result.state == PortState.OPEN) {
                    (remaining + event.result).sortedBy(PortProbeResult::port)
                } else {
                    remaining
                }
                state.copy(
                    openPorts = openPorts,
                    error = if (event.result.state == PortState.OPEN) null else {
                        "TCP $port 重新探测：${event.result.message ?: event.result.state.name}"
                    },
                )
            }
            is PortScanEvent.Completed -> mutableState.update {
                it.copy(status = PortScannerStatus.COMPLETED, rescanningPort = null)
            }
            is PortScanEvent.Started, is PortScanEvent.Progress -> Unit
        }
    }

    fun cancelScan() {
        scanner.cancel()
        scanJob?.cancel()
        scanJob = null
        mutableState.update { it.copy(status = PortScannerStatus.CANCELLED, rescanningPort = null) }
    }

    private fun handleEvent(event: PortScanEvent) {
        mutableState.value.rescanningPort?.let { port ->
            handleRescanEvent(port, event)
            return
        }
        when (event) {
            is PortScanEvent.Resolved -> mutableState.update { it.copy(resolvedAddresses = event.addresses, selectedAddress = event.selectedAddress) }
            is PortScanEvent.Started -> mutableState.update { it.copy(total = event.total) }
            is PortScanEvent.Progress -> mutableState.update { it.copy(completed = event.completed, total = event.total) }
            is PortScanEvent.Result -> mutableState.update { state ->
                when (event.result.state) {
                    PortState.OPEN -> state.copy(openPorts = (state.openPorts + event.result).sortedBy(PortProbeResult::port))
                    PortState.REFUSED -> state.copy(refused = state.refused + 1)
                    PortState.TIMEOUT_FILTERED -> state.copy(timeoutFiltered = state.timeoutFiltered + 1)
                    PortState.UNREACHABLE -> state.copy(unreachable = state.unreachable + 1)
                    PortState.ERROR -> state.copy(errors = state.errors + 1)
                }
            }
            is PortScanEvent.Completed -> mutableState.update {
                it.copy(
                    status = PortScannerStatus.COMPLETED,
                    completed = event.summary.total,
                    summary = event.summary,
                    refused = event.summary.refused,
                    timeoutFiltered = event.summary.timeoutFiltered,
                    unreachable = event.summary.unreachable,
                    errors = event.summary.errors,
                    rescanningPort = null,
                )
            }
        }
    }

    override fun onCleared() { scanner.cancel(); scanJob?.cancel() }

    companion object {
        fun factory(
            container: AppContainer,
            initialTarget: String = "",
            initialNetworkId: String = "",
            preselectFullScan: Boolean = false,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PortScannerViewModel(
                scanner = container.portScanner,
                initialTarget = initialTarget,
                initialNetworkId = initialNetworkId,
                preselectFullScan = preselectFullScan,
            ) as T
        }
    }
}
