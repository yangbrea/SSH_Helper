package com.yang136.sshhelper.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArpAndOuiTest {
    @Test
    fun parsesOnlyCompleteUnicastEntriesForSelectedInterface() {
        val content = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.1.2      0x1         0x2         00:11:22:33:44:55     *        wlan0
            192.168.1.3      0x1         0x0         00:11:22:33:44:66     *        wlan0
            192.168.1.4      0x1         0x2         ff:ff:ff:ff:ff:ff     *        wlan0
            192.168.1.5      0x1         0x2         00:11:22:33:44:77     *        eth0
        """.trimIndent()

        assertEquals(listOf(ArpEntry("192.168.1.2", "00:11:22:33:44:55")), ArpTableParser.parse(content, "wlan0"))
    }

    @Test
    fun ouiUsesLongestMatchingPrefix() {
        val index = OuiIndex.parse(sequenceOf(
            "001122/24\tExample Corp",
            "0011223/28\tExample Device Division",
        ))

        assertEquals("Example Device Division", index.vendorFor("00:11:22:33:44:55"))
        assertEquals("Example Corp", index.vendorFor("00:11:22:AA:44:55"))
        assertNull(index.vendorFor("02:FF:EE:DD:CC:BB"))
    }
}

