package com.yang136.sshhelper.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Cell style flags produced by nativeRenderSnapshot.
 */
internal const val SNAPSHOT_VERSION = 4

internal const val SNAPSHOT_DIRTY_NONE = 0
internal const val SNAPSHOT_DIRTY_PARTIAL = 1
internal const val SNAPSHOT_DIRTY_FULL = 2

internal const val CELL_FLAG_BOLD = 1 shl 0
internal const val CELL_FLAG_ITALIC = 1 shl 1
internal const val CELL_FLAG_FAINT = 1 shl 2
internal const val CELL_FLAG_INVERSE = 1 shl 3
internal const val CELL_FLAG_UNDERLINE = 1 shl 4
internal const val CELL_FLAG_UNDERLINE_STYLE_SHIFT = 10
internal const val CELL_FLAG_UNDERLINE_STYLE_MASK = 0x7 shl CELL_FLAG_UNDERLINE_STYLE_SHIFT
internal const val CELL_FLAG_STRIKETHROUGH = 1 shl 5
internal const val CELL_FLAG_OVERLINE = 1 shl 6
internal const val CELL_FLAG_INVISIBLE = 1 shl 7
internal const val CELL_FLAG_WIDE = 1 shl 8
internal const val CELL_FLAG_WIDE_TAIL = 1 shl 9
internal const val CELL_FLAG_BLINK = 1 shl 13

internal data class GhosttySearchRange(
    val startCol: Int,
    val endCol: Int,
    val active: Boolean,
)

internal data class GhosttyRenderCell(
    val fgArgb: Int,
    val bgArgb: Int,
    val underlineArgb: Int = fgArgb,
    val flags: Int,
    val text: String,
    val selected: Boolean = false,
) {
    val bold: Boolean get() = flags and CELL_FLAG_BOLD != 0
    val italic: Boolean get() = flags and CELL_FLAG_ITALIC != 0
    val faint: Boolean get() = flags and CELL_FLAG_FAINT != 0
    val inverse: Boolean get() = flags and CELL_FLAG_INVERSE != 0
    val underline: Boolean get() = flags and CELL_FLAG_UNDERLINE != 0
    val underlineStyle: Int
        get() = (flags and CELL_FLAG_UNDERLINE_STYLE_MASK) shr CELL_FLAG_UNDERLINE_STYLE_SHIFT
    val strikethrough: Boolean get() = flags and CELL_FLAG_STRIKETHROUGH != 0
    val overline: Boolean get() = flags and CELL_FLAG_OVERLINE != 0
    val invisible: Boolean get() = flags and CELL_FLAG_INVISIBLE != 0
    val wide: Boolean get() = flags and CELL_FLAG_WIDE != 0
    val wideTail: Boolean get() = flags and CELL_FLAG_WIDE_TAIL != 0
    val blink: Boolean get() = flags and CELL_FLAG_BLINK != 0
}

internal data class GhosttyRenderRow(
    val rowIndex: Int,
    val cells: List<GhosttyRenderCell>,
    val wrap: Boolean = false,
    val wrapContinuation: Boolean = false,
    val searchRanges: List<GhosttySearchRange> = emptyList(),
)

internal data class GhosttyRenderSnapshot(
    val version: Int,
    val dirtyKind: Int,
    val cols: Int,
    val rows: Int,
    val backgroundArgb: Int,
    val foregroundArgb: Int,
    val cursorArgb: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorStyle: Int,
    val cursorVisible: Boolean,
    val cursorBlinking: Boolean,
    val generation: Int,
    val rowsData: List<GhosttyRenderRow>,
) {
    val isDirty: Boolean get() = dirtyKind != SNAPSHOT_DIRTY_NONE
    val isFullDirty: Boolean get() = dirtyKind == SNAPSHOT_DIRTY_FULL
}

/**
 * Decodes the binary render snapshot written by
 * [GhosttyNativeBridge.nativeRenderSnapshot].
 */
internal object RenderSnapshotDecoder {
    private const val HEADER_INTS = 14

    fun decode(buffer: ByteBuffer, byteCount: Int): GhosttyRenderSnapshot? {
        if (byteCount < 0 || byteCount > buffer.capacity()) return null
        if (byteCount < HEADER_INTS * Int.SIZE_BYTES) return null
        val originalOrder = buffer.order()
        val originalLimit = buffer.limit()
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.limit(byteCount)
            buffer.rewind()

            val version = buffer.int
            val dirtyKind = buffer.int
            val cols = buffer.int
            val rows = buffer.int
            val backgroundArgb = buffer.int
            val foregroundArgb = buffer.int
            val cursorX = buffer.int
            val cursorY = buffer.int
            val cursorStyle = buffer.int
            val cursorVisible = buffer.int != 0
            val cursorBlinking = buffer.int != 0
            val rowCount = buffer.int
            val generation = buffer.int
            val cursorArgb = buffer.int

            if (version != SNAPSHOT_VERSION || dirtyKind !in 0..2) return null
            if (cols !in 1..MAX_GRID_DIMENSION || rows !in 1..MAX_GRID_DIMENSION ||
                rowCount !in 0..rows
            ) return null

            val rowsData = ArrayList<GhosttyRenderRow>(rowCount)
            repeat(rowCount) {
                if (buffer.remaining() < Int.SIZE_BYTES * 7) return null
                val rowIndex = buffer.int
                val selectionStartX = buffer.int
                val selectionEndX = buffer.int
                val wrap = buffer.int != 0
                val wrapContinuation = buffer.int != 0
                val searchRangeCount = buffer.int
                val cellCount = buffer.int
                if (cols <= 0 || rows <= 0 || rowIndex !in 0 until rows) return null
                if (cellCount < 0 || cellCount > cols) return null
                if (searchRangeCount < 0 || searchRangeCount > cols) return null

                val searchRanges = ArrayList<GhosttySearchRange>(searchRangeCount)
                repeat(searchRangeCount) {
                    if (buffer.remaining() < Int.SIZE_BYTES * 3) return null
                    val start = buffer.int
                    val end = buffer.int
                    val active = buffer.int != 0
                    if (start !in 0 until cols || end !in start until cols) return null
                    searchRanges += GhosttySearchRange(start, end, active)
                }

                val cells = ArrayList<GhosttyRenderCell>(cellCount)
                repeat(cellCount) {
                    if (buffer.remaining() < Int.SIZE_BYTES * 3 + Short.SIZE_BYTES * 2) return null
                    val fgArgb = buffer.int
                    val bgArgb = buffer.int
                    val underlineArgb = buffer.int
                    val flags = buffer.short.toInt() and 0xFFFF
                    val textLength = buffer.short.toInt() and 0xFFFF
                    if (textLength < 0 || buffer.remaining() < textLength) return null
                    val textBytes = ByteArray(textLength)
                    buffer.get(textBytes)
                    val text = String(textBytes, StandardCharsets.UTF_8)
                    val column = cells.size
                    cells += GhosttyRenderCell(
                        fgArgb = fgArgb,
                        bgArgb = bgArgb,
                        underlineArgb = underlineArgb,
                        flags = flags,
                        text = text,
                        selected = selectionStartX >= 0 &&
                            selectionEndX >= selectionStartX &&
                            column in selectionStartX..selectionEndX,
                    )
                }
                rowsData += GhosttyRenderRow(
                    rowIndex = rowIndex,
                    cells = cells,
                    wrap = wrap,
                    wrapContinuation = wrapContinuation,
                    searchRanges = searchRanges,
                )
            }

            return GhosttyRenderSnapshot(
                version = version,
                dirtyKind = dirtyKind,
                cols = cols,
                rows = rows,
                backgroundArgb = backgroundArgb,
                foregroundArgb = foregroundArgb,
                cursorArgb = cursorArgb,
                cursorX = cursorX,
                cursorY = cursorY,
                cursorStyle = cursorStyle,
                cursorVisible = cursorVisible,
                cursorBlinking = cursorBlinking,
                generation = generation,
                rowsData = rowsData,
            )
        } finally {
            buffer.order(originalOrder)
            buffer.limit(originalLimit)
        }
    }

    private const val MAX_GRID_DIMENSION = 65_535
}
