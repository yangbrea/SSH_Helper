package com.yang136.sshhelper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
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
    private var onTerminalFocusChange: ((Boolean) -> Unit)? = null
    private var scrollAccum = 0f
    private val flingScroller = OverScroller(context)
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var flingLastY = 0
    private var flingPixelRemainder = 0f
    private var pointerDown = false
    /** 触摸尚未被识别为滚动/选择时，不立即发送鼠标按下，等抬起判定为点按再补发。 */
    private var touchPendingClick = false
    private var pressedMouseButton = MOUSE_BUTTON_LEFT
    private val touchState = GhosttyTouchState()
    private var twoFingerLastY = 0f
    private var lastSelectionX = 0f
    private var lastSelectionY = 0f
    private val drawClip = Rect()

    private val scrollDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                flingScroller.forceFinished(true)
                removeCallbacks(flingRunnable)
                flingPixelRemainder = 0f
                scrollAccum = 0f
                pointerDown = true
                if (touchState.selectionArmed) {
                    cellAt(e.x, e.y)?.let { (col, row) ->
                        touchState.beginSelection()
                        onSelectionPress?.invoke(col, row)
                    } ?: touchState.clearSelection()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (touchState.selectionActive) {
                    // 扩选由 onTouchEvent 的 ACTION_MOVE 统一处理；长按后
                    // GestureDetector 不保证继续回调 onScroll。
                    return true
                }
                // 一旦判定为滚动/拖动，就不再把它当作点按补发鼠标事件。
                touchPendingClick = false
                if (cellHeightPx <= 0f) return false
                scrollAccum += distanceY
                val delta = (scrollAccum / cellHeightPx).toInt()
                if (delta != 0) {
                    scrollAccum -= delta * cellHeightPx
                    val current = engine
                    if (current?.mouseReportingActive == true && e2.pointerCount == 1) {
                        // One-finger drag is the primary mobile scroll gesture. When a
                        // remote app (e.g. tmux with mouse on) owns the screen, deliver it
                        // as wheel clicks instead of a native viewport scroll (which is a
                        // no-op inside its alternate screen) or a mouse drag.
                        releaseTouchMouse(e2)
                        sendWheelClicks(
                            current,
                            // Finger up == scroll down == wheel down (button 5);
                            // finger down == scroll up == wheel up (button 4).
                            if (delta > 0) MOUSE_BUTTON_FIVE else MOUSE_BUTTON_FOUR,
                            abs(delta),
                            e2.x,
                            e2.y,
                        )
                    } else {
                        onScrollLines?.invoke(delta)
                    }
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (touchState.selectionActive || cellHeightPx <= 0f) return false
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
                if (!pointerDown || touchState.selectionActive) return
                val cell = cellAt(e.x, e.y) ?: return
                releaseTouchMouse(e)
                touchState.beginSelection()
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onSelectionPress?.invoke(cell.first, cell.second)
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (touchState.selectionActive || engine?.mouseReportingActive == true) return false
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
                val current = engine
                if (current?.mouseReportingActive == true) {
                    sendWheelClicks(
                        current,
                        if (deltaRows > 0) MOUSE_BUTTON_FIVE else MOUSE_BUTTON_FOUR,
                        abs(deltaRows),
                        width / 2f,
                        height / 2f,
                    )
                } else {
                    onScrollLines?.invoke(deltaRows)
                }
            }
            if (!flingScroller.isFinished) postOnAnimation(this)
        }
    }

    private val selectionAutoScrollRunnable = object : Runnable {
        override fun run() {
            if (!touchState.selectionActive || cellHeightPx <= 0f) return
            val edge = cellHeightPx
            val delta = when {
                lastSelectionY < edge -> -edgeScrollSpeed(lastSelectionY, edge)
                lastSelectionY > height - edge -> edgeScrollSpeed(height - lastSelectionY, edge)
                else -> 0
            }
            if (delta != 0) {
                onScrollLines?.invoke(delta)
                cellAt(lastSelectionX, lastSelectionY)?.let { onSelectionDrag?.invoke(it.first, it.second) }
                postDelayed(this, SELECTION_AUTO_SCROLL_INTERVAL_MS)
            }
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
    private var selectionArgb = 0x99155E75.toInt()
    private var cursorAccentArgb = Color.BLACK
    private val fallbackTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val emojiTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val glyphTypefaceCache = LinkedHashMap<String, Typeface>(256, 0.75f, true)
    private val imeState = GhosttyImeState()

    // Native snapshots are dirty-row deltas. The store retains a complete
    // frame across View resizes until the matching full native frame arrives.
    private var frameStore: GhosttyRenderFrameStore? = null

    private var cursorBlinkOn = true
    private var cursorBlinking = false
    private var textBlinking = false
    private var hasFocus = false
    private var initialFocusRequested = false
    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            if (!cursorBlinking && !textBlinking) return
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
        renderFrames.currentFrame()?.let {
            updateCursorBlink(shouldBlink(it.snapshot))
            updateTextBlink(it.rows.any { row -> row.any { cell -> cell?.blink == true } })
        }
        if (width > 0 && height > 0) {
            resizeGrid()
        }
        invalidate()
    }

    fun setPalette(palette: TerminalPalette) {
        backgroundArgb = terminalColorToArgb(palette.background)
        foregroundArgb = terminalColorToArgb(palette.foreground)
        selectionArgb = terminalColorToArgb(palette.selectionBackground)
        cursorAccentArgb = terminalColorToArgb(palette.cursorAccent)
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

    fun setOnTerminalFocusChange(callback: (Boolean) -> Unit) {
        onTerminalFocusChange = callback
    }

    fun clearComposingText() {
        if (!imeState.isComposing) return
        imeState.cancel()
        invalidate()
    }

    fun performBellFeedback() {
        if (!isAttachedToWindow || !isShown || !hasWindowFocus()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBellAtMs < BELL_RATE_LIMIT_MS) return
        lastBellAtMs = now
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private var lastBellAtMs = 0L

    fun armSelectionMode() {
        touchPendingClick = false
        if (touchState.armSelection()) {
            engine?.requestMouseEvent(
                MOUSE_ACTION_RELEASE,
                MOUSE_BUTTON_LEFT,
                0,
                0f,
                0f,
                false,
            )
        }
        requestFocus()
    }

    fun clearSelectionAndResetGesture() {
        touchPendingClick = false
        touchState.clearSelection()
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
        val currentEngine = engine
        if (currentEngine?.mouseReportingActive == true &&
            !touchState.selectionArmed && !touchState.selectionActive
        ) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    touchPendingClick = false
                    releaseTouchMouse(event)
                    touchState.beginTwoFingerScroll()
                    twoFingerLastY = averageY(event)
                }
                MotionEvent.ACTION_MOVE -> if (touchState.twoFingerScrolling && event.pointerCount >= 2) {
                    val y = averageY(event)
                    scrollAccum += twoFingerLastY - y
                    twoFingerLastY = y
                    val delta = (scrollAccum / cellHeightPx).toInt()
                    if (delta != 0) {
                        scrollAccum -= delta * cellHeightPx
                        // Mouse-reporting applications (e.g. tmux with mouse on) own the
                        // screen, so two-finger drag must be delivered as wheel clicks.
                        // A plain viewport scroll is a no-op inside their alternate screen.
                        val current = engine
                        if (current?.mouseReportingActive == true) {
                            sendWheelClicks(
                                current,
                                // Finger up == scroll down == wheel down (button 5);
                                // finger down == scroll up == wheel up (button 4).
                                if (delta > 0) MOUSE_BUTTON_FIVE else MOUSE_BUTTON_FOUR,
                                abs(delta),
                                event.x,
                                event.y,
                            )
                        } else {
                            onScrollLines?.invoke(delta)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (touchState.finishTwoFingerScroll()) {
                        touchPendingClick = false
                        pointerDown = false
                        return true
                    }
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerDown = true
            // Touch events are ambiguous until the gesture is classified. Do not
            // send a real mouse press on ACTION_DOWN: a drag would otherwise leak
            // SGR press/motion sequences into the remote before scroll starts.
            // A tap sends press+release on ACTION_UP instead.
            touchPendingClick = currentEngine?.mouseReportingActive == true &&
                !touchState.selectionArmed &&
                !touchState.selectionActive &&
                !event.isFromSource(InputDevice.SOURCE_MOUSE)
        }

        // 长按进入选择后，扩选由这里直接处理；不依赖 GestureDetector 的 onScroll。
        if (event.actionMasked == MotionEvent.ACTION_MOVE && touchState.selectionActive) {
            lastSelectionX = event.x
            lastSelectionY = event.y
            cellAt(event.x, event.y)?.let { (col, row) ->
                onSelectionDrag?.invoke(col, row)
            }
            scheduleSelectionAutoScroll()
            return true
        }

        val handled = scrollDetector.onTouchEvent(event) || super.onTouchEvent(event)
        val mouseHandled = !touchState.twoFingerScrolling && dispatchMouseEvent(event, allowTouch = true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
            }
            MotionEvent.ACTION_UP -> {
                pointerDown = false
                removeCallbacks(selectionAutoScrollRunnable)
                if (touchState.selectionActive) {
                    cellAt(event.x, event.y)?.let { (col, row) ->
                        onSelectionRelease?.invoke(col, row)
                    } ?: onSelectionRelease?.invoke(-1, -1)
                    touchState.finishSelection()
                } else {
                    if (touchPendingClick) {
                        touchPendingClick = false
                        // Gesture was a tap, not a drag/scroll: now deliver the click
                        // that TUIs using mouse reporting expect.
                        currentEngine?.requestMouseEvent(
                            action = MOUSE_ACTION_PRESS,
                            button = MOUSE_BUTTON_LEFT,
                            mods = mouseModifiers(event),
                            x = event.x,
                            y = event.y,
                            anyButtonPressed = true,
                        )
                        currentEngine?.requestMouseEvent(
                            action = MOUSE_ACTION_RELEASE,
                            button = MOUSE_BUTTON_LEFT,
                            mods = mouseModifiers(event),
                            x = event.x,
                            y = event.y,
                            anyButtonPressed = false,
                        )
                    }
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                touchPendingClick = false
                pointerDown = false
                removeCallbacks(selectionAutoScrollRunnable)
                if (touchState.selectionActive) {
                    onSelectionRelease?.invoke(-1, -1)
                    touchState.finishSelection()
                }
            }
        }
        return handled || mouseHandled
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
        // A terminal has no editable document for autocorrect/prediction. Without
        // these flags, some IMEs keep English hardware-key input indefinitely as
        // composing text, while Chinese appears to work only when a candidate is
        // confirmed through commitText.
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        return object : BaseInputConnection(this, true) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                imeState.setComposing(text)
                postInvalidateOnAnimation()
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                imeState.commit(text)?.let(::sendInput)
                postInvalidateOnAnimation()
                return true
            }

            override fun finishComposingText(): Boolean {
                clearComposingText()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // 组合输入过程中由 IME 管理的删除不应直接发给远端；
                // 只有真正编辑已上屏内容时才发送退格。
                val count = imeState.deleteCount(beforeLength, MAX_IME_DELETE)
                if (count > 0) {
                    sendInput("\u007f".repeat(count))
                }
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
                return handleKeyEvent(event) || super.sendKeyEvent(event)
            }
        }
    }

    // Hardware keyboards enter through View.dispatchKeyEvent. Handling at this
    // boundary also covers devices whose events do not reach onKeyDown/onKeyUp.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleKeyEvent(event) || super.dispatchKeyEvent(event)

    private fun sendInput(text: String) {
        if (text.isNotEmpty()) onInputBytes?.invoke(text.encodeToByteArray())
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        // Some Bluetooth keyboards and IMEs deliver already-composed text as a
        // single ACTION_MULTIPLE/KEYCODE_UNKNOWN event instead of key down/up.
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val characters = event.characters
            if (!characters.isNullOrEmpty()) {
                sendInput(normalizeTerminalInput(characters))
                return true
            }
            return false
        }
        val unicode = event.unicodeChar
        val isDeadKey = unicode and KeyCharacterMap.COMBINING_ACCENT != 0
        if (event.action == KeyEvent.ACTION_DOWN &&
            unicode >= FIRST_PRINTABLE_CODEPOINT &&
            unicode != DELETE_CODEPOINT &&
            !isDeadKey &&
            !event.isCtrlPressed &&
            !event.isAltPressed &&
            !event.isMetaPressed &&
            Character.isValidCodePoint(unicode)
        ) {
            // Plain text does not need terminal key-protocol encoding. Sending it
            // through the same raw-input path as commitText also avoids devices
            // whose printable KeyEvents are not encoded by the native key encoder.
            sendInput(String(Character.toChars(unicode)))
            return true
        }
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
        if (!gainFocus) clearComposingText()
        onTerminalFocusChange?.invoke(gainFocus)
        syncCursorBlink()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A terminal should be ready for a physical keyboard as soon as it is
        // shown. requestFocus alone does not summon the software keyboard.
        if (!initialFocusRequested) {
            initialFocusRequested = true
            post {
                if (isAttachedToWindow && isShown && !hasFocus()) requestFocus()
            }
        }
        hasFocus = hasFocus()
        onTerminalFocusChange?.invoke(hasFocus)
        frameStore?.currentFrame()?.let {
            updateCursorBlink(shouldBlink(it.snapshot))
            updateTextBlink(it.rows.any { row -> row.any { cell -> cell?.blink == true } })
        }
    }

    override fun onDetachedFromWindow() {
        cursorBlinking = false
        textBlinking = false
        touchPendingClick = false
        if (touchState.releaseMouse()) {
            engine?.requestMouseEvent(
                MOUSE_ACTION_RELEASE,
                MOUSE_BUTTON_LEFT,
                0,
                0f,
                0f,
                false,
            )
        }
        flingScroller.forceFinished(true)
        removeCallbacks(cursorBlinkRunnable)
        removeCallbacks(flingRunnable)
        removeCallbacks(selectionAutoScrollRunnable)
        clearComposingText()
        onTerminalFocusChange?.invoke(false)
        super.onDetachedFromWindow()
    }

    private fun dispatchMouseEvent(event: MotionEvent, allowTouch: Boolean = false): Boolean {
        val currentEngine = engine ?: return false
        val physicalMouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
        if (!physicalMouse && !allowTouch) return false
        if (!physicalMouse && (event.pointerCount != 1 || touchState.twoFingerScrolling)) return false
        if (touchState.selectionArmed || touchState.selectionActive) return false
        if (!currentEngine.mouseReportingActive) return false
        // A touch gesture is not a mouse click until it is classified as a tap.
        // Do not emit press/motion from ambiguous touch drags.
        if (!physicalMouse && touchPendingClick) return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> MOUSE_ACTION_PRESS
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_CANCEL ->
                MOUSE_ACTION_RELEASE
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> MOUSE_ACTION_MOTION
            else -> return false
        }
        if (!physicalMouse && action != MOUSE_ACTION_PRESS && !touchState.mousePressed) return false
        val anyButtonPressed = if (physicalMouse) event.buttonState != 0 else touchState.mousePressed
        val button = if (action == MOUSE_ACTION_MOTION) {
            // Motion events must carry the currently pressed button when a drag is
            // active. Sending 0/null makes remote TUIs see hover (button code 35)
            // instead of a drag and can leak malformed mouse reporting sequences.
            if (anyButtonPressed) pressedMouseButton else 0
        } else {
            mouseButton(event).also { pressedMouseButton = it }
        }
        if (!physicalMouse) {
            when (action) {
                MOUSE_ACTION_PRESS -> touchState.beginMouse()
                MOUSE_ACTION_RELEASE -> touchState.releaseMouse()
            }
        }
        currentEngine.requestMouseEvent(
            action = action,
            button = button,
            mods = mouseModifiers(event),
            x = event.x,
            y = event.y,
            anyButtonPressed = anyButtonPressed,
        )
        return true
    }

    private fun releaseTouchMouse(event: MotionEvent) {
        // This is called once a touch is known to be a scroll/selection/gesture.
        // Even if no synthetic press was sent yet, cancel the pending tap click.
        touchPendingClick = false
        if (!touchState.releaseMouse()) return
        engine?.requestMouseEvent(
            action = MOUSE_ACTION_RELEASE,
            button = MOUSE_BUTTON_LEFT,
            mods = mouseModifiers(event),
            x = event.x,
            y = event.y,
            anyButtonPressed = false,
        )
    }

    private fun averageY(event: MotionEvent): Float {
        if (event.pointerCount == 0) return event.y
        var total = 0f
        for (index in 0 until event.pointerCount) total += event.getY(index)
        return total / event.pointerCount
    }

    private fun edgeScrollSpeed(distance: Float, edge: Float): Int = when {
        distance <= edge / 3f -> 3
        distance <= edge * 2f / 3f -> 2
        else -> 1
    }

    private fun scheduleSelectionAutoScroll() {
        removeCallbacks(selectionAutoScrollRunnable)
        if (lastSelectionY < cellHeightPx || lastSelectionY > height - cellHeightPx) {
            postDelayed(selectionAutoScrollRunnable, SELECTION_AUTO_SCROLL_INTERVAL_MS)
        }
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

    private fun sendWheelClicks(
        currentEngine: GhosttyNativeEngine,
        button: Int,
        count: Int,
        x: Float,
        y: Float,
    ) {
        repeat(count.coerceIn(1, 10)) {
            currentEngine.requestMouseEvent(
                action = MOUSE_ACTION_PRESS,
                button = button,
                mods = 0,
                x = x,
                y = y,
                anyButtonPressed = false,
            )
        }
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
        updateTextBlink(
            frameStore?.currentFrame()?.rows?.any { row -> row.any { cell -> cell?.blink == true } } == true,
        )
        if (change.fullRedraw || height <= 0 || width <= 0) {
            invalidate()
            return
        }
        val top = (change.firstDirtyRow * cellHeightPx).toInt().coerceAtLeast(0)
        val bottom = ceil((change.lastDirtyRow + 1) * cellHeightPx).toInt().coerceAtMost(height)
        postInvalidateOnAnimation(0, top, width, bottom)
    }

    private fun shouldBlink(snapshot: GhosttyRenderSnapshot): Boolean =
        // 对齐传统桌面终端：只要光标可见且 View 持有焦点就闪烁，
        // 不依赖远端是否发送 DECSET 12。
        hasFocus && snapshot.cursorVisible

    private fun syncCursorBlink() {
        val snapshot = frameStore?.currentFrame()?.snapshot ?: return
        updateCursorBlink(shouldBlink(snapshot))
    }

    private fun updateCursorBlink(enabled: Boolean) {
        if (enabled == cursorBlinking) return
        cursorBlinking = enabled
        syncBlinkTimer()
    }

    private fun updateTextBlink(enabled: Boolean) {
        if (enabled == textBlinking) return
        textBlinking = enabled
        syncBlinkTimer()
    }

    private fun syncBlinkTimer() {
        removeCallbacks(cursorBlinkRunnable)
        cursorBlinkOn = true
        postInvalidateOnAnimation()
        if ((cursorBlinking || textBlinking) && isAttachedToWindow) {
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
        textPaint.color = cell.underlineArgb
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
        textPaint.color = lineColor
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
        canvas.getClipBounds(drawClip)
        val firstVisibleRow = (drawClip.top / cellHeightPx).toInt().coerceIn(0, target.lastIndex)
        val lastVisibleRow = (drawClip.bottom / cellHeightPx).toInt().coerceIn(firstVisibleRow, target.lastIndex)
        for (rowIndex in firstVisibleRow..lastVisibleRow) {
            val rowCells = target[rowIndex]
            val searchRanges = frame.rowMetadata.getOrNull(rowIndex)?.searchRanges.orEmpty()
            val y = rowIndex * cellHeightPx
            var x = 0f
            for ((column, cell) in rowCells.withIndex()) {
                if (cell == null || cell.wideTail) {
                    // The leading wide cell already advanced x by two columns;
                    // the tail is a spacer and must not advance again.
                    if (cell != null && cell.wideTail) continue
                    x += cellWidthPx
                    continue
                }
                val effectiveBg = when {
                    cell.selected -> selectionArgb
                    searchRanges.any { it.active && column in it.startCol..it.endCol } -> SEARCH_ACTIVE_BG_ARGB
                    searchRanges.any { column in it.startCol..it.endCol } -> SEARCH_BG_ARGB
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
                if (cell.text.isNotEmpty() && !cell.invisible && !cell.wideTail &&
                    (!cell.blink || cursorBlinkOn)
                ) {
                    textPaint.color = effectiveFg
                    textPaint.alpha = if (cell.faint) FAINT_ALPHA else 255
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.textSkewX = if (cell.italic) ITALIC_SKEW_X else 0f
                    textPaint.isStrikeThruText = cell.strikethrough
                    textPaint.isUnderlineText = false
                    textPaint.typeface = typefaceFor(cell.text)
                    val saveCount = canvas.save()
                    canvas.clipRect(x, y, x + cellWidth, y + cellHeightPx)
                    canvas.drawText(cell.text, x, y + baselinePx, textPaint)
                    drawCellDecorations(canvas, cell, x, y, cellWidth)
                    canvas.restoreToCount(saveCount)
                }
                textPaint.color = foregroundArgb
                textPaint.alpha = 255
                textPaint.isFakeBoldText = false
                textPaint.textSkewX = 0f
                textPaint.isStrikeThruText = false
                textPaint.pathEffect = null
                textPaint.typeface = Typeface.MONOSPACE
                x += cellWidth
            }
        }

        if (imeState.isComposing && snapshot.cursorX >= 0 && snapshot.cursorY >= 0) {
            val composingText = imeState.composingText
            val left = snapshot.cursorX * cellWidthPx
            val top = snapshot.cursorY * cellHeightPx
            textPaint.color = foregroundArgb
            textPaint.typeface = typefaceFor(composingText)
            canvas.drawText(composingText, left, top + baselinePx, textPaint)
            canvas.drawLine(
                left,
                top + baselinePx + UNDERLINE_Y_OFFSET,
                minOf(width.toFloat(), left + textPaint.measureText(composingText)),
                top + baselinePx + UNDERLINE_Y_OFFSET,
                textPaint,
            )
            textPaint.typeface = Typeface.MONOSPACE
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
                        textPaint.color = cursorAccentArgb
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

    private fun typefaceFor(text: String): Typeface {
        glyphTypefaceCache[text]?.let { return it }
        val selected = when {
            textPaint.hasGlyph(text) -> Typeface.MONOSPACE
            text.codePoints().anyMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() } -> emojiTypeface
            else -> fallbackTypeface
        }
        if (glyphTypefaceCache.size >= GLYPH_CACHE_SIZE) {
            val oldest = glyphTypefaceCache.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        glyphTypefaceCache[text] = selected
        return selected
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
        const val SEARCH_BG_ARGB = 0x997A5B00.toInt()
        const val SEARCH_ACTIVE_BG_ARGB = 0xFFE0A800.toInt()
        const val GLYPH_CACHE_SIZE = 512
        const val MAX_IME_DELETE = 64
        const val SELECTION_AUTO_SCROLL_INTERVAL_MS = 50L
        const val BELL_RATE_LIMIT_MS = 100L
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
        const val FIRST_PRINTABLE_CODEPOINT = 0x20
        const val DELETE_CODEPOINT = 0x7f
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
