package com.yang136.sshhelper.diagnostics

import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.discovery.SshBanner

const val DEFAULT_DIAGNOSTIC_PORT = 22
const val DEFAULT_DIAGNOSTIC_SAMPLES = 5
const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000
const val DEFAULT_DNS_TIMEOUT_MILLIS = 5_000L
const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 300L
const val DEFAULT_BANNER_TIMEOUT_MILLIS = 1_000

enum class DiagnosticTransport { WIFI, ETHERNET, CELLULAR, VPN, OTHER }

data class DiagnosticNetwork(
    val id: String,
    val label: String,
    val transports: Set<DiagnosticTransport>,
    val isDefault: Boolean,
)

data class NetworkSnapshot(
    val network: DiagnosticNetwork,
    val interfaceName: String?,
    val addresses: List<String>,
    val gateways: List<String>,
    val dnsServers: List<String>,
    val mtu: Int?,
    val privateDnsActive: Boolean,
    val privateDnsServerName: String?,
    val httpProxy: String?,
    val metered: Boolean,
    val hasInternetCapability: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
)

data class DiagnosticRequest(
    val networkId: String,
    val hostname: String,
    val port: Int,
    val readSshBanner: Boolean = true,
    val sampleCount: Int = DEFAULT_DIAGNOSTIC_SAMPLES,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val dnsTimeoutMillis: Long = DEFAULT_DNS_TIMEOUT_MILLIS,
    val sampleIntervalMillis: Long = DEFAULT_SAMPLE_INTERVAL_MILLIS,
)

enum class DiagnosticFailureKind { TIMEOUT, REFUSED, UNREACHABLE, PERMISSION, IO }

sealed interface DiagnosticSample {
    val index: Int

    data class Success(
        override val index: Int,
        val address: String,
        val durationMillis: Double,
    ) : DiagnosticSample

    data class Failure(
        override val index: Int,
        val kind: DiagnosticFailureKind,
        val message: String,
    ) : DiagnosticSample
}

enum class DiagnosticConclusionKind {
    NO_NETWORK,
    PERMISSION_DENIED,
    DNS_FAILURE,
    TCP_FAILURE,
    PARTIAL_FAILURE,
    NON_SSH_SERVICE,
    HEALTHY,
}

data class DiagnosticConclusion(
    val kind: DiagnosticConclusionKind,
    val title: String,
    val detail: String,
)

data class DiagnosticReport(
    val request: DiagnosticRequest,
    val snapshot: NetworkSnapshot,
    val resolvedAddresses: List<String>,
    val dnsDurationMillis: Double,
    val samples: List<DiagnosticSample>,
    val banner: SshBanner?,
    val conclusion: DiagnosticConclusion,
) {
    val successfulSamples: List<DiagnosticSample.Success>
        get() = samples.filterIsInstance<DiagnosticSample.Success>()
    val minimumMillis: Double? get() = successfulSamples.minOfOrNull { it.durationMillis }
    val averageMillis: Double? get() = successfulSamples.map { it.durationMillis }.average().takeUnless(Double::isNaN)
    val maximumMillis: Double? get() = successfulSamples.maxOfOrNull { it.durationMillis }
    val failureRatePercent: Int
        get() = if (samples.isEmpty()) 0 else ((samples.count { it is DiagnosticSample.Failure } * 100.0) / samples.size).toInt()
}

sealed interface DiagnosticEvent {
    data class Started(val snapshot: NetworkSnapshot) : DiagnosticEvent
    data class DnsResolved(val addresses: List<String>, val durationMillis: Double) : DiagnosticEvent
    data class Sampled(val sample: DiagnosticSample) : DiagnosticEvent
    data class Completed(val report: DiagnosticReport) : DiagnosticEvent
    data class Failed(
        val conclusion: DiagnosticConclusion,
        val message: String,
        val snapshot: NetworkSnapshot? = null,
    ) : DiagnosticEvent
}

interface NetworkDiagnosticsEngine {
    suspend fun availableNetworks(): List<DiagnosticNetwork>
    suspend fun snapshot(networkId: String): NetworkSnapshot?
    fun diagnose(request: DiagnosticRequest): kotlinx.coroutines.flow.Flow<DiagnosticEvent>
    fun cancel()
}

enum class DiagnosticEndpointKind { SSH, PROXY }

data class DiagnosticEndpoint(
    val hostname: String,
    val port: Int,
    val label: String,
    val kind: DiagnosticEndpointKind,
    val routeSummary: String,
    val limitation: String? = null,
)

data class SavedDiagnosticTarget(
    val profile: HostProfile,
    val endpoint: DiagnosticEndpoint,
)

fun validateDiagnosticTarget(hostname: String, portText: String): String? {
    if (hostname.trim().removeSurrounding("[", "]").isBlank()) return "请输入目标地址"
    val port = portText.toIntOrNull() ?: return "端口必须是 1–65535 之间的整数"
    if (port !in 1..65_535) return "端口必须是 1–65535 之间的整数"
    return null
}

fun normalizeDiagnosticHostname(value: String): String = value.trim().removeSurrounding("[", "]")

fun diagnosticEndpointFor(target: HostProfile, allHosts: List<HostProfile>): DiagnosticEndpoint {
    val jump = target.jumpHostId?.let { id -> allHosts.firstOrNull { it.id == id } }
    val routeHost = jump ?: target
    val proxyHost = routeHost.proxyHost?.takeIf { routeHost.proxyType != null && it.isNotBlank() }
    val proxyPort = routeHost.proxyPort?.takeIf { it in 1..65_535 }
    val endpointIsProxy = proxyHost != null && proxyPort != null
    val endpointHost = if (endpointIsProxy) proxyHost else routeHost.hostname
    val endpointPort = if (endpointIsProxy) proxyPort else routeHost.port
    val routeParts = buildList {
        add("本机")
        if (endpointIsProxy) add("${routeHost.proxyType!!.name} 代理 $endpointHost:$endpointPort")
        if (jump != null) add("跳板机 ${jump.hostname}:${jump.port}")
        add("目标 ${target.hostname}:${target.port}")
    }
    val firstHopLabel = when {
        endpointIsProxy -> "${routeHost.proxyType!!.name} 代理"
        jump != null -> "跳板机"
        else -> "SSH 目标"
    }
    val limited = endpointIsProxy || jump != null
    return DiagnosticEndpoint(
        hostname = endpointHost,
        port = endpointPort,
        label = firstHopLabel,
        kind = if (endpointIsProxy) DiagnosticEndpointKind.PROXY else DiagnosticEndpointKind.SSH,
        routeSummary = routeParts.joinToString(" → "),
        limitation = if (limited) "首版只检测本机到第一个连接节点，不读取凭据或验证后续链路。" else null,
    )
}

fun classifyDiagnostic(
    samples: List<DiagnosticSample>,
    banner: SshBanner?,
    expectsSsh: Boolean,
): DiagnosticConclusion {
    val successes = samples.count { it is DiagnosticSample.Success }
    val permissionDenied = samples.any {
        it is DiagnosticSample.Failure && it.kind == DiagnosticFailureKind.PERMISSION
    }
    return when {
        permissionDenied -> DiagnosticConclusion(
            DiagnosticConclusionKind.PERMISSION_DENIED,
            "系统拒绝网络访问",
            "请检查本地网络或附近设备权限，然后重新测试。",
        )
        successes == 0 -> DiagnosticConclusion(
            DiagnosticConclusionKind.TCP_FAILURE,
            "目标端口无法连接",
            "DNS 已完成，但所有 TCP 连接均失败；请检查地址、端口、防火墙和路由。",
        )
        successes < samples.size -> DiagnosticConclusion(
            DiagnosticConclusionKind.PARTIAL_FAILURE,
            "连接不稳定",
            "部分 TCP 连接失败，可能存在网络切换、拥塞或服务限流。",
        )
        expectsSsh && banner == null -> DiagnosticConclusion(
            DiagnosticConclusionKind.NON_SSH_SERVICE,
            "端口可连接，但未识别到 SSH",
            "该端口可能不是 SSH 服务，或服务端 Banner 响应较慢。",
        )
        else -> DiagnosticConclusion(
            DiagnosticConclusionKind.HEALTHY,
            "网络连接正常",
            if (expectsSsh) "TCP 连接稳定，并已识别 SSH 服务。" else "到第一个连接节点的 TCP 连接稳定。",
        )
    }
}
