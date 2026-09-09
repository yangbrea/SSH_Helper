package com.yang136.sshhelper.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhosttyRenderFrameStoreTest {
    @Test
    fun partialSnapshotPreservesUntouchedRows() {
        val store = GhosttyRenderFrameStore()
        store.apply(snapshot(dirty = SNAPSHOT_DIRTY_FULL, rows = 3, textByRow = mapOf(0 to "one", 1 to "two", 2 to "three")))

        val change = store.apply(snapshot(dirty = SNAPSHOT_DIRTY_PARTIAL, rows = 3, textByRow = mapOf(1 to "changed")))

        requireNotNull(change)
        assertFalse(change.fullRedraw)
        assertEquals(0, change.firstDirtyRow) // old cursor row
        assertEquals(1, change.lastDirtyRow)
        assertEquals("one", store.textAt(0))
        assertEquals("changed", store.textAt(1))
        assertEquals("three", store.textAt(2))
    }

    @Test
    fun mismatchedPartialSnapshotIsIgnoredUntilFullResizeFrameArrives() {
        val store = GhosttyRenderFrameStore()
        store.apply(snapshot(dirty = SNAPSHOT_DIRTY_FULL, rows = 4, textByRow = mapOf(0 to "before")))

        val ignored = store.apply(
            snapshot(dirty = SNAPSHOT_DIRTY_PARTIAL, cols = 8, rows = 2, textByRow = mapOf(0 to "stale")),
        )

        assertNull(ignored)
        assertEquals(4, store.currentFrame()?.snapshot?.rows)
        assertEquals("before", store.textAt(0))

        val applied = store.apply(
            snapshot(dirty = SNAPSHOT_DIRTY_FULL, cols = 8, rows = 2, textByRow = mapOf(0 to "after")),
        )

        requireNotNull(applied)
        assertTrue(applied.fullRedraw)
        assertEquals(2, store.currentFrame()?.snapshot?.rows)
        assertEquals("after", store.textAt(0))

        val closeKeyboardPartial = store.apply(
            snapshot(dirty = SNAPSHOT_DIRTY_PARTIAL, cols = 12, rows = 4, textByRow = mapOf(0 to "incomplete")),
        )
        assertNull(closeKeyboardPartial)
        assertEquals(2, store.currentFrame()?.snapshot?.rows)
        assertEquals("after", store.textAt(0))

        val closeKeyboardFull = store.apply(
            snapshot(dirty = SNAPSHOT_DIRTY_FULL, cols = 12, rows = 4, textByRow = mapOf(0 to "restored")),
        )
        requireNotNull(closeKeyboardFull)
        assertTrue(closeKeyboardFull.fullRedraw)
        assertEquals(4, store.currentFrame()?.snapshot?.rows)
        assertEquals("restored", store.textAt(0))
    }

    @Test
    fun cleanSnapshotCannotEraseCompleteFrame() {
        val store = GhosttyRenderFrameStore()
        store.apply(snapshot(dirty = SNAPSHOT_DIRTY_FULL, rows = 2, textByRow = mapOf(0 to "stable")))

        val ignored = store.apply(snapshot(dirty = SNAPSHOT_DIRTY_NONE, rows = 2))

        assertNull(ignored)
        assertEquals("stable", store.textAt(0))
        assertTrue(store.currentFrame()?.snapshot?.cursorVisible == true)
    }

    @Test
    fun staleFullResizeFrameIsIgnoredWhenNewerSizeIsExpected() {
        val store = GhosttyRenderFrameStore()
        store.apply(snapshot(dirty = SNAPSHOT_DIRTY_FULL, cols = 12, rows = 4, textByRow = mapOf(0 to "original")))
        store.expectSize(cols = 8, rows = 2)

        val stale = store.apply(
            snapshot(dirty = SNAPSHOT_DIRTY_FULL, cols = 10, rows = 3, textByRow = mapOf(0 to "stale")),
        )

        assertNull(stale)
        assertEquals("original", store.textAt(0))

        val latest = store.apply(
            snapshot(dirty = SNAPSHOT_DIRTY_FULL, cols = 8, rows = 2, textByRow = mapOf(0 to "latest")),
        )
        requireNotNull(latest)
        assertEquals("latest", store.textAt(0))
    }

    @Test
    fun fullSnapshotClearsCellsMissingFromNewFrame() {
        val store = GhosttyRenderFrameStore()
        store.apply(snapshot(dirty = SNAPSHOT_DIRTY_FULL, rows = 2, textByRow = mapOf(0 to "old", 1 to "old-2")))

        store.apply(snapshot(dirty = SNAPSHOT_DIRTY_FULL, rows = 2, textByRow = mapOf(1 to "new")))

        assertEquals("", store.textAt(0))
        assertEquals("new", store.textAt(1))
    }

    @Test
    fun staleGenerationCannotOverwriteResetFrame() {
        val store = GhosttyRenderFrameStore()
        store.apply(snapshot(SNAPSHOT_DIRTY_FULL, rows = 2, textByRow = mapOf(0 to "old"), generation = 1))
        store.apply(snapshot(SNAPSHOT_DIRTY_FULL, rows = 2, textByRow = mapOf(0 to "new"), generation = 2))

        val stale = store.apply(
            snapshot(SNAPSHOT_DIRTY_FULL, rows = 2, textByRow = mapOf(0 to "late-old"), generation = 1),
        )

        assertNull(stale)
        assertEquals("new", store.textAt(0))
    }

    @Test
    fun newGenerationMustStartWithFullFrame() {
        val store = GhosttyRenderFrameStore()
        store.apply(snapshot(SNAPSHOT_DIRTY_FULL, rows = 2, textByRow = mapOf(0 to "old"), generation = 1))

        val partial = store.apply(
            snapshot(SNAPSHOT_DIRTY_PARTIAL, rows = 2, textByRow = mapOf(0 to "unsafe"), generation = 2),
        )

        assertNull(partial)
        assertEquals("old", store.textAt(0))
    }

    @Test
    fun partialFramePreservesWrapAndSearchMetadata() {
        val store = GhosttyRenderFrameStore()
        val initial = snapshot(SNAPSHOT_DIRTY_FULL, rows = 2, textByRow = mapOf(0 to "url", 1 to "tail"))
        store.apply(
            initial.copy(
                rowsData = initial.rowsData.map {
                    if (it.rowIndex == 0) it.copy(wrap = true) else it.copy(wrapContinuation = true)
                },
            ),
        )
        store.apply(snapshot(SNAPSHOT_DIRTY_PARTIAL, rows = 2, textByRow = mapOf(1 to "next")))

        assertTrue(store.currentFrame()?.rowMetadata?.get(0)?.wrap == true)
    }

    private fun GhosttyRenderFrameStore.textAt(row: Int): String =
        currentFrame()?.rows?.get(row)?.filterNotNull()?.joinToString("") { it.text }.orEmpty()

    private fun snapshot(
        dirty: Int,
        cols: Int = 12,
        rows: Int,
        textByRow: Map<Int, String> = emptyMap(),
        generation: Int = 1,
    ): GhosttyRenderSnapshot = GhosttyRenderSnapshot(
        version = SNAPSHOT_VERSION,
        dirtyKind = dirty,
        cols = cols,
        rows = rows,
        backgroundArgb = 0xFF000000.toInt(),
        foregroundArgb = 0xFFFFFFFF.toInt(),
        cursorArgb = 0xFF00FF00.toInt(),
        cursorX = 0,
        cursorY = 0,
        cursorStyle = 1,
        cursorVisible = true,
        cursorBlinking = false,
        generation = generation,
        rowsData = textByRow.map { (row, text) ->
            GhosttyRenderRow(
                rowIndex = row,
                cells = text.map { char ->
                    GhosttyRenderCell(
                        fgArgb = 0xFFFFFFFF.toInt(),
                        bgArgb = 0xFF000000.toInt(),
                        flags = 0,
                        text = char.toString(),
                    )
                },
            )
        },
    )
}
