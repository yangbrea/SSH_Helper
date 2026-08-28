package com.yang136.sshhelper.ui

import androidx.compose.ui.graphics.Color
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.ui.design.SshStatusColors
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.theme.resolveDarkMode
import com.yang136.sshhelper.ui.theme.colorScheme
import com.yang136.sshhelper.ui.theme.terminalPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSystemTest {
    @Test
    fun statusPresentation_hasStableLabelsIconsAndPriority() {
        assertEquals("在线", SshStatusTone.CONNECTED.label)
        assertEquals("sync", SshStatusTone.CONNECTING.iconKey)
        assertTrue(SshStatusTone.ERROR.priority > SshStatusTone.WARNING.priority)
        assertTrue(SshStatusTone.WARNING.priority > SshStatusTone.OFFLINE.priority)
    }

    @Test
    fun statusColors_mapEveryTone() {
        val colors = SshStatusColors(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Magenta, Color.Gray)
        assertEquals(Color.Red, colors.color(SshStatusTone.CONNECTED))
        assertEquals(Color.Green, colors.color(SshStatusTone.CONNECTING))
        assertEquals(Color.Blue, colors.color(SshStatusTone.WAITING))
        assertEquals(Color.Yellow, colors.color(SshStatusTone.WARNING))
        assertEquals(Color.Magenta, colors.color(SshStatusTone.ERROR))
        assertEquals(Color.Gray, colors.color(SshStatusTone.OFFLINE))
    }

    @Test
    fun themeMode_resolvesAgainstSystemWithoutChangingStoredPreset() {
        assertTrue(resolveDarkMode(ThemeMode.SYSTEM, true))
        assertFalse(resolveDarkMode(ThemeMode.SYSTEM, false))
        assertFalse(resolveDarkMode(ThemeMode.LIGHT, true))
        assertTrue(resolveDarkMode(ThemeMode.DARK, false))
        assertEquals(ThemePreset.VIOLET, AppSettings(themePreset = ThemePreset.VIOLET).themePreset)
    }

    @Test
    fun replacementPresets_keepStableEnumsAndUseNewPalettes() {
        assertEquals("曜石金", ThemePreset.AMBER.displayName())
        assertEquals("北境灰", ThemePreset.VIOLET.displayName())
        assertEquals(Color(0xFFD9B45F), colorScheme(ThemePreset.AMBER, true).primary)
        assertEquals(Color(0xFF0D1117), colorScheme(ThemePreset.VIOLET, true).background)
        assertEquals("#faf8f2", terminalPalette(ThemePreset.AMBER, false).background)
        assertEquals("#b8c4d6", terminalPalette(ThemePreset.VIOLET, true).cursor)
    }

    @Test
    fun terminalPalettes_lightAndDarkVariantsStayStable() {
        val expected = mapOf(
            (ThemePreset.OCEAN to true) to Triple("#07131f", "#dff8fb", "#22d3ee"),
            (ThemePreset.OCEAN to false) to Triple("#f8fbfc", "#15242b", "#22d3ee"),
            (ThemePreset.EMERALD to true) to Triple("#06130e", "#e2ffe9", "#35e07f"),
            (ThemePreset.EMERALD to false) to Triple("#f8fbfc", "#15242b", "#35e07f"),
            (ThemePreset.AMBER to true) to Triple("#0b0b0d", "#f2ecdd", "#d9b45f"),
            (ThemePreset.AMBER to false) to Triple("#faf8f2", "#29251d", "#765b12"),
            (ThemePreset.VIOLET to true) to Triple("#0d1117", "#e9eef5", "#b8c4d6"),
            (ThemePreset.VIOLET to false) to Triple("#f5f7fa", "#1a2028", "#465a70"),
        )
        expected.forEach { (key, colors) ->
            val (preset, dark) = key
            val palette = terminalPalette(preset, dark)
            assertEquals("${preset} dark=$dark 背景", colors.first, palette.background)
            assertEquals("${preset} dark=$dark 前景", colors.second, palette.foreground)
            assertEquals("${preset} dark=$dark 光标", colors.third, palette.cursor)
        }
    }
}
