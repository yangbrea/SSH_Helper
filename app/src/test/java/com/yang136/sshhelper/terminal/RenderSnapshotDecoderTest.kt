package com.yang136.sshhelper.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSnapshotDecoderTest {
    private fun bufferWith(vararg ints: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(ints.size * Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        ints.forEach(buffer::putInt)
        buffer.rewind()
        return buffer
    }

    @Test
    fun emptySnapshotDecodesHeader() {
        val buffer = bufferWith(
            SNAPSHOT_VERSION,
            0, // dirty none
            80,
            24,
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            -1,
            -1,
            0,
            0,
            0,
            0,
            7, // generation
            0xFF00FF00.toInt(), // cursor
        )

        val snapshot = RenderSnapshotDecoder.decode(buffer, buffer.capacity())
        requireNotNull(snapshot)

        assertEquals(80, snapshot.cols)
        assertEquals(24, snapshot.rows)
        assertFalse(snapshot.isDirty)
        assertEquals(7, snapshot.generation)
        assertEquals(0xFF00FF00.toInt(), snapshot.cursorArgb)
        assertTrue(snapshot.rowsData.isEmpty())
    }

    @Test
    fun dirtyRowDecodesCellsAndUtf8Text() {
        val text = "hi"
        val textBytes = text.toByteArray(Charsets.UTF_8)

        // Header
        val header = intArrayOf(
            SNAPSHOT_VERSION,
            2, // full dirty
            4,
            2,
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            1,
            0,
            1,
            1,
            0,
            1, // row count
            1, // generation
            0xFFFFFF00.toInt(), // cursor
        )
        val body = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        header.forEach(body::putInt)

        // Row 0: two cells.
        body.putInt(0) // row index
        body.putInt(2) // cell count

        // Cell 0: foreground red, background black, bold flag.
        body.putInt(0xFFFF0000.toInt())
        body.putInt(0xFF000000.toInt())
        body.putShort(CELL_FLAG_BOLD.toShort())
        body.putShort(textBytes.size.toShort())
        body.put(textBytes)

        // Cell 1: empty.
        body.putInt(0xFFFFFFFF.toInt())
        body.putInt(0xFF000000.toInt())
        body.putShort(0)
        body.putShort(0)

        val length = body.position()
        body.rewind()
        val snapshot = RenderSnapshotDecoder.decode(body, length)
        requireNotNull(snapshot)

        assertTrue(snapshot.isDirty)
        assertEquals(1, snapshot.rowsData.size)
        val row = snapshot.rowsData.single()
        assertEquals(0, row.rowIndex)
        assertEquals(2, row.cells.size)
        assertEquals("hi", row.cells[0].text)
        assertTrue(row.cells[0].bold)
        assertFalse(row.cells[1].bold)
        assertEquals("", row.cells[1].text)
    }

    @Test
    fun truncatedBufferReturnsNull() {
        val buffer = bufferWith(SNAPSHOT_VERSION, 0)
        assertEquals(null, RenderSnapshotDecoder.decode(buffer, buffer.capacity()))
    }
}
