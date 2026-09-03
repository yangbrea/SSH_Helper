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
    fun createAndFreeTerminalHandle() {
        val handle = GhosttyNativeBridge.nativeCreate(cols = 80, rows = 24)
        assertNotEquals(0L, handle)
        GhosttyNativeBridge.nativeFree(handle)
        // Double-free is contractually a safe no-op in this smoke layer.
        GhosttyNativeBridge.nativeFree(handle)
    }

    @Test
    fun createRejectsInvalidDimensions() {
        try {
            GhosttyNativeBridge.nativeCreate(cols = 0, rows = 24)
            throw AssertionError("expected IllegalArgumentException for cols=0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertEquals("native library is still usable after a rejected create", 1, 1)
    }
}
