package com.yang136.sshhelper.theme

import com.yang136.sshhelper.theme.CropGeometry
import com.yang136.sshhelper.theme.CropTransform
import com.yang136.sshhelper.theme.MAX_CROP_ZOOM
import com.yang136.sshhelper.theme.ImageFocalTransform
import com.yang136.sshhelper.theme.cropSelection
import com.yang136.sshhelper.theme.focalCropSelection
import com.yang136.sshhelper.theme.updateCropTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCropGeometryTest {
    @Test
    fun `cover crop centers a landscape image in a portrait viewport`() {
        val geometry = CropGeometry(4000f, 3000f, 1080f, 1920f)
        val selection = cropSelection(CropTransform(), geometry)

        assertEquals(0.2890625f, selection.left, 0.0001f)
        assertEquals(0f, selection.top, 0.0001f)
        assertEquals(0.421875f, selection.width, 0.0001f)
        assertEquals(1f, selection.height, 0.0001f)
    }

    @Test
    fun `pan is clamped so the viewport never exposes empty space`() {
        val geometry = CropGeometry(4000f, 3000f, 1080f, 1920f)
        val moved = updateCropTransform(
            current = CropTransform(),
            geometry = geometry,
            panX = 100_000f,
            panY = 100_000f,
        )
        val selection = cropSelection(moved, geometry)

        assertEquals(0f, selection.left, 0.0001f)
        assertEquals(0f, selection.top, 0.0001f)
        assertTrue(selection.left + selection.width <= 1.0001f)
        assertTrue(selection.top + selection.height <= 1.0001f)
    }

    @Test
    fun `zoom is capped at four times and preserves crop bounds`() {
        val geometry = CropGeometry(3000f, 4000f, 1080f, 1920f)
        val zoomed = updateCropTransform(
            current = CropTransform(offsetX = 100f),
            geometry = geometry,
            zoomChange = 20f,
        )
        val selection = cropSelection(zoomed, geometry)

        assertEquals(MAX_CROP_ZOOM, zoomed.zoom, 0f)
        assertTrue(selection.left >= 0f && selection.top >= 0f)
        assertTrue(selection.left + selection.width <= 1.0001f)
        assertTrue(selection.top + selection.height <= 1.0001f)
    }

    @Test
    fun `square source produces requested wide viewport ratio`() {
        val geometry = CropGeometry(2000f, 2000f, 1600f, 900f)
        val selection = cropSelection(CropTransform(), geometry)

        assertEquals(1f, selection.width, 0.0001f)
        assertEquals(0.5625f, selection.height, 0.0001f)
        assertEquals(0.21875f, selection.top, 0.0001f)
    }

    @Test
    fun `one focal point adapts between portrait and landscape`() {
        val focal = ImageFocalTransform(focusX = 0.75f, focusY = 0.4f)
        val portrait = focalCropSelection(4000f, 3000f, 1080f, 1920f, focal)
        val landscape = focalCropSelection(4000f, 3000f, 1920f, 1080f, focal)

        assertEquals(0.421875f, portrait.width, 0.0001f)
        assertEquals(1f, portrait.height, 0.0001f)
        assertEquals(1f, landscape.width, 0.0001f)
        assertEquals(0.75f, landscape.height, 0.0001f)
        assertTrue(portrait.left + portrait.width <= 1f)
        assertTrue(landscape.top + landscape.height <= 1f)
    }

    @Test
    fun `focal zoom remains bounded inside source`() {
        val crop = focalCropSelection(
            3000f, 4000f, 1600f, 900f,
            ImageFocalTransform(focusX = 1f, focusY = 0f, zoom = 4f),
        )
        assertTrue(crop.left >= 0f && crop.top >= 0f)
        assertTrue(crop.left + crop.width <= 1.0001f)
        assertTrue(crop.top + crop.height <= 1.0001f)
    }

    @Test
    fun `corrupt non finite focal metadata falls back to centered crop`() {
        val crop = focalCropSelection(
            4000f,
            3000f,
            1080f,
            1920f,
            ImageFocalTransform(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN),
        )

        assertEquals(0.2890625f, crop.left, 0.0001f)
        assertEquals(0f, crop.top, 0.0001f)
        assertEquals(0.421875f, crop.width, 0.0001f)
        assertEquals(1f, crop.height, 0.0001f)
    }
}
