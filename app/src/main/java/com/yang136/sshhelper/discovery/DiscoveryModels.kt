package com.yang136.sshhelper.discovery

import kotlinx.coroutines.flow.Flow

const val DEFAULT_SSH_PORT = 22
const val MAX_SCAN_ADDRESSES = 1_024
const val MAX_SCAN_PORTS = 16
const val MAX_GENERAL_CUSTOM_PORTS = 4

val SSH_MDNS_SERVICE_TYPES = setOf("_ssh._tcp.", "_sftp-ssh._tcp.")
val GENERAL_MDNS_SERVICE_TYPES = setOf(
    "_ssh._tcp.", "_sftp-ssh._tcp.", "_http._tcp.", "_https._tcp.",
    "_workstation._tcp.", "_device-info._tcp.", "_ipp._tcp.", "_ipps._tcp.",
    "_printer._tcp.", "_smb._tcp.", "_airplay._tcp.", "_raop._tcp.",
    "_googlecast._tcp.", "_hap._tcp.", "_rtsp._tcp.",
)

enum class ScanMode { SSH, GENERAL }
enum class TransportProtocol { TCP, UDP }
enum class DiscoverySource { TCP, MDNS, SSDP, ARP, DEVICE_DESCRIPTION }
enum class DiscoveryStatus { IDLE, SCANNING, COMPLETED, CANCELLED, NO_NETWORK, ERROR }
enum class SshConfidence { PORT_OPEN, MDNS_ADVERTISED, BANNER_CONFIRMED }
enum class ClassificationConfidence { LOW, MEDIUM, HIGH }
enum class DeviceKind { ROUTER, COMPUTER, PRINTER, MEDIA_DEVICE, IOT, UNKNOWN }
enum class ServiceKind {
    SSH, HTTP, HTTPS, FTP, TELNET, DNS, NETBIOS, SMB, RTSP, IPP, IPPS, MQTT,
    RDP, JETDIRECT, UPNP, WORKSTATION, AIRPLAY, GOOGLE_CAST, HOMEKIT, UNKNOWN,
}

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
    /** SSH ports in SSH mode; optional first-phase ports in general mode. */
    val ports: Set<Int>,
    val ownAddress: String,
    val mode: ScanMode = ScanMode.SSH,
)

data class SshBanner(
    val raw: String,
    val protocolVersion: String,
    val softwareVersion: String,
) {
    val supported: Boolean get() = protocolVersion == "2.0" || protocolVersion == "1.99"
}

data class DiscoveredService(
    val port: Int,
    val kind: ServiceKind = ServiceKind.SSH,
    val transport: TransportProtocol = TransportProtocol.TCP,
    val confidence: SshConfidence = SshConfidence.PORT_OPEN,
    val banner: SshBanner? = null,
    val serviceType: String? = null,
    val displayName: String? = null,
) {
    val key: String get() = "${transport.name}:$port:${kind.name}"
}

typealias SshEndpoint = DiscoveredService

data class DeviceDescription(
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val modelNumber: String? = null,
    val deviceType: String? = null,
)

data class SsdpRecord(
    val address: String,
    val st: String,
    val usn: String? = null,
    val server: String? = null,
    val location: String? = null,
)

data class DeviceClassification(
    val kind: DeviceKind = DeviceKind.UNKNOWN,
    val confidence: ClassificationConfidence = ClassificationConfidence.LOW,
    val reason: String? = null,
)

data class DiscoveredDevice(
    val networkId: String,
    val address: String,
    val mdnsName: String? = null,
    val services: Map<String, DiscoveredService> = emptyMap(),
    val ssdpRecords: List<SsdpRecord> = emptyList(),
    val description: DeviceDescription? = null,
    val macAddress: String? = null,
    val vendor: String? = null,
    val sources: Set<DiscoverySource> = emptySet(),
    val classification: DeviceClassification = DeviceClassification(),
) {
    val displayName: String? get() = description?.friendlyName ?: mdnsName
    val endpoints: Map<Int, DiscoveredService>
        get() = services.values.filter { it.kind == ServiceKind.SSH }.associateBy(DiscoveredService::port)
    val bestConfidence: SshConfidence
        get() = endpoints.values.maxOfOrNull(DiscoveredService::confidence) ?: SshConfidence.PORT_OPEN
    val hasSsh: Boolean get() = endpoints.isNotEmpty()
}

typealias DiscoveredSshDevice = DiscoveredDevice

sealed interface DiscoveryEvidence {
    val address: String

    data class Tcp(
        override val address: String,
        val port: Int,
        val banner: SshBanner?,
        val serviceKind: ServiceKind = ServiceKind.SSH,
    ) : DiscoveryEvidence

    data class Mdns(
        override val address: String,
        val port: Int,
        val serviceName: String,
        val serviceType: String,
        val serviceKind: ServiceKind = serviceKindForMdns(serviceType),
    ) : DiscoveryEvidence

    data class Ssdp(val record: SsdpRecord) : DiscoveryEvidence {
        override val address: String get() = record.address
    }

    data class Description(
        override val address: String,
        val value: DeviceDescription,
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
    /** totalProbes may grow when the general scan schedules its second phase. */
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

data class TcpProbeResult(val banner: SshBanner?)

interface TcpServiceProbe {
    suspend fun probe(
        networkId: String,
        address: String,
        port: Int,
        readSshBanner: Boolean = true,
    ): TcpProbeResult?

    fun cancel()
}

typealias TcpSshProbe = TcpServiceProbe

data class MdnsService(
    val address: String,
    val port: Int,
    val serviceName: String,
    val serviceType: String,
)

interface MdnsDiscovery {
    fun discover(networkId: String, serviceTypes: Set<String> = SSH_MDNS_SERVICE_TYPES): Flow<MdnsService>
    fun cancel()
}

interface SsdpDiscovery {
    fun discover(networkId: String, cidr: Ipv4Cidr): Flow<SsdpRecord>
    fun cancel()
}

object NoOpSsdpDiscovery : SsdpDiscovery {
    override fun discover(networkId: String, cidr: Ipv4Cidr): Flow<SsdpRecord> =
        kotlinx.coroutines.flow.emptyFlow()
    override fun cancel() = Unit
}

object NoOpDeviceDescriptionRepository : DeviceDescriptionRepository {
    override suspend fun load(
        networkId: String,
        address: String,
        location: String,
    ): Result<DeviceDescription> = Result.failure(UnsupportedOperationException("设备描述不可用"))
    override fun clear() = Unit
}

interface DeviceDescriptionRepository {
    suspend fun load(networkId: String, address: String, location: String): Result<DeviceDescription>
    fun clear()
}

interface ArpTableReader {
    suspend fun read(interfaceName: String): Result<List<ArpEntry>>
}

interface MacVendorResolver {
    fun vendorFor(macAddress: String): String?
}

fun serviceKindForPort(port: Int): ServiceKind = when (port) {
    21 -> ServiceKind.FTP
    22 -> ServiceKind.SSH
    23 -> ServiceKind.TELNET
    53 -> ServiceKind.DNS
    80, 8000, 8080 -> ServiceKind.HTTP
    443, 8443 -> ServiceKind.HTTPS
    139 -> ServiceKind.NETBIOS
    445 -> ServiceKind.SMB
    554 -> ServiceKind.RTSP
    631 -> ServiceKind.IPP
    1883 -> ServiceKind.MQTT
    3389 -> ServiceKind.RDP
    9100 -> ServiceKind.JETDIRECT
    else -> ServiceKind.UNKNOWN
}

fun serviceKindForMdns(serviceType: String): ServiceKind {
    val type = serviceType.lowercase()
    return when {
        type.startsWith("_ssh.") || type.startsWith("_sftp-ssh.") -> ServiceKind.SSH
        type.startsWith("_https.") -> ServiceKind.HTTPS
        type.startsWith("_http.") -> ServiceKind.HTTP
        type.startsWith("_workstation.") -> ServiceKind.WORKSTATION
        type.startsWith("_ipps.") -> ServiceKind.IPPS
        type.startsWith("_ipp.") || type.startsWith("_printer.") -> ServiceKind.IPP
        type.startsWith("_smb.") -> ServiceKind.SMB
        type.startsWith("_airplay.") || type.startsWith("_raop.") -> ServiceKind.AIRPLAY
        type.startsWith("_googlecast.") -> ServiceKind.GOOGLE_CAST
        type.startsWith("_hap.") -> ServiceKind.HOMEKIT
        type.startsWith("_rtsp.") -> ServiceKind.RTSP
        else -> ServiceKind.UNKNOWN
    }
}
