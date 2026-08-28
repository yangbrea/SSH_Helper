package com.yang136.sshhelper.theme

import com.yang136.sshhelper.settings.ImageThemeVariant
import com.yang136.sshhelper.theme.ImageColorSample
import com.yang136.sshhelper.theme.contrastRatio
import com.yang136.sshhelper.theme.deriveImageThemePalette
import com.yang136.sshhelper.theme.imageThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePaletteAlgorithmTest {
    @Test
    fun `colorful dominant sample becomes immersive seed`() {
        val palette = deriveImageThemePalette(
            samples = listOf(
                ImageColorSample(0xFF1769AA.toInt(), 700),
                ImageColorSample(0xFFF2A93B.toInt(), 260),
                ImageColorSample(0xFF66717A.toInt(), 400),
            ),
            averageArgb = 0xFF55768A.toInt(),
        )

        assertEquals(0xFF1769AA.toInt(), palette.vibrantArgb)
        assertEquals(ImageThemeVariant.IMMERSIVE, palette.recommendedVariant)
        assertNotEquals(palette.vibrantArgb, palette.secondaryArgb)
    }

    @Test
    fun `grayscale image falls back to green and soft recipe`() {
        val palette = deriveImageThemePalette(
            samples = listOf(
                ImageColorSample(0xFF444444.toInt(), 800),
                ImageColorSample(0xFFB0B0B0.toInt(), 500),
            ),
            averageArgb = 0xFF777777.toInt(),
        )

        assertEquals(0xFF00E676.toInt(), palette.vibrantArgb)
        assertEquals(ImageThemeVariant.SOFT, palette.recommendedVariant)
    }

    @Test
    fun `very bright image recommends bright recipe`() {
        val palette = deriveImageThemePalette(
            samples = listOf(
                ImageColorSample(0xFFBBDDF5.toInt(), 900),
                ImageColorSample(0xFFE6C7D8.toInt(), 400),
            ),
            averageArgb = 0xFFF0F3F5.toInt(),
        )

        assertEquals(ImageThemeVariant.BRIGHT, palette.recommendedVariant)
    }

    @Test
    fun `all generated schemes satisfy text contrast`() {
        val palette = deriveImageThemePalette(
            samples = listOf(
                ImageColorSample(0xFFFFD000.toInt(), 600),
                ImageColorSample(0xFF6C35D4.toInt(), 450),
                ImageColorSample(0xFF928878.toInt(), 300),
            ),
            averageArgb = 0xFF9E8E70.toInt(),
        )

        ImageThemeVariant.entries.forEach { variant ->
            val tokens = imageThemeTokens(palette, variant)
            assertTrue(contrastRatio(tokens.onBackground, tokens.background) >= 4.5)
            assertTrue(contrastRatio(tokens.onSurfaceVariant, tokens.surfaceVariant) >= 4.5)
            assertTrue(contrastRatio(tokens.onPrimary, tokens.primary) >= 4.5)
        }
    }

    @Test
    fun `three recipes produce distinct surfaces and expected brightness`() {
        val palette = deriveImageThemePalette(
            listOf(ImageColorSample(0xFF009688.toInt(), 1000)),
            0xFF365B58.toInt(),
        )
        val immersive = imageThemeTokens(palette, ImageThemeVariant.IMMERSIVE)
        val soft = imageThemeTokens(palette, ImageThemeVariant.SOFT)
        val bright = imageThemeTokens(palette, ImageThemeVariant.BRIGHT)

        assertTrue(immersive.dark)
        assertTrue(soft.dark)
        assertTrue(!bright.dark)
        assertNotEquals(immersive.primary, soft.primary)
        assertNotEquals(immersive.background, bright.background)
    }
}
