package com.yang136.sshhelper.discovery

object DiscoveryReducer {
    fun apply(
        devices: Map<String, DiscoveredSshDevice>,
        networkId: String,
        evidence: DiscoveryEvidence,
    ): Map<String, DiscoveredSshDevice> {
        val current = devices[evidence.address] ?: DiscoveredSshDevice(networkId, evidence.address)
        val updated = when (evidence) {
            is DiscoveryEvidence.Tcp -> current.copy(
                endpoints = current.endpoints + mergeEndpoint(
                    current.endpoints[evidence.port],
                    SshEndpoint(
                        port = evidence.port,
                        confidence = if (evidence.banner?.supported == true) {
                            SshConfidence.BANNER_CONFIRMED
                        } else {
                            SshConfidence.PORT_OPEN
                        },
                        banner = evidence.banner,
                    ),
                ).let { evidence.port to it },
                sources = current.sources + DiscoverySource.TCP,
            )

            is DiscoveryEvidence.Mdns -> current.copy(
                displayName = sanitizeServiceName(evidence.serviceName) ?: current.displayName,
                endpoints = current.endpoints + mergeEndpoint(
                    current.endpoints[evidence.port],
                    SshEndpoint(
                        port = evidence.port,
                        confidence = SshConfidence.MDNS_ADVERTISED,
                        serviceType = evidence.serviceType,
                    ),
                ).let { evidence.port to it },
                sources = current.sources + DiscoverySource.MDNS,
            )

            is DiscoveryEvidence.Arp -> current.copy(
                macAddress = evidence.macAddress,
                vendor = evidence.vendor,
                sources = current.sources + DiscoverySource.ARP,
            )
        }
        return devices + (evidence.address to updated)
    }

    private fun mergeEndpoint(current: SshEndpoint?, incoming: SshEndpoint): SshEndpoint {
        if (current == null) return incoming
        val confidence = maxOf(current.confidence, incoming.confidence)
        return SshEndpoint(
            port = current.port,
            confidence = confidence,
            banner = incoming.banner ?: current.banner,
            serviceType = incoming.serviceType ?: current.serviceType,
        )
    }

    private fun sanitizeServiceName(value: String): String? = value
        .filter { it.code in 0x20..0x7e || it.code > 0x9f }
        .trim()
        .take(128)
        .takeIf(String::isNotEmpty)
}
