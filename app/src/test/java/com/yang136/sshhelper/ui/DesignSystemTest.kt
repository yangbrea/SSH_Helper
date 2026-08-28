package com.yang136.sshhelper.ui

import androidx.compose.ui.graphics.Color
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.ui.design.SshStatusColors
import com.yang136.sshhelper.ui.design.SshStatusTone
import com.yang136.sshhelper.ui.theme.resolveDarkMode
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
}
