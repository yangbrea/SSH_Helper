package com.yang136.sshhelper.terminal

/**
 * Merges Ghostty's full and dirty-row snapshots into a stable screen frame.
 *
 * Native snapshots are deltas after the first/full frame. Keeping this state
 * outside the View's size lifecycle prevents an IME or rotation resize from
 * clearing rows before the matching full resize snapshot arrives.
 */
internal class GhosttyRenderFrameStore {
    private var snapshot: GhosttyRenderSnapshot? = null
    private var rows: Array<Array<GhosttyRenderCell?>>? = null
    private var expectedCols: Int? = null
    private var expectedRows: Int? = null

    data class Change(
        val fullRedraw: Boolean,
        val firstDirtyRow: Int,
        val lastDirtyRow: Int,
    )

    data class Frame(
        val snapshot: GhosttyRenderSnapshot,
        val rows: Array<Array<GhosttyRenderCell?>>,
    )

    fun apply(update: GhosttyRenderSnapshot): Change? {
        if (update.cols <= 0 || update.rows <= 0) return null
        if (expectedCols?.let { it != update.cols } == true ||
            expectedRows?.let { it != update.rows } == true
        ) {
            return null
        }

        val previousSnapshot = snapshot
        val previousRows = rows
        if (previousSnapshot != null && update.generation < previousSnapshot.generation) {
            return null
        }
        if (previousSnapshot != null &&
            update.generation > previousSnapshot.generation &&
            !update.isFullDirty
        ) {
            // A reset generation must begin with a complete frame. Never merge
            // a late/partial frame into a screen owned by another session.
            return null
        }
        val dimensionsChanged = previousSnapshot != null &&
            (previousSnapshot.cols != update.cols || previousSnapshot.rows != update.rows)

        // A partial frame with different dimensions cannot be merged safely:
        // resize may have reflowed every row. Retain the old complete frame
        // until the native side publishes the full resize frame.
        if (dimensionsChanged && !update.isFullDirty) return null

        if (!update.isDirty) {
            // A clean snapshot carries no cell changes. Preserve both cells and
            // their last valid cursor metadata instead of replacing the frame
            // with an empty delta.
            return null
        }

        val target = if (previousRows == null || dimensionsChanged || update.isFullDirty) {
            Array(update.rows) { arrayOfNulls<GhosttyRenderCell>(update.cols) }
        } else {
            previousRows
        }

        var firstDirtyRow = update.rows
        var lastDirtyRow = -1
        for (row in update.rowsData) {
            if (row.rowIndex !in target.indices) continue
            target[row.rowIndex] = Array(update.cols) { column -> row.cells.getOrNull(column) }
            firstDirtyRow = minOf(firstDirtyRow, row.rowIndex)
            lastDirtyRow = maxOf(lastDirtyRow, row.rowIndex)
        }

        previousSnapshot?.cursorY?.takeIf { it in target.indices }?.let {
            firstDirtyRow = minOf(firstDirtyRow, it)
            lastDirtyRow = maxOf(lastDirtyRow, it)
        }
        update.cursorY.takeIf { it in target.indices }?.let {
            firstDirtyRow = minOf(firstDirtyRow, it)
            lastDirtyRow = maxOf(lastDirtyRow, it)
        }

        rows = target
        snapshot = update

        val fullRedraw = previousRows == null || dimensionsChanged || update.isFullDirty
        return Change(
            fullRedraw = fullRedraw,
            firstDirtyRow = if (fullRedraw || lastDirtyRow < 0) 0 else firstDirtyRow,
            lastDirtyRow = if (fullRedraw || lastDirtyRow < 0) update.rows - 1 else lastDirtyRow,
        )
    }

    fun currentFrame(): Frame? {
        val currentSnapshot = snapshot ?: return null
        val currentRows = rows ?: return null
        return Frame(currentSnapshot, currentRows)
    }

    fun expectSize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        expectedCols = cols
        expectedRows = rows
    }

    fun clear() {
        snapshot = null
        rows = null
        expectedCols = null
        expectedRows = null
    }
}
