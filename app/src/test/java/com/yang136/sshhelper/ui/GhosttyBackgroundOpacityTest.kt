package com.yang136.sshhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GhosttyBackgroundOpacityTest {
    @Test
    fun opaqueBackgroundKeepsRgbAndFullAlpha() {
        assertEquals(0xFF123456.toInt(), applyOpacityToArgb(0xFF123456.toInt(), 1f))
    }

    @Test
    fun translucentBackgroundChangesOnlyAlpha() {
        assertEquals(0xCC123456.toInt(), applyOpacityToArgb(0xFF123456.toInt(), 0.8f))
    }

    @Test
    fun invalidOpacityFallsBackToOpaque() {
        assertEquals(0xFF123456.toInt(), applyOpacityToArgb(0x80123456.toInt(), Float.NaN))
    }
}
