package com.yang136.sshhelper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yang136.sshhelper.terminal.GhosttyNativeBridge
import com.yang136.sshhelper.terminal.RenderSnapshotDecoder
import com.yang136.sshhelper.terminal.GhosttyRenderSnapshot
import com.yang136.sshhelper.ui.theme.TerminalPalette
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private var handle: Long = 0L
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

    private var snapshotBuffer = ByteBuffer
        .allocateDirect(INITIAL_BUFFER_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)

    init {
        isFocusable = false
        updateMetrics()
    }

    fun attach(nativeHandle: Long) {
        handle = nativeHandle
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
        if (handle != 0L) resizeGrid()
    }

    private fun updateMetrics() {
        val fontMetrics = textPaint.fontMetrics
        val measuredHeight = fontMetrics.descent - fontMetrics.ascent
        cellWidthPx = max(1f, textPaint.measureText("M"))
        cellHeightPx = max(1f, ceil(measuredHeight.toDouble()).toFloat())
        baselinePx = -fontMetrics.ascent
    }

    private fun resizeGrid() {
        if (width <= 0 || height <= 0 || handle == 0L) return
        val newCols = max(2, (width / cellWidthPx).toInt())
        val newRows = max(2, (height / cellHeightPx).toInt())
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        GhosttyNativeBridge.nativeResize(
            handle,
            cols,
            rows,
            ceil(cellWidthPx.toDouble()).toInt().coerceAtLeast(1),
            ceil(cellHeightPx.toDouble()).toInt().coerceAtLeast(1),
        )
        onGridResize?.invoke(cols, rows)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (handle == 0L) return

        canvas.drawColor(backgroundArgb)

        snapshotBuffer.clear()
        val rowCount = GhosttyNativeBridge.nativeRenderSnapshot(handle, snapshotBuffer)
        if (rowCount < 0) {
            // Caller-provided buffer too small; first layer uses a generous size.
            return
        }
        snapshotBuffer.clear()
        val snapshot = RenderSnapshotDecoder.decode(snapshotBuffer, snapshotBuffer.capacity())
            ?: return

        backgroundArgb = snapshot.backgroundArgb
        foregroundArgb = snapshot.foregroundArgb

        for (row in snapshot.rowsData) {
            val y = row.rowIndex * cellHeightPx
            var x = 0f
            for (cell in row.cells) {
                val cellLeft = x
                val cellRight = x + cellWidthPx * if (cell.wide) 2f else 1f
                if (cell.bgArgb != snapshot.backgroundArgb) {
                    fillPaint.color = cell.bgArgb
                    canvas.drawRect(cellLeft, y, cellRight, y + cellHeightPx, fillPaint)
                }
                if (cell.text.isNotEmpty() && !cell.invisible && !cell.wideTail) {
                    textPaint.color = if (cell.inverse) cell.bgArgb else cell.fgArgb
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.isStrikeThruText = cell.strikethrough
                    textPaint.isUnderlineText = cell.underline
                    canvas.drawText(cell.text, cellLeft, y + baselinePx, textPaint)
                }
                textPaint.isFakeBoldText = false
                textPaint.isStrikeThruText = false
                textPaint.isUnderlineText = false
                x = cellRight
            }
        }

        if (snapshot.cursorVisible && snapshot.cursorX >= 0 && snapshot.cursorY >= 0) {
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
    }
}

@Composable
internal fun GhosttyTerminalSurface(
    frontend: GhosttyTerminalFrontend,
    onResize: (cols: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnResize = rememberUpdatedState(onResize)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            GhosttyTerminalView(context).apply {
                setOnGridResize { cols, rows -> currentOnResize.value(cols, rows) }
                frontend.attachView(this)
            }
        },
        update = { view ->
            view.setOnGridResize { cols, rows -> currentOnResize.value(cols, rows) }
            frontend.attachView(view)
        },
        onRelease = { view ->
            frontend.detachView(view)
        },
    )
}
