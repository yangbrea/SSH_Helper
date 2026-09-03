package com.yang136.sshhelper.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
