package com.yang136.sshhelper.discovery

object DiscoveryReducer {
    fun apply(
        devices: Map<String, DiscoveredDevice>,
        networkId: String,
        evidence: DiscoveryEvidence,
    ): Map<String, DiscoveredDevice> {
        val current = devices[evidence.address] ?: DiscoveredDevice(networkId, evidence.address)
        val updated = when (evidence) {
            is DiscoveryEvidence.Tcp -> current.withService(
                DiscoveredService(
                    port = evidence.port,
                    kind = evidence.serviceKind,
                    confidence = if (evidence.banner?.supported == true) {
                        SshConfidence.BANNER_CONFIRMED
                    } else {
                        SshConfidence.PORT_OPEN
                    },
                    banner = evidence.banner,
                ),
                DiscoverySource.TCP,
            )

            is DiscoveryEvidence.Mdns -> current.copy(
                mdnsName = sanitize(evidence.serviceName) ?: current.mdnsName,
            ).withService(
                DiscoveredService(
                    port = evidence.port,
                    kind = evidence.serviceKind,
                    confidence = if (evidence.serviceKind == ServiceKind.SSH) {
                        SshConfidence.MDNS_ADVERTISED
                    } else {
                        SshConfidence.PORT_OPEN
                    },
                    serviceType = evidence.serviceType,
                    displayName = sanitize(evidence.serviceName),
                ),
                DiscoverySource.MDNS,
            )

            is DiscoveryEvidence.Ssdp -> current.copy(
                ssdpRecords = (current.ssdpRecords + evidence.record)
                    .distinctBy { Triple(it.address, it.usn, it.st) },
                sources = current.sources + DiscoverySource.SSDP,
            )

            is DiscoveryEvidence.Description -> current.copy(
                description = evidence.value.copy(
                    friendlyName = sanitize(evidence.value.friendlyName),
                    manufacturer = sanitize(evidence.value.manufacturer),
                    modelName = sanitize(evidence.value.modelName),
                    modelNumber = sanitize(evidence.value.modelNumber),
                    deviceType = sanitize(evidence.value.deviceType),
                ),
                sources = current.sources + DiscoverySource.DEVICE_DESCRIPTION,
            )

            is DiscoveryEvidence.Arp -> current.copy(
                macAddress = evidence.macAddress,
                vendor = evidence.vendor,
                sources = current.sources + DiscoverySource.ARP,
            )
        }.let(DeviceClassifier::classify)
        return devices + (evidence.address to updated)
    }

    private fun DiscoveredDevice.withService(
        incoming: DiscoveredService,
        source: DiscoverySource,
    ): DiscoveredDevice {
        val current = services[incoming.key]
        val merged = if (current == null) incoming else incoming.copy(
            confidence = maxOf(current.confidence, incoming.confidence),
            banner = incoming.banner ?: current.banner,
            serviceType = incoming.serviceType ?: current.serviceType,
            displayName = incoming.displayName ?: current.displayName,
        )
        return copy(services = services + (incoming.key to merged), sources = sources + source)
    }

    private fun sanitize(value: String?): String? = value
        ?.filter { it.code in 0x20..0x7e || it.code > 0x9f }
        ?.trim()
        ?.take(128)
        ?.takeIf(String::isNotEmpty)
}

object DeviceClassifier {
    fun classify(device: DiscoveredDevice): DiscoveredDevice {
        val kinds = device.services.values.mapTo(mutableSetOf(), DiscoveredService::kind)
        val ssdp = device.ssdpRecords.joinToString(" ") { "${it.st} ${it.usn.orEmpty()}" }.lowercase()
        val describedType = device.description?.deviceType.orEmpty().lowercase()
        val classification = when {
            ServiceKind.IPP in kinds || ServiceKind.IPPS in kinds || ServiceKind.JETDIRECT in kinds ->
                DeviceClassification(DeviceKind.PRINTER, ClassificationConfidence.HIGH, "打印服务")
            "internetgatewaydevice" in ssdp || "internetgatewaydevice" in describedType || "wanconnectiondevice" in ssdp ->
                DeviceClassification(DeviceKind.ROUTER, ClassificationConfidence.HIGH, "UPnP 网关")
            "mediarenderer" in ssdp || "mediaserver" in ssdp || "mediarenderer" in describedType ||
                ServiceKind.AIRPLAY in kinds || ServiceKind.GOOGLE_CAST in kinds ->
                DeviceClassification(DeviceKind.MEDIA_DEVICE, ClassificationConfidence.HIGH, "媒体广播服务")
            ServiceKind.WORKSTATION in kinds || ServiceKind.RDP in kinds ->
                DeviceClassification(DeviceKind.COMPUTER, ClassificationConfidence.MEDIUM, "工作站服务")
            ServiceKind.HOMEKIT in kinds || ServiceKind.MQTT in kinds ->
                DeviceClassification(DeviceKind.IOT, ClassificationConfidence.MEDIUM, "IoT 服务")
            else -> DeviceClassification()
        }
        return device.copy(classification = classification)
    }
}
