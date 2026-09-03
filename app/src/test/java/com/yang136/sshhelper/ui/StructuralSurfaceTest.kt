package com.yang136.sshhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StructuralSurfaceTest {
    @Test
    fun presetThemeUsesTranslucentStructuralSurfaceAlpha() {
        assertEquals(0.80f, PRESET_CONTENT_SURFACE_ALPHA)
        assertEquals(0.86f, PRESET_NAVIGATION_SURFACE_ALPHA)
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
        assertEquals(0.78f, IMAGE_CONTENT_SURFACE_ALPHA)
        assertEquals(0.84f, IMAGE_NAVIGATION_SURFACE_ALPHA)
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
