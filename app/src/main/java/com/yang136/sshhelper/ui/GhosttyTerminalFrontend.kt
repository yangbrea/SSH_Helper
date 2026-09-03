package com.yang136.sshhelper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import com.yang136.sshhelper.ui.theme.TerminalPalette

/**
 * Ghostty-backed terminal frontend.
 *
 * The frontend owns a [GhosttyNativeEngine] which serializes all native calls
 * on a dedicated worker. A Canvas renderer ([GhosttyTerminalView]) reads the
 * latest immutable snapshot produced by that engine.
 */
internal class GhosttyTerminalFrontend : TerminalFrontend {
    /** Receives bytes the terminal asks to write back to the PTY. */
    var onPtyWrite: ((ByteArray) -> Unit)? = null

    /** Receives copied terminal text; the surface installs a clipboard writer. */
    var copySink: ((String) -> Unit)? = null

    /** Terminal effects from OSC/BEL. */
    var onBell: (() -> Unit)? = null
    var onTitleChange: ((String) -> Unit)? = null
    var onPwdChange: ((String) -> Unit)? = null

    private var lastSearchQuery: String? = null

    private val engine = GhosttyNativeEngine { bytes ->
        onPtyWrite?.invoke(bytes)
    }.apply {
        onBell = { this@GhosttyTerminalFrontend.onBell?.invoke() }
        onTitleChange = { this@GhosttyTerminalFrontend.onTitleChange?.invoke(it) }
        onPwdChange = { this@GhosttyTerminalFrontend.onPwdChange?.invoke(it) }
    }
    private var started = false

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
        ensureStarted()
        view = terminalView
        terminalView.attach(engine)
    }

    internal fun detachView(terminalView: GhosttyTerminalView) {
        if (view === terminalView) view = null
    }

    internal fun scrollLines(delta: Int) {
        ensureStarted()
        engine.requestScrollViewport(delta)
        view?.invalidate()
    }

    override suspend fun write(bytes: ByteArray) {
        ensureStarted()
        engine.write(bytes)
        view?.invalidate()
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

    override fun setImeVisible(visible: Boolean) = Unit

    override fun paste(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) pasteText(text)
    }

    override fun pasteText(text: String) {
        if (text.isEmpty()) return
        ensureStarted()
        engine.requestPasteText(text)
    }

    override fun enterSelectionMode() = Unit

    override fun selectAll() {
        ensureStarted()
        engine.requestSelectAll()
        view?.invalidate()
    }

    override fun copySelection() {
        ensureStarted()
        engine.requestCopySelection { bytes ->
            val text = bytes?.decodeToString().orEmpty()
            if (text.isEmpty()) return@requestCopySelection
            onCopied?.invoke(text.length)
            copySink?.invoke(text)
        }
    }

    override fun clearSelection() = Unit

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
            engine.requestSearchSet(query) { total ->
                onSearchResults?.invoke(if (total > 0) 0 else -1, total)
            }
        } else {
            engine.requestSearchSelect(backwards) { index, total ->
                onSearchResults?.invoke(index, total)
            }
        }
    }

    override fun clearSearch() {
        lastSearchQuery = null
        ensureStarted()
        engine.requestSearchClear()
        onSearchResults?.invoke(-1, 0)
    }

    override fun armCtrl() = Unit
    override fun focusAndShowKeyboard() = Unit
    override fun hideKeyboard() = Unit

    override fun close() {
        engine.close()
        view = null
        started = false
    }

    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}
