package com.yang136.sshhelper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yang136.sshhelper.terminal.GhosttyRenderCell
import com.yang136.sshhelper.terminal.GhosttyRenderFrameStore
import com.yang136.sshhelper.terminal.GhosttyRenderSnapshot
import com.yang136.sshhelper.ui.theme.TerminalPalette
import kotlin.math.abs
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
    private var onScrollLines: ((Int) -> Unit)? = null
    private var onInputBytes: ((ByteArray) -> Unit)? = null
    private var scrollAccum = 0f
    private var flingVelocityY = 0f

    private val scrollDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                flingVelocityY = 0f
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (cellHeightPx <= 0f) return false
                scrollAccum += distanceY
                val delta = (scrollAccum / cellHeightPx).toInt()
                if (delta != 0) {
                    scrollAccum -= delta * cellHeightPx
                    onScrollLines?.invoke(delta)
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (cellHeightPx <= 0f) return false
                flingVelocityY = velocityY
                postOnAnimation(flingRunnable)
                return true
            }
        },
    )

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (cellHeightPx <= 0f || abs(flingVelocityY) < FLING_STOP_VELOCITY_PX) {
                flingVelocityY = 0f
                return
            }
            val delta = (flingVelocityY / cellHeightPx).toInt()
            if (delta != 0) onScrollLines?.invoke(delta)
            flingVelocityY *= FLING_DECELERATION
            postOnAnimation(this)
        }
    }

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
    private var reportedCellWidthPx = 0
    private var reportedCellHeightPx = 0
    private var backgroundArgb = Color.BLACK
    private var foregroundArgb = Color.WHITE

    // Native snapshots are dirty-row deltas. The store retains a complete
    // frame across View resizes until the matching full native frame arrives.
    private var frameStore: GhosttyRenderFrameStore? = null

    private var cursorBlinkOn = true
    private var cursorBlinking = false
    private var hasFocus = false
    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            if (!cursorBlinking) return
            cursorBlinkOn = !cursorBlinkOn
            postInvalidateOnAnimation()
            postDelayed(this, CURSOR_BLINK_INTERVAL_MS)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateMetrics()
    }

    fun attach(nativeEngine: GhosttyNativeEngine, renderFrames: GhosttyRenderFrameStore) {
        engine = nativeEngine
        frameStore = renderFrames
        renderFrames.currentFrame()?.snapshot?.let { updateCursorBlink(shouldBlink(it)) }
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

    fun setOnScrollLines(callback: (Int) -> Unit) {
        onScrollLines = callback
    }

    fun setOnInputBytes(callback: (ByteArray) -> Unit) {
        onInputBytes = callback
    }

    fun focusAndShowKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        clearFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scrollAccum = 0f
            requestFocus()
        }
        return scrollDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) sendInput(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) sendInput("\u007f")
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKeyEvent(event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = true

    private fun sendInput(text: String) {
        if (text.isNotEmpty()) onInputBytes?.invoke(text.encodeToByteArray())
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> { sendInput("\r"); return true }
            KeyEvent.KEYCODE_DEL -> { sendInput("\u007f"); return true }
            KeyEvent.KEYCODE_TAB -> { sendInput("\t"); return true }
            KeyEvent.KEYCODE_ESCAPE -> { sendInput("\u001b"); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { sendInput("\u001b[A"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { sendInput("\u001b[B"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { sendInput("\u001b[C"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { sendInput("\u001b[D"); return true }
        }
        val unicode = event.unicodeChar
        if (unicode != 0) {
            if (event.isCtrlPressed) {
                val code = unicode and 0x1f
                sendInput(code.toChar().toString())
            } else if (!event.isAltPressed && !event.isMetaPressed) {
                sendInput(String(Character.toChars(unicode)))
            }
            return true
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeGrid()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        hasFocus = gainFocus
        syncCursorBlink()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hasFocus = hasFocus()
        frameStore?.currentFrame()?.snapshot?.let { updateCursorBlink(shouldBlink(it)) }
    }

    override fun onDetachedFromWindow() {
        cursorBlinking = false
        removeCallbacks(cursorBlinkRunnable)
        removeCallbacks(flingRunnable)
        super.onDetachedFromWindow()
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
        val newCellWidthPx = ceil(cellWidthPx.toDouble()).toInt().coerceAtLeast(1)
        val newCellHeightPx = ceil(cellHeightPx.toDouble()).toInt().coerceAtLeast(1)
        if (newCols == cols &&
            newRows == rows &&
            newCellWidthPx == reportedCellWidthPx &&
            newCellHeightPx == reportedCellHeightPx
        ) {
            return
        }
        cols = newCols
        rows = newRows
        reportedCellWidthPx = newCellWidthPx
        reportedCellHeightPx = newCellHeightPx
        frameStore?.expectSize(cols, rows)
        currentEngine.requestResize(
            cols,
            rows,
            reportedCellWidthPx,
            reportedCellHeightPx,
        )
        onGridResize?.invoke(cols, rows)
    }

    fun renderFrameChanged(
        snapshot: GhosttyRenderSnapshot,
        change: GhosttyRenderFrameStore.Change,
    ) {
        updateCursorBlink(shouldBlink(snapshot))
        if (change.fullRedraw || height <= 0 || width <= 0) {
            invalidate()
            return
        }
        val top = (change.firstDirtyRow * cellHeightPx).toInt().coerceAtLeast(0)
        val bottom = ceil((change.lastDirtyRow + 1) * cellHeightPx).toInt().coerceAtMost(height)
        postInvalidateOnAnimation(0, top, width, bottom)
    }

    private fun shouldBlink(snapshot: GhosttyRenderSnapshot): Boolean =
        // 对齐旧 xterm.js 的 cursorBlink=true：只要光标可见且 View 持有焦点就闪烁，
        // 不依赖远端是否发送 DECSET 12。
        hasFocus && snapshot.cursorVisible

    private fun syncCursorBlink() {
        val snapshot = frameStore?.currentFrame()?.snapshot ?: return
        updateCursorBlink(shouldBlink(snapshot))
    }

    private fun updateCursorBlink(enabled: Boolean) {
        if (enabled == cursorBlinking) return
        cursorBlinking = enabled
        removeCallbacks(cursorBlinkRunnable)
        cursorBlinkOn = true
        postInvalidateOnAnimation()
        if (enabled && isAttachedToWindow) {
            postDelayed(cursorBlinkRunnable, CURSOR_BLINK_INTERVAL_MS)
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

        val frame = frameStore?.currentFrame() ?: run {
            canvas.drawColor(backgroundArgb)
            return
        }
        val snapshot = frame.snapshot

        backgroundArgb = snapshot.backgroundArgb
        foregroundArgb = snapshot.foregroundArgb

        canvas.drawColor(backgroundArgb)

        val target = frame.rows
        for (rowIndex in target.indices) {
            val rowCells = target[rowIndex]
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

        if (cursorBlinkOn &&
            snapshot.cursorVisible &&
            snapshot.cursorX >= 0 &&
            snapshot.cursorY >= 0
        ) {
            val cursorLeft = snapshot.cursorX * cellWidthPx
            val cursorTop = snapshot.cursorY * cellHeightPx
            cursorPaint.color = snapshot.cursorArgb
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
        const val FLING_STOP_VELOCITY_PX = 40f
        const val FLING_DECELERATION = 0.92f
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
                frontend.copySink = { text ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SSH terminal", text))
                }
                setOnGridResize { cols, rows -> currentOnResize.value(cols, rows) }
                setOnScrollLines { delta -> frontend.scrollLines(delta) }
                setOnInputBytes { bytes -> frontend.sendUserInput(bytes) }
                frontend.attachView(this)
            }
        },
        update = { view ->
            frontend.onPtyWrite = { bytes -> currentOnPtyWrite.value(bytes) }
            view.setOnGridResize { cols, rows -> currentOnResize.value(cols, rows) }
            view.setOnScrollLines { delta -> frontend.scrollLines(delta) }
            view.setOnInputBytes { bytes -> frontend.sendUserInput(bytes) }
            frontend.attachView(view)
        },
        onRelease = { view ->
            frontend.detachView(view)
        },
    )
}
