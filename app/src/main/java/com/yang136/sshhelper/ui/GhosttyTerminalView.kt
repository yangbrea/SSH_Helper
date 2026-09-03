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
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
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
import kotlin.math.roundToInt

internal fun applyOpacityToArgb(argb: Int, opacity: Float): Int {
    val safeOpacity = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 1f
    val alpha = (safeOpacity * 255f).roundToInt()
    return (argb and 0x00FFFFFF) or (alpha shl 24)
}

/** Canvas renderer and native input surface for the Ghostty backend. */
internal class GhosttyTerminalView(context: Context) : View(context) {
    private var engine: GhosttyNativeEngine? = null
    private var onGridResize: ((cols: Int, rows: Int) -> Unit)? = null
    private var onScrollLines: ((Int) -> Unit)? = null
    private var onInputBytes: ((ByteArray) -> Unit)? = null
    private var onSelectionPress: ((Int, Int) -> Unit)? = null
    private var onSelectionDrag: ((Int, Int) -> Unit)? = null
    private var onSelectionRelease: ((Int, Int) -> Unit)? = null
    private var onSelectionClear: (() -> Unit)? = null
    private var onCellTap: ((Int, Int) -> Unit)? = null
    private var scrollAccum = 0f
    private val flingScroller = OverScroller(context)
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var flingLastY = 0
    private var flingPixelRemainder = 0f
    private var pointerDown = false
    private var selectionActive = false
    private var selectionModeArmed = false
    private var pressedMouseButton = MOUSE_BUTTON_LEFT

    private val scrollDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                flingScroller.forceFinished(true)
                removeCallbacks(flingRunnable)
                flingPixelRemainder = 0f
                scrollAccum = 0f
                pointerDown = true
                if (selectionModeArmed) {
                    selectionModeArmed = false
                    cellAt(e.x, e.y)?.let { (col, row) ->
                        selectionActive = true
                        onSelectionPress?.invoke(col, row)
                    }
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (selectionActive) {
                    // 扩选由 onTouchEvent 的 ACTION_MOVE 统一处理；长按后
                    // GestureDetector 不保证继续回调 onScroll。
                    return true
                }
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
                if (selectionActive || cellHeightPx <= 0f) return false
                if (abs(velocityY) < minimumFlingVelocity) return false
                flingLastY = 0
                flingPixelRemainder = 0f
                flingScroller.fling(
                    0,
                    0,
                    0,
                    velocityY.toInt().coerceIn(-maximumFlingVelocity, maximumFlingVelocity),
                    0,
                    0,
                    -FLING_POSITION_LIMIT,
                    FLING_POSITION_LIMIT,
                )
                removeCallbacks(flingRunnable)
                postOnAnimation(flingRunnable)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!pointerDown || selectionActive) return
                val cell = cellAt(e.x, e.y) ?: return
                selectionActive = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onSelectionPress?.invoke(cell.first, cell.second)
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (selectionActive) return false
                cellAt(e.x, e.y)?.let { (col, row) -> onCellTap?.invoke(col, row) }
                return true
            }
        },
    )

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (cellHeightPx <= 0f || !flingScroller.computeScrollOffset()) return
            val movementY = flingScroller.currY - flingLastY
            flingLastY = flingScroller.currY
            // Finger/down velocity is positive, while Ghostty viewport-up is
            // negative. Integrate actual per-frame pixel movement rather than
            // treating the pixels/second velocity as a per-frame distance.
            flingPixelRemainder -= movementY
            val deltaRows = (flingPixelRemainder / cellHeightPx).toInt()
            if (deltaRows != 0) {
                flingPixelRemainder -= deltaRows * cellHeightPx
                onScrollLines?.invoke(deltaRows)
            }
            if (!flingScroller.isFinished) postOnAnimation(this)
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
    private var backgroundOpacity = 1f

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

    fun setBackgroundOpacity(opacity: Float) {
        val normalized = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 1f
        if (backgroundOpacity == normalized) return
        backgroundOpacity = normalized
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

    fun setOnSelectionCallbacks(
        onPress: (Int, Int) -> Unit,
        onDrag: (Int, Int) -> Unit,
        onRelease: (Int, Int) -> Unit,
        onClear: () -> Unit,
    ) {
        onSelectionPress = onPress
        onSelectionDrag = onDrag
        onSelectionRelease = onRelease
        onSelectionClear = onClear
    }

    fun setOnCellTap(callback: (Int, Int) -> Unit) {
        onCellTap = callback
    }

    fun armSelectionMode() {
        selectionModeArmed = true
        selectionActive = false
        requestFocus()
    }

    fun clearSelectionAndResetGesture() {
        selectionModeArmed = false
        selectionActive = false
    }

    fun focusAndShowKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        clearFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dispatchMouseEvent(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pointerDown = true

        // 长按进入选择后，扩选由这里直接处理；不依赖 GestureDetector 的 onScroll。
        if (event.actionMasked == MotionEvent.ACTION_MOVE && selectionActive) {
            cellAt(event.x, event.y)?.let { (col, row) ->
                onSelectionDrag?.invoke(col, row)
            }
            return true
        }

        val handled = scrollDetector.onTouchEvent(event) || super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
            }
            MotionEvent.ACTION_UP -> {
                pointerDown = false
                if (selectionActive) {
                    cellAt(event.x, event.y)?.let { (col, row) ->
                        onSelectionRelease?.invoke(col, row)
                    } ?: onSelectionRelease?.invoke(-1, -1)
                    selectionActive = false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerDown = false
                if (selectionActive) {
                    onSelectionRelease?.invoke(-1, -1)
                    selectionActive = false
                }
            }
        }
        return handled
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.actionMasked == MotionEvent.ACTION_SCROLL
        ) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (vertical == 0f && horizontal == 0f) return true
            val currentEngine = engine
            if (currentEngine?.mouseReportingActive == true) {
                sendReportedWheel(currentEngine, event, vertical, horizontal)
            } else if (vertical != 0f) {
                onScrollLines?.invoke(mouseWheelViewportDelta(vertical))
            }
            return true
        }
        if (dispatchMouseEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
        return object : BaseInputConnection(this, true) {
            private var composing = false

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = text != null
                return super.setComposingText(text, newCursorPosition)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = false
                if (!text.isNullOrEmpty()) sendInput(normalizeTerminalInput(text.toString()))
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = false
                return super.finishComposingText()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // 组合输入过程中由 IME 管理的删除不应直接发给远端；
                // 只有真正编辑已上屏内容时才发送退格。
                if (!composing && beforeLength > 0) sendInput("\u007f")
                return true
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean = deleteSurroundingText(beforeLength, afterLength)

            override fun performEditorAction(actionCode: Int): Boolean = when (actionCode) {
                EditorInfo.IME_ACTION_NONE,
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_SEND,
                -> {
                    sendInput("\r")
                    true
                }
                else -> super.performEditorAction(actionCode)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                handleKeyEvent(event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyEvent(event) || super.onKeyUp(keyCode, event)

    private fun sendInput(text: String) {
        if (text.isNotEmpty()) onInputBytes?.invoke(text.encodeToByteArray())
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val currentEngine = engine ?: return fallbackHandleKeyEvent(event)
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) KEY_ACTION_REPEAT else KEY_ACTION_PRESS
            KeyEvent.ACTION_UP -> KEY_ACTION_RELEASE
            else -> return false
        }
        var mods = 0
        if (event.isShiftPressed) mods = mods or KEY_MOD_SHIFT
        if (event.isCtrlPressed) mods = mods or KEY_MOD_CTRL
        if (event.isAltPressed) mods = mods or KEY_MOD_ALT
        if (event.isMetaPressed) mods = mods or KEY_MOD_SUPER
        if (event.isCapsLockOn) mods = mods or KEY_MOD_CAPS_LOCK

        val withoutShift = event.metaState and
            KeyEvent.META_SHIFT_ON.inv() and
            KeyEvent.META_SHIFT_LEFT_ON.inv() and
            KeyEvent.META_SHIFT_RIGHT_ON.inv()
        val unshiftedCodepoint = event.getUnicodeChar(withoutShift)
        val unicode = event.unicodeChar
        val utf8 = if (unicode != 0 && !event.isCtrlPressed && !event.isAltPressed) {
            String(Character.toChars(unicode)).encodeToByteArray()
        } else {
            null
        }
        currentEngine.requestKeyEvent(
            action = action,
            keyCode = event.keyCode,
            mods = mods,
            unshiftedCodepoint = unshiftedCodepoint,
            utf8 = utf8,
        )
        return true
    }

    private fun fallbackHandleKeyEvent(event: KeyEvent): Boolean {
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
        flingScroller.forceFinished(true)
        removeCallbacks(cursorBlinkRunnable)
        removeCallbacks(flingRunnable)
        super.onDetachedFromWindow()
    }

    private fun dispatchMouseEvent(event: MotionEvent): Boolean {
        val currentEngine = engine ?: return false
        // A finger drag is terminal viewport navigation even when vim/tmux has
        // enabled mouse tracking. Only an actual pointer device is forwarded.
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        if (selectionModeArmed || selectionActive) return false
        if (!currentEngine.mouseReportingActive) return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> MOUSE_ACTION_PRESS
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_CANCEL ->
                MOUSE_ACTION_RELEASE
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> MOUSE_ACTION_MOTION
            else -> return false
        }
        val button = if (action == MOUSE_ACTION_MOTION) {
            0
        } else {
            mouseButton(event).also { pressedMouseButton = it }
        }
        currentEngine.requestMouseEvent(
            action = action,
            button = button,
            mods = mouseModifiers(event),
            x = event.x,
            y = event.y,
            anyButtonPressed = event.buttonState != 0,
        )
        return true
    }

    private fun mouseButton(event: MotionEvent): Int {
        val state = if (event.actionButton != 0) event.actionButton else event.buttonState
        return when {
            state and MotionEvent.BUTTON_SECONDARY != 0 -> MOUSE_BUTTON_RIGHT
            state and MotionEvent.BUTTON_TERTIARY != 0 -> MOUSE_BUTTON_MIDDLE
            state and MotionEvent.BUTTON_BACK != 0 -> MOUSE_BUTTON_FOUR
            state and MotionEvent.BUTTON_FORWARD != 0 -> MOUSE_BUTTON_FIVE
            state and MotionEvent.BUTTON_PRIMARY != 0 -> MOUSE_BUTTON_LEFT
            else -> pressedMouseButton
        }
    }

    private fun mouseModifiers(event: MotionEvent): Int {
        var mods = 0
        if (event.metaState and KeyEvent.META_SHIFT_ON != 0) mods = mods or KEY_MOD_SHIFT
        if (event.metaState and KeyEvent.META_CTRL_ON != 0) mods = mods or KEY_MOD_CTRL
        if (event.metaState and KeyEvent.META_ALT_ON != 0) mods = mods or KEY_MOD_ALT
        if (event.metaState and KeyEvent.META_META_ON != 0) mods = mods or KEY_MOD_SUPER
        return mods
    }

    private fun sendReportedWheel(
        currentEngine: GhosttyNativeEngine,
        event: MotionEvent,
        vertical: Float,
        horizontal: Float,
    ) {
        val (button, magnitude) = when {
            vertical > 0f -> MOUSE_BUTTON_FOUR to vertical
            vertical < 0f -> MOUSE_BUTTON_FIVE to -vertical
            horizontal > 0f -> MOUSE_BUTTON_SIX to horizontal
            else -> MOUSE_BUTTON_SEVEN to -horizontal
        }
        repeat(wheelEventCount(magnitude)) {
            currentEngine.requestMouseEvent(
                action = MOUSE_ACTION_PRESS,
                button = button,
                mods = mouseModifiers(event),
                x = event.x,
                y = event.y,
                anyButtonPressed = false,
            )
        }
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (cellWidthPx <= 0f || cellHeightPx <= 0f || cols <= 0 || rows <= 0) return null
        val col = (x / cellWidthPx).toInt().coerceIn(0, cols - 1)
        val row = (y / cellHeightPx).toInt().coerceIn(0, rows - 1)
        return col to row
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
            canvas.drawColor(applyOpacityToArgb(backgroundArgb, backgroundOpacity))
            return
        }
        val snapshot = frame.snapshot

        backgroundArgb = snapshot.backgroundArgb
        foregroundArgb = snapshot.foregroundArgb

        canvas.drawColor(applyOpacityToArgb(backgroundArgb, backgroundOpacity))

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
                val effectiveBg = when {
                    cell.selected -> SELECTION_BG_ARGB
                    cell.inverse -> cell.fgArgb
                    else -> cell.bgArgb
                }
                val effectiveFg = when {
                    cell.selected -> foregroundArgb
                    cell.inverse -> cell.bgArgb
                    else -> cell.fgArgb
                }
                val cellWidth = cellWidthPx * if (cell.wide) 2f else 1f
                if (cell.selected || cell.inverse || effectiveBg != snapshot.backgroundArgb) {
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
                3 -> {
                    cursorPaint.style = Paint.Style.STROKE
                    cursorPaint.strokeWidth = max(2f, resources.displayMetrics.density)
                    val inset = cursorPaint.strokeWidth / 2f
                    canvas.drawRect(
                        cursorLeft + inset,
                        cursorTop + inset,
                        cursorLeft + cellWidthPx - inset,
                        cursorTop + cellHeightPx - inset,
                        cursorPaint,
                    )
                    cursorPaint.style = Paint.Style.FILL
                }
                else -> {
                    canvas.drawRect(
                        cursorLeft,
                        cursorTop,
                        cursorLeft + cellWidthPx,
                        cursorTop + cellHeightPx,
                        cursorPaint,
                    )
                    // Redraw the glyph using the terminal background so an
                    // opaque block cursor does not erase the character.
                    val cell = frame.rows.getOrNull(snapshot.cursorY)
                        ?.getOrNull(snapshot.cursorX)
                    if (cell != null && cell.text.isNotEmpty() && !cell.invisible && !cell.wideTail) {
                        textPaint.color = snapshot.backgroundArgb
                        textPaint.alpha = 255
                        textPaint.isFakeBoldText = cell.bold
                        textPaint.textSkewX = if (cell.italic) ITALIC_SKEW_X else 0f
                        canvas.drawText(cell.text, cursorLeft, cursorTop + baselinePx, textPaint)
                        textPaint.color = foregroundArgb
                        textPaint.isFakeBoldText = false
                        textPaint.textSkewX = 0f
                    }
                }
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
        const val FLING_POSITION_LIMIT = 1_000_000
        const val SELECTION_BG_ARGB = 0xFF155E75.toInt()
        const val MOUSE_ACTION_PRESS = 0
        const val MOUSE_ACTION_RELEASE = 1
        const val MOUSE_ACTION_MOTION = 2
        const val MOUSE_BUTTON_LEFT = 1
        const val MOUSE_BUTTON_RIGHT = 2
        const val MOUSE_BUTTON_MIDDLE = 3
        const val MOUSE_BUTTON_FOUR = 4
        const val MOUSE_BUTTON_FIVE = 5
        const val MOUSE_BUTTON_SIX = 6
        const val MOUSE_BUTTON_SEVEN = 7
        const val KEY_ACTION_RELEASE = 0
        const val KEY_ACTION_PRESS = 1
        const val KEY_ACTION_REPEAT = 2
        const val KEY_MOD_SHIFT = 1 shl 0
        const val KEY_MOD_CTRL = 1 shl 1
        const val KEY_MOD_ALT = 1 shl 2
        const val KEY_MOD_SUPER = 1 shl 3
        const val KEY_MOD_CAPS_LOCK = 1 shl 4
    }
}

@Composable
internal fun GhosttyTerminalSurface(
    frontend: GhosttyTerminalFrontend,
    backgroundOpacity: Float,
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
                setBackgroundOpacity(backgroundOpacity)
                frontend.onPtyWrite = { bytes -> currentOnPtyWrite.value(bytes) }
                frontend.copySink = { text ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SSH terminal", text))
                }
                setOnGridResize { cols, rows -> currentOnResize.value(cols, rows) }
                setOnScrollLines { delta -> frontend.scrollLines(delta) }
                setOnInputBytes { bytes -> frontend.sendUserInput(bytes) }
                setOnSelectionCallbacks(
                    onPress = frontend::selectionPress,
                    onDrag = frontend::selectionDrag,
                    onRelease = frontend::selectionRelease,
                    onClear = { frontend.clearSelection() },
                )
                setOnCellTap(frontend::cellTap)
                frontend.attachView(this)
            }
        },
        update = { view ->
            view.setBackgroundOpacity(backgroundOpacity)
            frontend.onPtyWrite = { bytes -> currentOnPtyWrite.value(bytes) }
            view.setOnGridResize { cols, rows -> currentOnResize.value(cols, rows) }
            view.setOnScrollLines { delta -> frontend.scrollLines(delta) }
            view.setOnInputBytes { bytes -> frontend.sendUserInput(bytes) }
            view.setOnSelectionCallbacks(
                onPress = frontend::selectionPress,
                onDrag = frontend::selectionDrag,
                onRelease = frontend::selectionRelease,
                onClear = { frontend.clearSelection() },
            )
            view.setOnCellTap(frontend::cellTap)
            frontend.attachView(view)
        },
        onRelease = { view ->
            frontend.detachView(view)
        },
    )
}
