package com.yang136.sshhelper.ui

import android.content.Context
import com.yang136.sshhelper.terminal.GhosttyNativeBridge
import com.yang136.sshhelper.ui.theme.TerminalPalette

/**
 * Ghostty-backed terminal frontend.
 *
 * The frontend owns the native managed terminal handle. A Canvas renderer
 * ([GhosttyTerminalView]) may attach to it through [attachView]; before a
 * view is attached, writes/reset still reach the native engine so a later
 * render snapshot contains the current grid.
 *
 * The native library is loaded lazily so JVM unit tests can still verify
 * factory behavior without an Android device.
 */
internal class GhosttyTerminalFrontend : TerminalFrontend {
    private var handle: Long = 0L
    private var closed = false

    internal var view: GhosttyTerminalView? = null
        private set

    override var onSelectionStateChanged: ((Boolean, Boolean) -> Unit)? = null
    override var onCopied: ((Int) -> Unit)? = null
    override var onSearchResults: ((Int, Int) -> Unit)? = null
    override var onOpenLink: ((String) -> Unit)? = null
    override var onCtrlArmed: ((Boolean) -> Unit)? = null
    override var onRenderingDelayed: ((Boolean) -> Unit)? = null

    private fun ensureHandle(): Boolean {
        if (handle != 0L || closed) return handle != 0L
        handle = runCatching {
            GhosttyNativeBridge.nativeCreateManaged(cols = DEFAULT_COLS, rows = DEFAULT_ROWS)
        }.getOrDefault(0L)
        return handle != 0L
    }

    internal fun attachView(terminalView: GhosttyTerminalView) {
        if (closed) return
        if (!ensureHandle()) return
        view = terminalView
        terminalView.attach(handle)
    }

    internal fun detachView(terminalView: GhosttyTerminalView) {
        if (view === terminalView) view = null
    }

    override suspend fun write(bytes: ByteArray) {
        if (!ensureHandle()) return
        GhosttyNativeBridge.nativeWrite(handle, bytes)
        view?.invalidate()
    }

    override suspend fun reset() {
        if (!ensureHandle()) return
        GhosttyNativeBridge.nativeReset(handle)
        view?.invalidate()
    }

    override fun setAppearance(palette: TerminalPalette, fontSize: Int) {
        view?.setPalette(palette)
        view?.setFontSizeSp(fontSize.toFloat())
    }

    override fun setImeVisible(visible: Boolean) = Unit

    override fun paste(context: Context) = Unit
    override fun pasteText(text: String) = Unit

    override fun enterSelectionMode() = Unit
    override fun selectAll() = Unit
    override fun copySelection() = Unit
    override fun clearSelection() = Unit

    override fun search(query: String, backwards: Boolean, caseSensitive: Boolean) = Unit
    override fun clearSearch() = Unit

    override fun armCtrl() = Unit
    override fun focusAndShowKeyboard() = Unit
    override fun hideKeyboard() = Unit

    override fun close() {
        if (closed) return
        closed = true
        view = null
        if (handle != 0L) {
            GhosttyNativeBridge.nativeFreeManaged(handle)
            handle = 0L
        }
    }

    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}
