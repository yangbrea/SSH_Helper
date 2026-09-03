package com.yang136.sshhelper.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
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
    // libghostty-vt 0.1.0 search is ASCII case-insensitive and exposes no
    // case-sensitive option. Advertise that instead of ignoring the Aa toggle.
    override val supportsCaseSensitiveSearch: Boolean = false

    /** Receives bytes the terminal asks to write back to the PTY. */
    var onPtyWrite: ((ByteArray) -> Unit)? = null

    /** Receives copied terminal text; the surface installs a clipboard writer. */
    var copySink: ((String) -> Unit)? = null

    /** Terminal effects from OSC/BEL. */
    var onBell: (() -> Unit)? = null
    var onTitleChange: ((String) -> Unit)? = null
    var onPwdChange: ((String) -> Unit)? = null

    private var lastSearchQuery: String? = null
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
        onPtyWrite?.invoke(output)
    }

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

    override suspend fun reset() {
        ensureStarted()
        engine.reset()
        view?.invalidate()
    }

    override fun setAppearance(palette: TerminalPalette, fontSize: Int) {
        ensureStarted()
        engine.requestSetDefaultColors(
            backgroundArgb = Color.parseColor(palette.background),
            foregroundArgb = Color.parseColor(palette.foreground),
            cursorArgb = Color.parseColor(palette.cursor),
        )
        view?.setPalette(palette)
        view?.setFontSizeSp(fontSize.toFloat())
    }

    // WindowInsets reports global IME state; it does not grant this view focus
    // ownership. Explicit terminal actions own show/hide requests.
    override fun setImeVisible(visible: Boolean) = Unit

    override fun paste(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return
        val hasUnsafeControl = text.any { ch ->
            ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t'
        }
        if (!hasUnsafeControl) {
            pasteText(text)
            return
        }
        AlertDialog.Builder(context)
            .setTitle("粘贴不安全内容")
            .setMessage("剪贴板包含控制字符，粘贴后可能被当成按键序列执行。是否仍然粘贴？")
            .setPositiveButton("仍然粘贴") { _, _ -> pasteText(text) }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun pasteText(text: String) {
        if (text.isEmpty()) return
        ensureStarted()
        engine.requestPasteText(text)
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
        onSelectionStateChanged?.invoke(true, false)
        engine.requestSelectionPress(col, row)
    }

    internal fun selectionDrag(col: Int, row: Int) {
        ensureStarted()
        engine.requestSelectionDrag(col, row)
        onSelectionStateChanged?.invoke(true, true)
    }

    internal fun selectionRelease(col: Int, row: Int) {
        ensureStarted()
        engine.requestSelectionRelease(col, row)
    }

    internal fun cellTap(col: Int, row: Int) {
        ensureStarted()
        engine.requestLinkUriAt(col, row) { uri ->
            frontendScope.launch {
                if (uri.isNullOrEmpty()) {
                    view?.focusAndShowKeyboard()
                } else {
                    onOpenLink?.invoke(uri)
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
        if (lastSearchQuery != query) {
            lastSearchQuery = query
            engine.requestSearchSet(query, backwards) { index, total ->
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

    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val RENDER_DELAY_THRESHOLD_MS = 500L
    }
}
