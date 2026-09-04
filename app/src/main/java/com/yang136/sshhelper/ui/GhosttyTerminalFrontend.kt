package com.yang136.sshhelper.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.yang136.sshhelper.terminal.GhosttyNativeBridge
import com.yang136.sshhelper.terminal.GhosttyRenderFrameStore
import com.yang136.sshhelper.ui.theme.TerminalPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Ghostty-backed terminal frontend.
 *
 * The frontend owns a [GhosttyNativeEngine] which serializes all native calls
 * on a dedicated worker. A Canvas renderer ([GhosttyTerminalView]) reads the
 * main-thread frame store populated by snapshots from that engine.
 */
internal class GhosttyTerminalFrontend : TerminalFrontend {
    override val supportsCaseSensitiveSearch: Boolean = true

    /** Receives bytes the terminal asks to write back to the PTY. */
    var onPtyWrite: ((ByteArray) -> Unit)? = null

    /** Receives copied terminal text; the surface installs a clipboard writer. */
    var copySink: ((String) -> Unit)? = null

    /** Terminal effects from OSC/BEL. */
    override var onBell: (() -> Unit)? = null
    override var onTitleChange: ((String) -> Unit)? = null
    override var onPwdChange: ((String) -> Unit)? = null

    private var lastSearchQuery: String? = null
    private var lastSearchCaseSensitive = false
    private var ctrlArmed = false
    private val frontendJob = SupervisorJob()
    private val frontendScope = CoroutineScope(frontendJob + Dispatchers.Main.immediate)
    private val renderFrames = GhosttyRenderFrameStore()

    private val engine = GhosttyNativeEngine { bytes ->
        frontendScope.launch { onPtyWrite?.invoke(bytes) }
    }.apply {
        onBell = { frontendScope.launch { this@GhosttyTerminalFrontend.onBell?.invoke() } }
        onTitleChange = { title ->
            frontendScope.launch { this@GhosttyTerminalFrontend.onTitleChange?.invoke(title) }
        }
        onPwdChange = { pwd ->
            frontendScope.launch { this@GhosttyTerminalFrontend.onPwdChange?.invoke(pwd) }
        }
        onClipboardWrite = { request ->
            frontendScope.launch { showClipboardWriteConfirmation(request) }
        }
        onSearchUpdated = { index, total ->
            frontendScope.launch { this@GhosttyTerminalFrontend.onSearchResults?.invoke(index, total) }
        }
        onSnapshotReady = { snapshot ->
            frontendScope.launch {
                val change = renderFrames.apply(snapshot) ?: return@launch
                this@GhosttyTerminalFrontend.view?.renderFrameChanged(snapshot, change)
            }
        }
    }
    private var started = false

    @Volatile
    internal var view: GhosttyTerminalView? = null
        private set

    override var onSelectionStateChanged: ((Boolean, Boolean) -> Unit)? = null
    override var onCopied: ((Int) -> Unit)? = null
    override var onSearchResults: ((Int, Int) -> Unit)? = null
    override var onOpenLink: ((String) -> Unit)? = null
    override var onCtrlArmed: ((Boolean) -> Unit)? = null
    override var onRenderingDelayed: ((Boolean) -> Unit)? = null

    private fun ensureStarted() {
        if (!started) {
            started = true
            engine.start(DEFAULT_COLS, DEFAULT_ROWS)
        }
    }

    internal fun attachView(terminalView: GhosttyTerminalView) {
        view = terminalView
        terminalView.attach(engine, renderFrames)
        terminalView.setOnTerminalFocusChange(engine::requestFocus)
        ensureStarted()
    }

    internal fun detachView(terminalView: GhosttyTerminalView) {
        if (view === terminalView) view = null
    }

    internal fun sendUserInput(bytes: ByteArray) {
        val output = if (ctrlArmed && bytes.isNotEmpty()) {
            ctrlArmed = false
            onCtrlArmed?.invoke(false)
            val first = bytes[0].toInt()
            val ctrl = when {
                first in 0x61..0x7A -> first - 0x60
                first in 0x41..0x5A -> first - 0x40
                else -> first
            }
            byteArrayOf(ctrl.toByte()) + bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
        ensureStarted()
        engine.requestRawUserInput(output)
    }

    override fun sendInput(bytes: ByteArray) = sendUserInput(bytes)

    internal fun scrollLines(delta: Int) {
        ensureStarted()
        engine.requestScrollViewport(delta)
    }

    override suspend fun write(bytes: ByteArray) {
        ensureStarted()
        val writeJob = frontendScope.async { engine.write(bytes) }
        try {
            withTimeout(RENDER_DELAY_THRESHOLD_MS) { writeJob.await() }
            onRenderingDelayed?.invoke(false)
        } catch (_: TimeoutCancellationException) {
            onRenderingDelayed?.invoke(true)
            writeJob.await()
            onRenderingDelayed?.invoke(false)
        }
    }

    override suspend fun restore(bytes: ByteArray) {
        ensureStarted()
        engine.write(bytes, emitProtocolReplies = false)
    }

    override suspend fun reset() {
        ensureStarted()
        engine.reset()
        view?.clearComposingText()
        view?.invalidate()
    }

    override fun setAppearance(palette: TerminalPalette, fontSize: Int) {
        ensureStarted()
        val background = terminalColorToArgb(palette.background)
        engine.requestSetAppearance(
            backgroundArgb = background,
            foregroundArgb = terminalColorToArgb(palette.foreground),
            cursorArgb = terminalColorToArgb(palette.cursor),
            paletteArgb = palette.toXterm256Argb(),
            dark = isDarkTerminalColor(background),
        )
        view?.setPalette(palette)
        view?.setFontSizeSp(fontSize.toFloat())
    }

    // WindowInsets reports global IME state; it does not grant this view focus
    // ownership. Explicit terminal actions own show/hide requests.
    override fun setImeVisible(visible: Boolean) {
        if (!visible) view?.clearComposingText()
    }

    override fun paste(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return
        ensureStarted()
        engine.requestPasteText(
            text,
            GhosttyNativeBridge.PASTE_SOURCE_CLIPBOARD,
            allowUnsafe = false,
        ) { result ->
            if (result != GhosttyNativeBridge.PASTE_RESULT_REJECTED) return@requestPasteText
            frontendScope.launch {
                AlertDialog.Builder(context)
                    .setTitle("粘贴多行或控制内容")
                    .setMessage("该内容可能被远端 shell 直接执行。确认继续粘贴吗？")
                    .setPositiveButton("继续粘贴") { _, _ ->
                        engine.requestPasteText(
                            text,
                            GhosttyNativeBridge.PASTE_SOURCE_CLIPBOARD,
                            allowUnsafe = true,
                        )
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    override fun pasteText(text: String) {
        if (text.isEmpty()) return
        ensureStarted()
        engine.requestPasteText(
            text,
            GhosttyNativeBridge.PASTE_SOURCE_TEXT,
            allowUnsafe = true,
        )
    }

    override fun enterSelectionMode() {
        view?.armSelectionMode()
        onSelectionStateChanged?.invoke(true, false)
    }

    override fun clearSelection() {
        view?.clearSelectionAndResetGesture()
        onSelectionStateChanged?.invoke(false, false)
        ensureStarted()
        engine.requestSelectionClear()
    }

    internal fun selectionPress(col: Int, row: Int) {
        ensureStarted()
        // 长按/拖动过程中不能切布局：横屏会插入 Selection 面板导致终端尺寸变化，
        // 进而中断进行中的触摸事件。等 release 后确认有选区再通知 UI。
        engine.requestSelectionPress(col, row)
    }

    internal fun selectionDrag(col: Int, row: Int) {
        ensureStarted()
        engine.requestSelectionDrag(col, row)
    }

    internal fun selectionRelease(col: Int, row: Int) {
        ensureStarted()
        engine.requestSelectionRelease(col, row)
        engine.requestCopySelection { bytes ->
            frontendScope.launch {
                val hasSelection = bytes != null && bytes.isNotEmpty()
                onSelectionStateChanged?.invoke(hasSelection, hasSelection)
            }
        }
    }

    internal fun cellTap(col: Int, row: Int) {
        ensureStarted()
        engine.requestLinkUriAt(col, row) { uri ->
            frontendScope.launch {
                val resolved = uri ?: renderFrames.currentFrame()?.let {
                    TerminalUrlDetector.findAt(it, row, col)
                }
                if (resolved.isNullOrEmpty()) {
                    view?.focusAndShowKeyboard()
                } else {
                    onOpenLink?.invoke(resolved)
                }
            }
        }
    }

    override fun selectAll() {
        ensureStarted()
        engine.requestSelectAll { selected ->
            frontendScope.launch { onSelectionStateChanged?.invoke(selected, selected) }
        }
    }

    override fun copySelection() {
        ensureStarted()
        engine.requestCopySelection { bytes ->
            frontendScope.launch {
                val text = bytes?.decodeToString().orEmpty()
                if (text.isEmpty()) return@launch
                onCopied?.invoke(text.length)
                copySink?.invoke(text)
                clearSelection()
            }
        }
    }

    override fun search(query: String, backwards: Boolean, caseSensitive: Boolean) {
        ensureStarted()
        if (query.isEmpty()) {
            lastSearchQuery = null
            engine.requestSearchClear()
            onSearchResults?.invoke(-1, 0)
            return
        }
        if (lastSearchQuery != query || lastSearchCaseSensitive != caseSensitive) {
            lastSearchQuery = query
            lastSearchCaseSensitive = caseSensitive
            engine.requestSearchSet(query, backwards, caseSensitive) { index, total ->
                frontendScope.launch {
                    onSearchResults?.invoke(index, total)
                }
            }
        } else {
            engine.requestSearchSelect(backwards) { index, total ->
                frontendScope.launch { onSearchResults?.invoke(index, total) }
            }
        }
    }

    override fun clearSearch() {
        lastSearchQuery = null
        lastSearchCaseSensitive = false
        ensureStarted()
        engine.requestSearchClear()
        onSearchResults?.invoke(-1, 0)
    }

    override fun armCtrl() {
        ctrlArmed = !ctrlArmed
        onCtrlArmed?.invoke(ctrlArmed)
        if (ctrlArmed) view?.focusAndShowKeyboard()
    }

    override fun focusAndShowKeyboard() {
        view?.focusAndShowKeyboard()
    }

    override fun hideKeyboard() {
        ctrlArmed = false
        onCtrlArmed?.invoke(false)
        view?.hideKeyboard()
    }

    override fun close() {
        engine.onSnapshotReady = null
        frontendJob.cancel()
        engine.close()
        renderFrames.clear()
        view = null
        started = false
    }

    private fun showClipboardWriteConfirmation(
        request: GhosttyNativeBridge.ClipboardWriteRequest,
    ) {
        val context = view?.context
        if (context == null) {
            engine.resolveClipboardWrite(request.requestId, false)
            return
        }
        var resolved = false
        fun resolve(allowed: Boolean) {
            if (resolved) return
            resolved = true
            if (allowed) {
                runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Remote terminal", request.text),
                    )
                }.onFailure {
                    engine.resolveClipboardWrite(request.requestId, false)
                    return
                }
            }
            engine.resolveClipboardWrite(request.requestId, allowed)
        }
        val source = sanitizeTerminalMetadata(request.programName).ifEmpty { "远端程序" }
        val preview = sanitizeTerminalMetadata(request.text).take(CLIPBOARD_PREVIEW_CHARS)
        AlertDialog.Builder(context)
            .setTitle("允许远端写入剪贴板？")
            .setMessage("来源：$source\n大小：${request.text.encodeToByteArray().size} 字节\n\n$preview")
            .setPositiveButton("允许") { _, _ -> resolve(true) }
            .setNegativeButton("拒绝") { _, _ -> resolve(false) }
            .setOnCancelListener { resolve(false) }
            .show()
    }

    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val RENDER_DELAY_THRESHOLD_MS = 500L
        const val CLIPBOARD_PREVIEW_CHARS = 240
    }
}
