package com.yang136.sshhelper.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalReplayBufferTest {
    @Test
    fun preservesSmallReplayExactly() {
        val buffer = TerminalReplayBuffer(32)
        buffer.append("hello".encodeToByteArray())
        buffer.append(" world".encodeToByteArray())

        assertArrayEquals("hello world".encodeToByteArray(), buffer.snapshot())
    }

    @Test
    fun overflowIsBoundedAndStartsFromTerminalGroundState() {
        val buffer = TerminalReplayBuffer(16)
        buffer.append("\u001b]0;unterminated-title".encodeToByteArray())

        val snapshot = buffer.snapshot()
        assertTrue(snapshot.size <= 16)
        assertEquals(0x1B, snapshot[0].toInt() and 0xFF)
        assertEquals('c'.code, snapshot[1].toInt() and 0xFF)
    }

    @Test
    fun oversizedSingleChunkRetainsNewestBytes() {
        val buffer = TerminalReplayBuffer(10)
        buffer.append("0123456789ABCDEF".encodeToByteArray())

        val snapshot = buffer.snapshot()
        assertEquals(10, snapshot.size)
        assertEquals("\u001bc89ABCDEF", snapshot.decodeToString())
    }
}
