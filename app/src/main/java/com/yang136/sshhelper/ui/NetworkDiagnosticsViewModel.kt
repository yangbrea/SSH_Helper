package com.yang136.sshhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yang136.sshhelper.AppContainer
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.diagnostics.DEFAULT_DIAGNOSTIC_PORT
import com.yang136.sshhelper.diagnostics.DEFAULT_DIAGNOSTIC_SAMPLES
import com.yang136.sshhelper.diagnostics.DiagnosticConclusion
import com.yang136.sshhelper.diagnostics.DiagnosticEndpointKind
import com.yang136.sshhelper.diagnostics.DiagnosticEvent
import com.yang136.sshhelper.diagnostics.DiagnosticNetwork
import com.yang136.sshhelper.diagnostics.DiagnosticReport
import com.yang136.sshhelper.diagnostics.DiagnosticRequest
import com.yang136.sshhelper.diagnostics.DiagnosticSample
import com.yang136.sshhelper.diagnostics.NetworkDiagnosticsEngine
import com.yang136.sshhelper.diagnostics.NetworkSnapshot
import com.yang136.sshhelper.diagnostics.SavedDiagnosticTarget
import com.yang136.sshhelper.diagnostics.diagnosticEndpointFor
import com.yang136.sshhelper.diagnostics.normalizeDiagnosticHostname
import com.yang136.sshhelper.diagnostics.validateDiagnosticTarget
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NetworkDiagnosticsStatus { LOADING, IDLE, RUNNING, COMPLETED, CANCELLED, NO_NETWORK, ERROR }

data class NetworkDiagnosticsUiState(
    val networks: List<DiagnosticNetwork> = emptyList(),
    val selectedNetworkId: String? = null,
    val snapshot: NetworkSnapshot? = null,
    val hostnameInput: String = "",
    val portInput: String = DEFAULT_DIAGNOSTIC_PORT.toString(),
    val savedTargetName: String? = null,
    val targetLabel: String = "SSH 目标",
    val routeSummary: String? = null,
    val routeLimitation: String? = null,
    val targetLocked: Boolean = false,
    val expectsSsh: Boolean = true,
    val status: NetworkDiagnosticsStatus = NetworkDiagnosticsStatus.LOADING,
    val resolvedAddresses: List<String> = emptyList(),
    val dnsDurationMillis: Double? = null,
    val samples: List<DiagnosticSample> = emptyList(),
    val report: DiagnosticReport? = null,
    val conclusion: DiagnosticConclusion? = null,
    val error: String? = null,
) {
    val completedSamples: Int get() = samples.size
    val canStart: Boolean
        get() = selectedNetworkId != null && status != NetworkDiagnosticsStatus.RUNNING &&
            validateDiagnosticTarget(hostnameInput, portInput) == null
}

class NetworkDiagnosticsViewModel(
    private val engine: NetworkDiagnosticsEngine,
    private val hostId: Long = 0,
    private val loadHosts: suspend () -> List<HostProfile>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(NetworkDiagnosticsUiState())
    val state: StateFlow<NetworkDiagnosticsUiState> = mutableState.asStateFlow()
    private val generation = AtomicInteger(0)
    private var testJob: Job? = null
    private var networkJob: Job? = null

    init {
        loadInitialState()
    }

    fun refreshNetworks() {
        if (mutableState.value.status == NetworkDiagnosticsStatus.RUNNING) return
        networkJob?.cancel()
        networkJob = viewModelScope.launch {
            val networks = runCatching { engine.availableNetworks() }.getOrDefault(emptyList())
            val selected = networks.firstOrNull { it.id == mutableState.value.selectedNetworkId }
                ?: networks.firstOrNull { it.isDefault }
                ?: networks.firstOrNull()
            val snapshot = selected?.let { runCatching { engine.snapshot(it.id) }.getOrNull() }
            mutableState.update {
                it.copy(
                    networks = networks,
                    selectedNetworkId = selected?.id,
                    snapshot = snapshot,
                    status = if (selected == null) NetworkDiagnosticsStatus.NO_NETWORK else NetworkDiagnosticsStatus.IDLE,
                    error = if (selected == null) "未找到可用网络" else null,
                    samples = emptyList(),
                    resolvedAddresses = emptyList(),
                    dnsDurationMillis = null,
                    report = null,
                    conclusion = null,
                )
            }
        }
    }

    fun selectNetwork(id: String) {
        if (mutableState.value.status == NetworkDiagnosticsStatus.RUNNING) return
        val network = mutableState.value.networks.firstOrNull { it.id == id } ?: return
        networkJob?.cancel()
        networkJob = viewModelScope.launch {
            val snapshot = runCatching { engine.snapshot(network.id) }.getOrNull()
            mutableState.update {
                it.copy(
                    selectedNetworkId = network.id,
                    snapshot = snapshot,
                    status = if (snapshot == null) NetworkDiagnosticsStatus.NO_NETWORK else NetworkDiagnosticsStatus.IDLE,
                    error = if (snapshot == null) "所选网络已断开" else null,
                    resolvedAddresses = emptyList(),
                    dnsDurationMillis = null,
                    samples = emptyList(),
                    report = null,
                    conclusion = null,
                )
            }
        }
    }

    fun updateHostname(value: String) {
        if (mutableState.value.targetLocked || mutableState.value.status == NetworkDiagnosticsStatus.RUNNING) return
        mutableState.update { it.resetResult().copy(hostnameInput = value, error = null) }
    }

    fun updatePort(value: String) {
        if (mutableState.value.targetLocked || mutableState.value.status == NetworkDiagnosticsStatus.RUNNING) return
        if (value.length > 5 || value.any { !it.isDigit() }) return
        mutableState.update { it.resetResult().copy(portInput = value, error = null) }
    }

    fun startTest() {
        val current = mutableState.value
        val validation = validateDiagnosticTarget(current.hostnameInput, current.portInput)
        if (validation != null) {
            mutableState.update { it.copy(error = validation) }
            return
        }
        val networkId = current.selectedNetworkId ?: run {
            mutableState.update { it.copy(status = NetworkDiagnosticsStatus.NO_NETWORK, error = "请选择可用网络") }
            return
        }
        val scanGeneration = generation.incrementAndGet()
        testJob?.cancel()
        engine.cancel()
        mutableState.update {
            it.copy(
                status = NetworkDiagnosticsStatus.RUNNING,
                resolvedAddresses = emptyList(),
                dnsDurationMillis = null,
                samples = emptyList(),
                report = null,
                conclusion = null,
                error = null,
            )
        }
        val request = DiagnosticRequest(
            networkId = networkId,
            hostname = normalizeDiagnosticHostname(current.hostnameInput),
            port = current.portInput.toInt(),
            readSshBanner = current.expectsSsh,
        )
        testJob = viewModelScope.launch {
            try {
                engine.diagnose(request).collect { event ->
                    if (generation.get() != scanGeneration) return@collect
                    handleEvent(event)
                }
            } catch (_: CancellationException) {
                // cancelTest owns the visible state; lifecycle cancellation needs no update.
            } catch (failure: Throwable) {
                if (generation.get() == scanGeneration) {
                    mutableState.update {
                        it.copy(status = NetworkDiagnosticsStatus.ERROR, error = failure.message ?: "网络诊断失败")
                    }
                }
            }
        }
    }

    fun cancelTest() {
        if (mutableState.value.status != NetworkDiagnosticsStatus.RUNNING) return
        generation.incrementAndGet()
        engine.cancel()
        testJob?.cancel()
        testJob = null
        mutableState.update { it.copy(status = NetworkDiagnosticsStatus.CANCELLED, error = null) }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val hosts = runCatching { loadHosts() }.getOrDefault(emptyList())
            val saved = hosts.firstOrNull { it.id == hostId }?.let { profile ->
                SavedDiagnosticTarget(profile, diagnosticEndpointFor(profile, hosts))
            }
            val networks = runCatching { engine.availableNetworks() }.getOrDefault(emptyList())
            val selected = networks.firstOrNull { it.isDefault } ?: networks.firstOrNull()
            val snapshot = selected?.let { runCatching { engine.snapshot(it.id) }.getOrNull() }
            mutableState.update {
                it.copy(
                    networks = networks,
                    selectedNetworkId = selected?.id,
                    snapshot = snapshot,
                    hostnameInput = saved?.endpoint?.hostname.orEmpty(),
                    portInput = saved?.endpoint?.port?.toString() ?: DEFAULT_DIAGNOSTIC_PORT.toString(),
                    savedTargetName = saved?.profile?.name,
                    targetLabel = saved?.endpoint?.label ?: "SSH 目标",
                    routeSummary = saved?.endpoint?.routeSummary,
                    routeLimitation = saved?.endpoint?.limitation,
                    targetLocked = saved != null,
                    expectsSsh = saved?.endpoint?.kind != DiagnosticEndpointKind.PROXY,
                    status = if (selected == null) NetworkDiagnosticsStatus.NO_NETWORK else NetworkDiagnosticsStatus.IDLE,
                    error = if (selected == null) "未找到可用网络" else null,
                )
            }
        }
    }

    private fun handleEvent(event: DiagnosticEvent) {
        when (event) {
            is DiagnosticEvent.Started -> mutableState.update { it.copy(snapshot = event.snapshot) }
            is DiagnosticEvent.DnsResolved -> mutableState.update {
                it.copy(resolvedAddresses = event.addresses, dnsDurationMillis = event.durationMillis)
            }
            is DiagnosticEvent.Sampled -> mutableState.update { it.copy(samples = it.samples + event.sample) }
            is DiagnosticEvent.Completed -> mutableState.update {
                it.copy(
                    status = NetworkDiagnosticsStatus.COMPLETED,
                    report = event.report,
                    conclusion = event.report.conclusion,
                    samples = event.report.samples,
                )
            }
            is DiagnosticEvent.Failed -> mutableState.update {
                it.copy(
                    status = if (event.conclusion.kind == com.yang136.sshhelper.diagnostics.DiagnosticConclusionKind.NO_NETWORK) {
                        NetworkDiagnosticsStatus.NO_NETWORK
                    } else NetworkDiagnosticsStatus.ERROR,
                    snapshot = event.snapshot ?: it.snapshot,
                    conclusion = event.conclusion,
                    error = event.message,
                )
            }
        }
    }

    override fun onCleared() {
        generation.incrementAndGet()
        engine.cancel()
        networkJob?.cancel()
        testJob?.cancel()
    }

    companion object {
        fun factory(container: AppContainer, hostId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NetworkDiagnosticsViewModel(
                engine = container.networkDiagnosticsEngine,
                hostId = hostId,
                loadHosts = { container.hostRepository.hosts.first() },
            ) as T
        }
    }
}

private fun NetworkDiagnosticsUiState.resetResult() = copy(
    status = if (selectedNetworkId == null) NetworkDiagnosticsStatus.NO_NETWORK else NetworkDiagnosticsStatus.IDLE,
    resolvedAddresses = emptyList(),
    dnsDurationMillis = null,
    samples = emptyList(),
    report = null,
    conclusion = null,
)

internal const val NETWORK_DIAGNOSTIC_SAMPLE_COUNT = DEFAULT_DIAGNOSTIC_SAMPLES
internal const val NETWORK_DIAGNOSTICS_ROUTE_PATTERN = "diagnostics?hostId={hostId}"
internal fun networkDiagnosticsRoute(hostId: Long = 0): String =
    if (hostId > 0) "diagnostics?hostId=$hostId" else "diagnostics"
