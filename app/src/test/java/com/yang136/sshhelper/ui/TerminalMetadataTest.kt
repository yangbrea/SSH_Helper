package com.yang136.sshhelper.ui

import com.yang136.sshhelper.terminal.GhosttyRenderCell
import com.yang136.sshhelper.terminal.GhosttyRenderFrameStore
import com.yang136.sshhelper.terminal.GhosttyRenderRow
import com.yang136.sshhelper.terminal.GhosttyRenderSnapshot
import com.yang136.sshhelper.terminal.SNAPSHOT_DIRTY_FULL
import com.yang136.sshhelper.terminal.SNAPSHOT_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalMetadataTest {
    @Test
    fun metadataDropsControlsAndCapsLength() {
        val value = "title\n\u0007" + "x".repeat(400)
        val sanitized = sanitizeTerminalMetadata(value)
        assertEquals("title" + "x".repeat(251), sanitized)
    }

    @Test
    fun detectsHttpLinkAcrossSoftWrappedRows() {
        val frame = frame(listOf("go https://exam", "ple.com/path)."), wrapFirst = true)
        assertEquals("https://example.com/path", TerminalUrlDetector.findAt(frame, 1, 3))
    }

    @Test
    fun rejectsNonHttpText() {
        assertNull(TerminalUrlDetector.findAt(frame(listOf("file:///tmp/secret")), 0, 8))
    }

    @Test
    fun rejectsHttpLinkLongerThanProtocolLimit() {
        val value = "https://example.com/" + "x".repeat(4096)
        assertNull(TerminalUrlDetector.findAt(frame(listOf(value)), 0, 10))
    }

    private fun frame(lines: List<String>, wrapFirst: Boolean = false): GhosttyRenderFrameStore.Frame {
        val rows = lines.mapIndexed { index, line ->
            GhosttyRenderRow(
                rowIndex = index,
                cells = line.map(::cell),
                wrap = wrapFirst && index == 0,
                wrapContinuation = wrapFirst && index == 1,
            )
        }
        val snapshot = GhosttyRenderSnapshot(
            version = SNAPSHOT_VERSION,
            dirtyKind = SNAPSHOT_DIRTY_FULL,
            cols = lines.maxOf { it.length },
            rows = lines.size,
            backgroundArgb = 0xff000000.toInt(),
            foregroundArgb = 0xffffffff.toInt(),
            cursorArgb = 0xffffffff.toInt(),
            cursorX = 0,
            cursorY = 0,
            cursorStyle = 1,
            cursorVisible = true,
            cursorBlinking = false,
            generation = 1,
            rowsData = rows,
        )
        val store = GhosttyRenderFrameStore()
        requireNotNull(store.apply(snapshot))
        return requireNotNull(store.currentFrame())
    }

    private fun cell(char: Char) = GhosttyRenderCell(
        fgArgb = 0xffffffff.toInt(),
        bgArgb = 0xff000000.toInt(),
        flags = 0,
        text = char.toString(),
    )
}
