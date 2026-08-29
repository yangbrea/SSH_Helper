package com.yang136.sshhelper.discovery

import kotlinx.coroutines.flow.Flow

const val DEFAULT_SSH_PORT = 22
const val MAX_SCAN_ADDRESSES = 1_024
const val MAX_SCAN_PORTS = 16

data class LanNetwork(
    val id: String,
    val label: String,
    val interfaceName: String,
    val ipv4Address: String,
    val prefixLength: Int,
)

data class ScanRequest(
    val networkId: String,
    val cidr: Ipv4Cidr,
    val ports: Set<Int>,
    val ownAddress: String,
)

enum class DiscoverySource { TCP, MDNS, ARP }

enum class SshConfidence { PORT_OPEN, MDNS_ADVERTISED, BANNER_CONFIRMED }

data class SshBanner(
    val raw: String,
    val protocolVersion: String,
    val softwareVersion: String,
) {
    val supported: Boolean get() = protocolVersion == "2.0" || protocolVersion == "1.99"
}

data class SshEndpoint(
    val port: Int,
    val confidence: SshConfidence,
    val banner: SshBanner? = null,
    val serviceType: String? = null,
)

data class DiscoveredSshDevice(
    val networkId: String,
    val address: String,
    val displayName: String? = null,
    val endpoints: Map<Int, SshEndpoint> = emptyMap(),
    val macAddress: String? = null,
    val vendor: String? = null,
    val sources: Set<DiscoverySource> = emptySet(),
) {
    val bestConfidence: SshConfidence
        get() = endpoints.values.maxOfOrNull(SshEndpoint::confidence) ?: SshConfidence.PORT_OPEN
}

sealed interface DiscoveryEvidence {
    val address: String

    data class Tcp(
        override val address: String,
        val port: Int,
        val banner: SshBanner?,
    ) : DiscoveryEvidence

    data class Mdns(
        override val address: String,
        val port: Int,
        val serviceName: String,
        val serviceType: String,
    ) : DiscoveryEvidence

    data class Arp(
        override val address: String,
        val macAddress: String,
        val vendor: String?,
    ) : DiscoveryEvidence
}

sealed interface DiscoveryEvent {
    data class Started(val totalProbes: Int) : DiscoveryEvent
    data class Evidence(val value: DiscoveryEvidence) : DiscoveryEvent
    data class Progress(val completedProbes: Int, val totalProbes: Int) : DiscoveryEvent
    data class Notice(val message: String) : DiscoveryEvent
    data object Completed : DiscoveryEvent
}

interface LanDiscoveryEngine {
    fun scan(request: ScanRequest): Flow<DiscoveryEvent>
    fun cancel()
}

interface NetworkEnvironment {
    suspend fun availableNetworks(): List<LanNetwork>
}

interface MacVendorResolver {
    fun vendorFor(macAddress: String): String?
}

