package com.yang136.sshhelper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yang136.sshhelper.terminal.GhosttyRenderCell
import com.yang136.sshhelper.terminal.GhosttyRenderSnapshot
import com.yang136.sshhelper.ui.theme.TerminalPalette
import kotlin.math.ceil
import kotlin.math.max

/**
 * First-layer Canvas renderer for the Ghostty backend.
 *
 * This intentionally implements only background, ordinary text, colors,
 * basic styles and cursor. Input, selection, search and scrollback remain
 * future commits.
 */
internal class GhosttyTerminalView(context: Context) : View(context) {
    private var engine: GhosttyNativeEngine? = null
    private var onGridResize: ((cols: Int, rows: Int) -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = DEFAULT_FONT_SIZE_SP * resources.displayMetrics.density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var cellWidthPx = 0f
    private var cellHeightPx = 0f
    private var baselinePx = 0f
    private var cols = 0
    private var rows = 0
    private var backgroundArgb = Color.BLACK
    private var foregroundArgb = Color.WHITE

    // Grid cache for partial redraws: native snapshots may only contain dirty
    // rows, but drawing every frame must still render the untouched rows.
    private var grid: Array<Array<GhosttyRenderCell?>>? = null

    private var cursorBlinkOn = true
    private var lastCursorBlinkToggle = 0L

    init {
        isFocusable = false
        updateMetrics()
    }

    fun attach(nativeEngine: GhosttyNativeEngine) {
        engine = nativeEngine
        if (width > 0 && height > 0) {
            resizeGrid()
        }
        invalidate()
    }

    fun setPalette(palette: TerminalPalette) {
        backgroundArgb = Color.parseColor(palette.background)
        foregroundArgb = Color.parseColor(palette.foreground)
        invalidate()
    }

    fun setFontSizeSp(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(4f, 64f)
        textPaint.textSize = clamped * resources.displayMetrics.density
        updateMetrics()
        if (width > 0 && height > 0) resizeGrid()
        invalidate()
    }

    fun setOnGridResize(callback: (cols: Int, rows: Int) -> Unit) {
        onGridResize = callback
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeGrid()
    }

    private fun updateMetrics() {
        val fontMetrics = textPaint.fontMetrics
        val measuredHeight = fontMetrics.descent - fontMetrics.ascent
        cellWidthPx = max(1f, textPaint.measureText("M"))
        cellHeightPx = max(1f, ceil(measuredHeight.toDouble()).toFloat())
        baselinePx = -fontMetrics.ascent
    }

    private fun resizeGrid() {
        val currentEngine = engine ?: return
        if (width <= 0 || height <= 0) return
        val newCols = max(2, (width / cellWidthPx).toInt())
        val newRows = max(2, (height / cellHeightPx).toInt())
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        grid = null
        currentEngine.requestResize(
            cols,
            rows,
            ceil(cellWidthPx.toDouble()).toInt().coerceAtLeast(1),
            ceil(cellHeightPx.toDouble()).toInt().coerceAtLeast(1),
        )
        onGridResize?.invoke(cols, rows)
    }

    private fun ensureGrid(snapshot: GhosttyRenderSnapshot) {
        val current = grid
        if (current != null &&
            current.size == snapshot.rows &&
            (snapshot.rows == 0 || current[0].size == snapshot.cols)
        ) {
            return
        }
        grid = Array(snapshot.rows) {
            arrayOfNulls<GhosttyRenderCell>(snapshot.cols)
        }
    }

    private fun updateGrid(snapshot: GhosttyRenderSnapshot) {
        ensureGrid(snapshot)
        val target = grid ?: return
        for (row in snapshot.rowsData) {
            if (row.rowIndex !in target.indices) continue
            target[row.rowIndex] = Array(snapshot.cols) { column ->
                row.cells.getOrNull(column)
            }
        }
    }

    private fun drawCellDecorations(
        canvas: Canvas,
        cell: GhosttyRenderCell,
        left: Float,
        top: Float,
        width: Float,
    ) {
        val lineColor = textPaint.color
        textPaint.color = lineColor
        textPaint.strokeWidth = max(1f, resources.displayMetrics.density * 0.75f)
        textPaint.style = Paint.Style.STROKE

        if (cell.overline) {
            canvas.drawLine(left, top + OVERLINE_OFFSET, left + width, top + OVERLINE_OFFSET, textPaint)
        }

        val underlineY = top + baselinePx + UNDERLINE_Y_OFFSET
        when (cell.underlineStyle) {
            1 -> canvas.drawLine(left, underlineY, left + width, underlineY, textPaint)
            2 -> {
                canvas.drawLine(left, underlineY, left + width, underlineY, textPaint)
                canvas.drawLine(left, underlineY + 2f, left + width, underlineY + 2f, textPaint)
            }
            3 -> {
                val path = Path()
                val step = max(2f, width / 8f)
                path.moveTo(left, underlineY)
                var x = left
                var up = true
                while (x < left + width) {
                    val next = minOf(left + width, x + step)
                    val midY = if (up) underlineY - 1.5f else underlineY + 1.5f
                    path.quadTo((x + next) / 2f, midY, next, underlineY)
                    x = next
                    up = !up
                }
                canvas.drawPath(path, textPaint)
            }
            4 -> {
                textPaint.pathEffect = DashPathEffect(floatArrayOf(1f, 3f), 0f)
                canvas.drawLine(left, underlineY, left + width, underlineY, textPaint)
            }
            5 -> {
                textPaint.pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
                canvas.drawLine(left, underlineY, left + width, underlineY, textPaint)
            }
        }

        textPaint.pathEffect = null
        textPaint.style = Paint.Style.FILL
        textPaint.strokeWidth = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val snapshot = engine?.latestSnapshot() ?: run {
            canvas.drawColor(backgroundArgb)
            return
        }

        backgroundArgb = snapshot.backgroundArgb
        foregroundArgb = snapshot.foregroundArgb
        updateGrid(snapshot)

        canvas.drawColor(backgroundArgb)

        val target = grid ?: return
        for (rowIndex in target.indices) {
            val rowCells = target[rowIndex] ?: continue
            val y = rowIndex * cellHeightPx
            var x = 0f
            for (cell in rowCells) {
                if (cell == null || cell.wideTail) {
                    // The leading wide cell already advanced x by two columns;
                    // the tail is a spacer and must not advance again.
                    if (cell != null && cell.wideTail) continue
                    x += cellWidthPx
                    continue
                }
                val effectiveBg = if (cell.inverse) cell.fgArgb else cell.bgArgb
                val effectiveFg = if (cell.inverse) cell.bgArgb else cell.fgArgb
                val cellWidth = cellWidthPx * if (cell.wide) 2f else 1f
                if (cell.inverse || effectiveBg != snapshot.backgroundArgb) {
                    fillPaint.color = effectiveBg
                    canvas.drawRect(x, y, x + cellWidth, y + cellHeightPx, fillPaint)
                }
                if (cell.text.isNotEmpty() && !cell.invisible && !cell.wideTail) {
                    textPaint.color = effectiveFg
                    textPaint.alpha = if (cell.faint) FAINT_ALPHA else 255
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.textSkewX = if (cell.italic) ITALIC_SKEW_X else 0f
                    textPaint.isStrikeThruText = cell.strikethrough
                    textPaint.isUnderlineText = false
                    canvas.drawText(cell.text, x, y + baselinePx, textPaint)
                    drawCellDecorations(canvas, cell, x, y, cellWidth)
                }
                textPaint.color = foregroundArgb
                textPaint.alpha = 255
                textPaint.isFakeBoldText = false
                textPaint.textSkewX = 0f
                textPaint.isStrikeThruText = false
                textPaint.pathEffect = null
                x += cellWidth
            }
        }

        if (snapshot.cursorBlinking) {
            val now = SystemClock.uptimeMillis()
            if (now - lastCursorBlinkToggle >= CURSOR_BLINK_INTERVAL_MS) {
                cursorBlinkOn = !cursorBlinkOn
                lastCursorBlinkToggle = now
                postInvalidateOnAnimation()
            }
        } else {
            cursorBlinkOn = true
        }

        if (cursorBlinkOn &&
            snapshot.cursorVisible &&
            snapshot.cursorX >= 0 &&
            snapshot.cursorY >= 0
        ) {
            val cursorLeft = snapshot.cursorX * cellWidthPx
            val cursorTop = snapshot.cursorY * cellHeightPx
            cursorPaint.color = Color.WHITE
            when (snapshot.cursorStyle) {
                0 -> canvas.drawRect(
                    cursorLeft,
                    cursorTop,
                    cursorLeft + max(2f, cellWidthPx * 0.15f),
                    cursorTop + cellHeightPx,
                    cursorPaint,
                )
                2 -> canvas.drawRect(
                    cursorLeft,
                    cursorTop + cellHeightPx - max(2f, cellHeightPx * 0.12f),
                    cursorLeft + cellWidthPx,
                    cursorTop + cellHeightPx,
                    cursorPaint,
                )
                else -> canvas.drawRect(
                    cursorLeft,
                    cursorTop,
                    cursorLeft + cellWidthPx,
                    cursorTop + cellHeightPx,
                    cursorPaint,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_FONT_SIZE_SP = 14f
        const val INITIAL_BUFFER_BYTES = 1 shl 20
        const val FAINT_ALPHA = 150
        const val ITALIC_SKEW_X = -0.2f
        const val OVERLINE_OFFSET = 1f
        const val UNDERLINE_Y_OFFSET = 3f
        const val CURSOR_BLINK_INTERVAL_MS = 500L
    }
}

@Composable
internal fun GhosttyTerminalSurface(
    frontend: GhosttyTerminalFrontend,
    onPtyWrite: (ByteArray) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnPtyWrite = rememberUpdatedState(onPtyWrite)
    val currentOnResize = rememberUpdatedState(onResize)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            GhosttyTerminalView(context).apply {
                frontend.onPtyWrite = { bytes -> currentOnPtyWrite.value(bytes) }
                setOnGridResize { cols, rows -> currentOnResize.value(cols, rows) }
                frontend.attachView(this)
            }
        },
        update = { view ->
            frontend.onPtyWrite = { bytes -> currentOnPtyWrite.value(bytes) }
            view.setOnGridResize { cols, rows -> currentOnResize.value(cols, rows) }
            frontend.attachView(view)
        },
        onRelease = { view ->
            frontend.detachView(view)
        },
    )
}
