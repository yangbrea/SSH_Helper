package com.yang136.sshhelper.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Cell style flags produced by nativeRenderSnapshot.
 */
internal const val SNAPSHOT_VERSION = 2

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

internal data class GhosttyRenderCell(
    val fgArgb: Int,
    val bgArgb: Int,
    val flags: Int,
    val text: String,
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
}

internal data class GhosttyRenderRow(
    val rowIndex: Int,
    val cells: List<GhosttyRenderCell>,
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

            if (version != SNAPSHOT_VERSION) return null
            if (rowCount < 0) return null

            val rowsData = ArrayList<GhosttyRenderRow>(rowCount)
            repeat(rowCount) {
                if (buffer.remaining() < Int.SIZE_BYTES * 2) return null
                val rowIndex = buffer.int
                val cellCount = buffer.int
                if (cellCount < 0 || cellCount > cols * 4) return null

                val cells = ArrayList<GhosttyRenderCell>(cellCount)
                repeat(cellCount) {
                    if (buffer.remaining() < Int.SIZE_BYTES * 2 + Short.SIZE_BYTES * 2) return null
                    val fgArgb = buffer.int
                    val bgArgb = buffer.int
                    val flags = buffer.short.toInt() and 0xFFFF
                    val textLength = buffer.short.toInt() and 0xFFFF
                    if (textLength < 0 || buffer.remaining() < textLength) return null
                    val textBytes = ByteArray(textLength)
                    buffer.get(textBytes)
                    val text = String(textBytes, StandardCharsets.UTF_8)
                    cells += GhosttyRenderCell(
                        fgArgb = fgArgb,
                        bgArgb = bgArgb,
                        flags = flags,
                        text = text,
                    )
                }
                rowsData += GhosttyRenderRow(rowIndex = rowIndex, cells = cells)
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
}
