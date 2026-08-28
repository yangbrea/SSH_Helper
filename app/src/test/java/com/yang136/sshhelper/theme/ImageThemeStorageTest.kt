package com.yang136.sshhelper.theme

import com.yang136.sshhelper.theme.calculateInSampleSize
import com.yang136.sshhelper.theme.calculateWorkingInSampleSize
import com.yang136.sshhelper.theme.capRecentEntries
import com.yang136.sshhelper.theme.cropPixelBounds
import com.yang136.sshhelper.theme.CropSelection
import com.yang136.sshhelper.theme.nextActiveIdAfterDelete
import com.yang136.sshhelper.theme.recoverInterruptedReplace
import com.yang136.sshhelper.theme.safeReplace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ImageThemeStorageTest {
    @Test
    fun `large images are decoded near target edge`() {
        assertEquals(1, calculateInSampleSize(1920, 1080, 1920))
        assertEquals(2, calculateInSampleSize(6000, 4000, 1920))
        assertEquals(4, calculateInSampleSize(12000, 8000, 1920))
    }

    @Test
    fun `crop working decode respects edge and pixel limits`() {
        assertEquals(4, calculateWorkingInSampleSize(12_000, 8_000, 4096, 12_000_000L))
        assertEquals(2, calculateWorkingInSampleSize(6000, 4000, 4096, 12_000_000L))
        assertEquals(1, calculateWorkingInSampleSize(3000, 2000, 4096, 12_000_000L))
    }

    @Test
    fun `normalized crop maps to safe pixel bounds`() {
        assertTrue(
            cropPixelBounds(
                4000,
                3000,
                CropSelection(left = 0.25f, top = 0.1f, width = 0.5f, height = 0.8f),
            ).contentEquals(intArrayOf(1000, 300, 3000, 2700)),
        )
    }

    @Test
    fun `recent history keeps newest three and falls back after active deletion`() {
        assertEquals(listOf("new", "middle", "old"), capRecentEntries(listOf("new", "middle", "old", "evicted")))
        assertEquals("middle", nextActiveIdAfterDelete(listOf("new", "middle", "old"), "new", "new"))
        assertEquals("new", nextActiveIdAfterDelete(listOf("new", "middle", "old"), "new", "old"))
        assertEquals(null, nextActiveIdAfterDelete(listOf("only"), "only", "only"))
    }

    @Test
    fun `safe replace publishes new file and removes backup`() {
        val directory = Files.createTempDirectory("image-theme-replace").toFile()
        val target = directory.resolve("background.jpg").apply { writeText("old") }
        val temporary = directory.resolve("background.tmp").apply { writeText("new") }
        val backup = directory.resolve("background.bak")

        assertTrue(safeReplace(temporary, target, backup))
        assertEquals("new", target.readText())
        assertFalse(temporary.exists())
        assertFalse(backup.exists())
        directory.deleteRecursively()
    }

    @Test
    fun `startup recovery restores an interrupted replacement`() {
        val directory = Files.createTempDirectory("image-theme-recovery").toFile()
        val target = directory.resolve("background.jpg")
        val temporary = directory.resolve("background.tmp")
        val backup = directory.resolve("background.bak").apply { writeText("previous") }

        recoverInterruptedReplace(target, temporary, backup)

        assertEquals("previous", target.readText())
        assertFalse(backup.exists())
        directory.deleteRecursively()
    }
}
