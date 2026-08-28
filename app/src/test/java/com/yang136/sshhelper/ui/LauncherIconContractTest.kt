package com.yang136.sshhelper.ui

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only contract checks for launcher resources and their approved source artwork. */
class LauncherIconContractTest {
    private val resources = File("src/main/res")

    @Test
    fun adaptiveIconsUseWhiteBackgroundAndRasterArtwork() {
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val xml = File(resources, "mipmap-anydpi-v26/$name").readText()
            assertTrue(xml.contains("@color/launcher_background"))
            assertTrue(xml.contains("@drawable/ic_launcher_foreground"))
            assertTrue(xml.contains("@drawable/ic_launcher_monochrome"))
        }

        val colors = File(resources, "values/colors.xml").readText()
        assertTrue("启动器背景必须保持纯白", colors.contains("#FFFFFF"))

        assertPng("drawable-nodpi/ic_launcher_foreground.png", 432)
        assertPng("drawable-nodpi/ic_launcher_monochrome.png", 432)
    }

    @Test
    fun everyLegacyDensityHasSquareAndRoundArtworkAtContractSize() {
        mapOf(
            "mdpi" to 48,
            "hdpi" to 72,
            "xhdpi" to 96,
            "xxhdpi" to 144,
            "xxxhdpi" to 192,
        ).forEach { (density, size) ->
            assertPng("mipmap-$density/ic_launcher.png", size)
            assertPng("mipmap-$density/ic_launcher_round.png", size)
        }
    }

    @Test
    fun approvedImagegenArtworkIsRetainedAsTheBrandSource() {
        val source = File("../design/branding/concepts/young-orbit-shield-imagegen-reference.png")
        assertTrue("必须保留已确认的原始概念图", source.isFile)
        val image = ImageIO.read(source)
        assertEquals(1254, image.width)
        assertEquals(1254, image.height)
    }

    private fun assertPng(relativePath: String, expectedSize: Int) {
        val file = File(resources, relativePath)
        assertTrue("缺少启动器资源：$relativePath", file.isFile)
        val image = ImageIO.read(file)
        assertEquals("$relativePath 宽度错误", expectedSize, image.width)
        assertEquals("$relativePath 高度错误", expectedSize, image.height)
        assertTrue("$relativePath 应包含透明通道", image.colorModel.hasAlpha())
    }
}
