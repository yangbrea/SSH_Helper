package com.yang136.sshhelper.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryGuardTest {
    @Test
    fun detectsSupportedRomFamiliesFromBrandOrManufacturer() {
        assertEquals(OemFamily.XIAOMI, detectOemFamily("POCO", "unknown"))
        assertEquals(OemFamily.OPPO, detectOemFamily("OnePlus", "unknown"))
        assertEquals(OemFamily.VIVO, detectOemFamily("iQOO", "unknown"))
        assertEquals(OemFamily.HUAWEI, detectOemFamily("unknown", "HONOR"))
        assertEquals(OemFamily.SAMSUNG, detectOemFamily("Samsung", "Samsung"))
        assertEquals(OemFamily.STOCK, detectOemFamily("Google", "Google"))
    }

    @Test
    fun everySupportedOemHasOrderedCandidates() {
        OemFamily.entries.filter { it != OemFamily.STOCK }.forEach { family ->
            assertTrue("$family should have settings candidates", oemBatterySettingsComponents(family).isNotEmpty())
        }
        assertTrue(oemBatterySettingsComponents(OemFamily.STOCK).isEmpty())
    }
}
