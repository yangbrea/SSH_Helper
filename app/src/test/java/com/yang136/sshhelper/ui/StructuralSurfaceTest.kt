package com.yang136.sshhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StructuralSurfaceTest {
    @Test
    fun presetThemeUsesBalancedStructuralSurfaceAlpha() {
        assertEquals(
            PRESET_CONTENT_SURFACE_ALPHA,
            structuralSurfaceAlpha(false, StructuralSurfaceRole.CONTENT),
        )
        assertEquals(
            PRESET_NAVIGATION_SURFACE_ALPHA,
            structuralSurfaceAlpha(false, StructuralSurfaceRole.NAVIGATION),
        )
    }

    @Test
    fun imageThemeKeepsBackgroundVisibleBehindStructuralSurfaces() {
        assertEquals(
            IMAGE_CONTENT_SURFACE_ALPHA,
            structuralSurfaceAlpha(true, StructuralSurfaceRole.CONTENT),
        )
        assertEquals(
            IMAGE_NAVIGATION_SURFACE_ALPHA,
            structuralSurfaceAlpha(true, StructuralSurfaceRole.NAVIGATION),
        )
    }
}
