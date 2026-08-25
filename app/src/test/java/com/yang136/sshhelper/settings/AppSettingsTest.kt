package com.yang136.sshhelper.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun defaults_areStable() {
        assertEquals(ThemeMode.SYSTEM, AppSettings().themeMode)
        assertEquals(ThemePreset.OCEAN, AppSettings().themePreset)
        assertEquals(14, AppSettings().terminalFontSize)
        // 锁库后活动转发隧道凭据租约默认开启（产品决策）。
        assertEquals(true, AppSettings().forwardReconnectAfterLock)
    }

    @Test
    fun fontSize_isClampedToSupportedRange() {
        assertEquals(10, sanitizeTerminalFontSize(1))
        assertEquals(18, sanitizeTerminalFontSize(18))
        assertEquals(28, sanitizeTerminalFontSize(99))
    }

    @Test
    fun unknownStoredEnums_fallBackSafely() {
        assertEquals(ThemeMode.SYSTEM, enumValueOrDefault<ThemeMode>("BROKEN", ThemeMode.SYSTEM))
        assertEquals(ThemePreset.OCEAN, enumValueOrDefault<ThemePreset>(null, ThemePreset.OCEAN))
        assertEquals(ThemePreset.VIOLET, enumValueOrDefault("VIOLET", ThemePreset.OCEAN))
    }
}
