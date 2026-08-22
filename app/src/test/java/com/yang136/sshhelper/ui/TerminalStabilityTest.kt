package com.yang136.sshhelper.ui

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStabilityTest {
    @Test
    fun outputIsSplitAtBatchBoundaryWithoutChangingBytes() {
        val source = ByteArray(MAX_TERMINAL_RENDER_BATCH_BYTES * 2 + 731) { index -> (index % 251).toByte() }
        val chunks = splitTerminalOutput(source)
        val restored = ByteArrayOutputStream().apply { chunks.forEach(::write) }.toByteArray()

        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.size <= MAX_TERMINAL_RENDER_BATCH_BYTES })
        assertArrayEquals(source, restored)
    }

    @Test
    fun splitCopiesInputSoCallersCannotMutateQueuedOutput() {
        val source = byteArrayOf(1, 2, 3)
        val queued = splitTerminalOutput(source).single()
        source.fill(9)

        assertArrayEquals(byteArrayOf(1, 2, 3), queued)
    }

    @Test
    fun ptyDimensionsAreClampedToSafeRange() {
        assertEquals(2 to 2, normalizePtySize(0, -10))
        assertEquals(80 to 24, normalizePtySize(80, 24))
        assertEquals(500 to 300, normalizePtySize(2_000, 1_000))
        assertEquals(100L, PTY_RESIZE_DEBOUNCE_MS)
    }
}
