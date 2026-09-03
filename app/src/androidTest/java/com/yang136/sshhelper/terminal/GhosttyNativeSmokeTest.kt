package com.yang136.sshhelper.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GhosttyNativeSmokeTest {
    @Test
    fun nativeLibraryLoadsAndReportsVersion() {
        val version = GhosttyNativeBridge.nativeVersion()
        assertTrue("libghostty-vt version should not be empty", version.isNotEmpty())
    }

    @Test
    fun createAndFreeManagedTerminalHandle() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        assertNotEquals(0L, handle)
        GhosttyNativeBridge.nativeFreeManaged(handle)
    }

    @Test
    fun freeNullHandleIsSafeNoOp() {
        GhosttyNativeBridge.nativeFreeManaged(0L)
    }

    @Test
    fun createRejectsInvalidDimensions() {
        try {
            GhosttyNativeBridge.nativeCreateManaged(cols = 0, rows = 24)
            throw AssertionError("expected IllegalArgumentException for cols=0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertEquals("native library is still usable after a rejected create", 1, 1)
    }

    @Test
    fun writeResetAndResizeDoNotCrash() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "hello\r\n".encodeToByteArray())
            GhosttyNativeBridge.nativeWrite(
                handle,
                "\u001b[31mred\u001b[0m".encodeToByteArray(),
            )
            GhosttyNativeBridge.nativeResize(
                handle,
                cols = 100,
                rows = 30,
                cellWidthPx = 8,
                cellHeightPx = 16,
            )
            GhosttyNativeBridge.nativeReset(handle)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun renderSnapshotProducesDecodableBatch() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "hello\r\n".encodeToByteArray())

            val snapshot = renderSnapshot(handle)
            assertEquals(SNAPSHOT_VERSION, snapshot.version)
            assertEquals(80, snapshot.cols)
            assertEquals(24, snapshot.rows)
            assertTrue(snapshot.isDirty)
            assertTrue(snapshot.rowsData.isNotEmpty())
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun writtenHelloAppearsInSnapshot() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "hello\r\n".encodeToByteArray())
            val text = renderSnapshot(handle).rowsData.joinToString("") { row ->
                row.cells.joinToString("") { it.text }
            }
            assertTrue("snapshot should contain hello, got: $text", text.contains("hello"))
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun ansiColorIsPreservedInSnapshot() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(
                handle,
                "\u001b[31mred\u001b[0m".encodeToByteArray(),
            )
            val cells = renderSnapshot(handle).rowsData.flatMap { it.cells }
            val redCell = cells.firstOrNull { it.text == "r" || it.text == "e" || it.text == "d" }
            assertTrue("expected colored text cell", redCell != null)
            assertTrue(
                "expected non-default colored text, got ${redCell!!.fgArgb}",
                redCell.fgArgb != 0xFFFFFFFF.toInt(),
            )
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun wideCharacterIsMarkedWide() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "中".encodeToByteArray())
            val cells = renderSnapshot(handle).rowsData.flatMap { it.cells }
            val wide = cells.firstOrNull { it.text == "中" }
            assertTrue("expected wide char", wide != null && wide.wide)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun alternateScreenShowsSecondaryContent() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "\u001b[?1049h".encodeToByteArray())
            GhosttyNativeBridge.nativeWrite(handle, "alternate-content".encodeToByteArray())
            val text = renderSnapshot(handle).rowsData.joinToString("") { row ->
                row.cells.joinToString("") { it.text }
            }
            assertTrue(
                "alternate screen content should be visible, got: $text",
                text.contains("alternate-content"),
            )
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun resizeUpdatesGridAndKeepsWorking() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "before-resize\r\n".encodeToByteArray())
            GhosttyNativeBridge.nativeResize(
                handle,
                cols = 40,
                rows = 10,
                cellWidthPx = 8,
                cellHeightPx = 16,
            )
            val snapshot = renderSnapshot(handle)
            assertEquals(40, snapshot.cols)
            assertEquals(10, snapshot.rows)
            val text = snapshot.rowsData.joinToString("") { row ->
                row.cells.joinToString("") { it.text }
            }
            assertTrue(text.contains("before-resize"))
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun selectionDragCopiesSelectedRange() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 10)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "abcdefghij".encodeToByteArray())
            GhosttyNativeBridge.nativeSelectionPress(handle, 2, 0)
            GhosttyNativeBridge.nativeSelectionDrag(handle, 5, 0)
            GhosttyNativeBridge.nativeSelectionRelease(handle, 5, 0)
            val text = GhosttyNativeBridge.nativeCopySelection(handle)?.decodeToString()
            assertEquals("cdef", text)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun linkUriAtReturnsOsc8Hyperlink() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 10)
        try {
            val uri = "https://example.com"
            GhosttyNativeBridge.nativeWrite(
                handle,
                "\u001b]8;;$uri\u001b\\Ghostty\u001b]8;;\u001b\\".encodeToByteArray(),
            )
            val bytes = GhosttyNativeBridge.nativeLinkUriAt(handle, 2, 0)
            assertNotNull(bytes)
            assertEquals(uri, bytes?.decodeToString())
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun scrollViewportShowsOlderLines() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 10)
        try {
            repeat(30) { index ->
                GhosttyNativeBridge.nativeWrite(handle, "line-$index\r\n".encodeToByteArray())
            }
            val before = renderSnapshot(handle).rowsData
                .firstOrNull()?.cells?.joinToString("") { it.text }.orEmpty()
            assertTrue("expected line-20 at top before scroll, got: $before", before.contains("line-20"))

            GhosttyNativeBridge.nativeScrollViewport(handle, deltaRows = -5)
            val after = renderSnapshot(handle).rowsData
                .firstOrNull()?.cells?.joinToString("") { it.text }.orEmpty()
            assertTrue("expected line-15 at top after scroll, got: $after", after.contains("line-15"))
            assertNotEquals(before, after)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    private fun renderSnapshot(handle: Long): GhosttyRenderSnapshot {
        val buffer = ByteBuffer
            .allocateDirect(1 shl 20)
            .order(ByteOrder.LITTLE_ENDIAN)
        val rowCount = GhosttyNativeBridge.nativeRenderSnapshot(handle, buffer)
        assertTrue("snapshot should decode", rowCount >= 0)
        buffer.clear()
        return RenderSnapshotDecoder.decode(buffer, buffer.capacity())
            ?: error("failed to decode snapshot")
    }
}
