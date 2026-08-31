package com.yang136.sshhelper.scanner

import kotlinx.coroutines.flow.Flow

const val DEFAULT_PORT_SCAN_CONCURRENCY = 64
const val DEFAULT_PORT_CONNECT_TIMEOUT_MILLIS = 750
const val DEFAULT_PORT_BANNER_TIMEOUT_MILLIS = 1_000
const val MAX_PORT_SCAN_PORTS = 65_535
const val MAX_PORT_BANNER_BYTES = 4_096

val COMMON_SCAN_PORTS = sortedSetOf(
    21, 22, 23, 25, 53, 80, 110, 143, 443, 445, 554, 587, 631, 993, 995,
    1433, 1521, 1883, 3306, 3389, 5432, 5900, 6379, 8000, 8080, 8443, 8883,
    9100, 27017,
)

data class PortScanNetwork(val id: String, val label: String, val isDefault: Boolean)

enum class PortState { OPEN, REFUSED, TIMEOUT_FILTERED, UNREACHABLE, ERROR }
enum class FingerprintConfidence { LOW, MEDIUM, HIGH }

data class ServiceFingerprint(
    val service: String,
    val product: String? = null,
    val version: String? = null,
    val banner: String? = null,
    val evidence: String,
    val confidence: FingerprintConfidence,
    val tlsUnverified: Boolean = false,
)

data class PortProbeResult(
    val address: String,
    val port: Int,
    val state: PortState,
    val latencyMillis: Double? = null,
    val fingerprint: ServiceFingerprint? = null,
    val message: String? = null,
)

data class PortScanRequest(
    val networkId: String,
    val target: String,
    val ports: Set<Int>,
    val selectedAddress: String? = null,
    val connectTimeoutMillis: Int = DEFAULT_PORT_CONNECT_TIMEOUT_MILLIS,
    val bannerTimeoutMillis: Int = DEFAULT_PORT_BANNER_TIMEOUT_MILLIS,
    val concurrency: Int = DEFAULT_PORT_SCAN_CONCURRENCY,
)

data class PortScanSummary(
    val address: String,
    val total: Int,
    val open: Int,
    val refused: Int,
    val timeoutFiltered: Int,
    val unreachable: Int,
    val errors: Int,
)

sealed interface PortScanEvent {
    data class Resolved(val addresses: List<String>, val selectedAddress: String) : PortScanEvent
    data class Started(val total: Int) : PortScanEvent
    data class Result(val result: PortProbeResult) : PortScanEvent
    data class Progress(val completed: Int, val total: Int) : PortScanEvent
    data class Completed(val summary: PortScanSummary, val traceId: String) : PortScanEvent
}

interface PortScanner {
    suspend fun availableNetworks(): List<PortScanNetwork>
    suspend fun resolve(networkId: String, target: String): List<String>
    fun scan(request: PortScanRequest): Flow<PortScanEvent>
    fun cancel()
}

interface PortScanBackend {
    suspend fun availableNetworks(): List<PortScanNetwork>
    suspend fun resolve(networkId: String, target: String): List<String>
    suspend fun probe(
        networkId: String,
        targetHost: String,
        address: String,
        port: Int,
        connectTimeoutMillis: Int,
        bannerTimeoutMillis: Int,
    ): PortProbeResult
    fun cancel()
}
