package com.yang136.sshhelper.ui

import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.settings.ThemeSource
import com.yang136.sshhelper.settings.ImageThemeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun stableIds_resolveNotificationTargets() {
        SettingsDestination.entries.forEach { assertEquals(it, SettingsDestination.fromId(it.id)) }
        assertNull(SettingsDestination.fromId("removed-section"))
        assertNull(SettingsDestination.fromId(null))
    }

    @Test
    fun summaries_reflectCurrentState() {
        val settings = AppSettings(themeMode = ThemeMode.DARK, themePreset = ThemePreset.AMBER, aiApiKey = "secret")
        assertEquals("深色 · 曜石金", settingsSummary(SettingsDestination.APPEARANCE, settings, "已锁定", 2, 0))
        assertEquals(
            "图片主题 · 明亮",
            settingsSummary(
                SettingsDestination.APPEARANCE,
                settings.copy(themeSource = ThemeSource.IMAGE, imageThemeVariant = ImageThemeVariant.BRIGHT),
                "已锁定",
                2,
                0,
            ),
        )
        assertEquals("2 台主机已授权 · 3 个待恢复", settingsSummary(SettingsDestination.DOCUMENTS, settings, "已锁定", 2, 3))
        assertEquals("deepseek-chat · 已配置", settingsSummary(SettingsDestination.AI, settings, "已锁定", 2, 0))
        val home = buildSettingsHomeUiState(settings, "已锁定", 2, 3)
        assertEquals(SettingsDestination.entries.toList(), home.categories.map { it.destination })
        assertEquals("2 台主机已授权 · 3 个待恢复", home.categories.first { it.destination == SettingsDestination.DOCUMENTS }.summary)
    }

    @Test
    fun aiDraft_detectsSaveAndDiscardState() {
        val settings = AppSettings(aiBaseUrl = "https://example.test/v1", aiApiKey = "key", aiModel = "model")
        assertFalse(AiSettingsDraft.from(settings).isDirty(settings))
        assertTrue(AiSettingsDraft.from(settings).copy(model = "other").isDirty(settings))
        assertFalse(AiSettingsDraft.from(settings).isDirty(settings))
    }
}
