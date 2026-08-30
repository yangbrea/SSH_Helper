package com.yang136.sshhelper.ui.adaptive

import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {

    @Test
    fun layoutMode_landscape_whenWidthExceedsHeight() {
        assertEquals(SshLayoutMode.LANDSCAPE, layoutMode(915.dp, 412.dp))
        assertEquals(SshLayoutMode.LANDSCAPE, layoutMode(800.dp, 400.dp))
    }

    @Test
    fun layoutMode_portrait_whenHeightDominates() {
        assertEquals(SshLayoutMode.PORTRAIT, layoutMode(412.dp, 915.dp))
        assertEquals(SshLayoutMode.PORTRAIT, layoutMode(360.dp, 800.dp))
    }

    @Test
    fun layoutMode_square_isPortrait() {
        assertEquals(SshLayoutMode.PORTRAIT, layoutMode(500.dp, 500.dp))
    }

    @Test
    fun hardwareKeyboard_detected_whenQwertyVisible() {
        assertTrue(hasHardwareKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO))
    }

    @Test
    fun hardwareKeyboard_notDetected_whenQwertyHidden() {
        assertFalse(hasHardwareKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES))
    }

    @Test
    fun hardwareKeyboard_notDetected_withoutQwerty() {
        assertFalse(hasHardwareKeyboard(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_NO))
        assertFalse(hasHardwareKeyboard(Configuration.KEYBOARD_12KEY, Configuration.HARDKEYBOARDHIDDEN_NO))
    }
}
