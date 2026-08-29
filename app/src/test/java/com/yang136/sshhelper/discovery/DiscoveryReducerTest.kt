package com.yang136.sshhelper.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryReducerTest {
    @Test
    fun mergesOutOfOrderEvidenceWithoutDowngradingConfidence() {
        var devices = emptyMap<String, DiscoveredSshDevice>()
        devices = DiscoveryReducer.apply(
            devices,
            "network-1",
            DiscoveryEvidence.Mdns("192.168.1.8", 2222, "Office NAS", "_ssh._tcp."),
        )
        val banner = SshBanner("SSH-2.0-OpenSSH_9.8", "2.0", "OpenSSH_9.8")
        devices = DiscoveryReducer.apply(
            devices,
            "network-1",
            DiscoveryEvidence.Tcp("192.168.1.8", 2222, banner),
        )
        devices = DiscoveryReducer.apply(
            devices,
            "network-1",
            DiscoveryEvidence.Arp("192.168.1.8", "00:11:22:33:44:55", "Example"),
        )

        val device = devices.getValue("192.168.1.8")
        assertEquals("Office NAS", device.displayName)
        assertEquals(SshConfidence.BANNER_CONFIRMED, device.endpoints.getValue(2222).confidence)
        assertEquals("_ssh._tcp.", device.endpoints.getValue(2222).serviceType)
        assertEquals("Example", device.vendor)
        assertEquals(setOf(DiscoverySource.MDNS, DiscoverySource.TCP, DiscoverySource.ARP), device.sources)
    }

    @Test
    fun arpOnlyEvidenceDoesNotInventAnEndpoint() {
        val devices = DiscoveryReducer.apply(
            emptyMap(),
            "network-1",
            DiscoveryEvidence.Arp("192.168.1.9", "00:11:22:33:44:55", null),
        )

        assertTrue(devices.getValue("192.168.1.9").endpoints.isEmpty())
        assertNull(devices.getValue("192.168.1.9").displayName)
    }
}
