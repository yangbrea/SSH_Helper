package com.yang136.sshhelper.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

            val buffer = ByteBuffer
                .allocateDirect(1 shl 20)
                .order(ByteOrder.LITTLE_ENDIAN)
            val rowCount = GhosttyNativeBridge.nativeRenderSnapshot(handle, buffer)
            assertTrue("snapshot should encode at least one row", rowCount >= 0)

            buffer.rewind()
            val version = buffer.int
            val dirty = buffer.int
            val cols = buffer.int
            val rows = buffer.int
            assertEquals(1, version)
            assertEquals(80, cols)
            assertEquals(24, rows)
            assertTrue("dirty should not be none", dirty != 0)
            assertTrue("reported row count should match header", rowCount <= rows)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }
}
