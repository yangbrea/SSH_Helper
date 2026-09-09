package com.yang136.sshhelper.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsTest {
    @Test
    fun defaults_areStable() {
        assertEquals(ThemeMode.SYSTEM, AppSettings().themeMode)
        assertEquals(ThemePreset.OCEAN, AppSettings().themePreset)
        assertEquals(14, AppSettings().terminalFontSize)
        assertFalse(AppSettings().terminalTransparencyEnabled)
        assertEquals(DEFAULT_TERMINAL_BACKGROUND_OPACITY, AppSettings().terminalBackgroundOpacity)
        // 锁库后活动转发隧道凭据租约默认开启（产品决策）。
        assertEquals(true, AppSettings().forwardReconnectAfterLock)
        assertEquals(null, AppSettings().lastLocalRootUri)
    }

    @Test
    fun terminalBackgroundOpacityIsClampedAndRejectsNonFiniteValues() {
        assertEquals(MIN_TERMINAL_BACKGROUND_OPACITY, coerceTerminalBackgroundOpacity(0.1f))
        assertEquals(0.75f, coerceTerminalBackgroundOpacity(0.75f))
        assertEquals(MAX_TERMINAL_BACKGROUND_OPACITY, coerceTerminalBackgroundOpacity(1.0f))
        assertEquals(DEFAULT_TERMINAL_BACKGROUND_OPACITY, coerceTerminalBackgroundOpacity(Float.NaN))
        assertEquals(DEFAULT_TERMINAL_BACKGROUND_OPACITY, coerceTerminalBackgroundOpacity(Float.POSITIVE_INFINITY))
    }

    @Test
    fun terminalBackgroundOpacityRespectsTransparencyEnabled() {
        assertEquals(1f, effectiveTerminalBackgroundOpacity(false, 0.7f))
        assertEquals(0.7f, effectiveTerminalBackgroundOpacity(true, 0.7f))
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
