package com.yang136.sshhelper.theme

import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.ImageThemeVariant
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemeSource
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.settings.coerceImageOverlayStrength
import com.yang136.sshhelper.settings.effectiveThemeMode
import com.yang136.sshhelper.settings.defaultImageOverlayStrength
import com.yang136.sshhelper.settings.parseThemeSource
import com.yang136.sshhelper.ui.theme.terminalPaletteForTerminal
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageThemeSettingsTest {
    @Test
    fun `legacy theme source migrates from existing preset`() {
        assertEquals(ThemeSource.PRESET, parseThemeSource(null))
        assertEquals(ThemeSource.PRESET, parseThemeSource("BROKEN"))
        assertEquals(ThemeSource.IMAGE, parseThemeSource("IMAGE"))
    }

    @Test
    fun `image variant controls effective system bar mode`() {
        val base = AppSettings(themeSource = ThemeSource.IMAGE)

        assertEquals(
            ThemeMode.DARK,
            base.copy(imageThemeVariant = ImageThemeVariant.IMMERSIVE).effectiveThemeMode,
        )
        assertEquals(
            ThemeMode.LIGHT,
            base.copy(imageThemeVariant = ImageThemeVariant.BRIGHT).effectiveThemeMode,
        )
    }

    @Test
    fun `image overlay strength remains within readable range`() {
        assertEquals(0.35f, coerceImageOverlayStrength(0.1f))
        assertEquals(0.8f, coerceImageOverlayStrength(0.95f))
        assertEquals(0.6f, coerceImageOverlayStrength(0.6f))
        assertEquals(0.55f, coerceImageOverlayStrength(Float.NaN))
        assertEquals(0.55f, coerceImageOverlayStrength(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `each generated recipe has a readability tuned default overlay`() {
        assertEquals(0.55f, defaultImageOverlayStrength(ImageThemeVariant.IMMERSIVE))
        assertEquals(0.65f, defaultImageOverlayStrength(ImageThemeVariant.SOFT))
        assertEquals(0.60f, defaultImageOverlayStrength(ImageThemeVariant.BRIGHT))
    }

    @Test
    fun `image theme never replaces saved terminal preset`() {
        val settings = AppSettings(
            themeMode = ThemeMode.DARK,
            themePreset = ThemePreset.AMBER,
            themeSource = ThemeSource.IMAGE,
            imageThemeVariant = ImageThemeVariant.BRIGHT,
        )

        val terminal = terminalPaletteForTerminal(settings)

        assertEquals("#000000", terminal.background)
        assertEquals("#000000", terminal.cursorAccent)
        assertEquals("#d9b45f", terminal.cursor)
    }

    @Test
    fun `terminal background stays black in every mode and preset`() {
        ThemePreset.entries.forEach { preset ->
            ThemeMode.entries.forEach { mode ->
                val terminal = terminalPaletteForTerminal(AppSettings(themeMode = mode, themePreset = preset))
                assertEquals("${preset}/${mode} 背景必须恒黑", "#000000", terminal.background)
                assertEquals("${preset}/${mode} 光标底色必须恒黑", "#000000", terminal.cursorAccent)
            }
        }
    }
}
