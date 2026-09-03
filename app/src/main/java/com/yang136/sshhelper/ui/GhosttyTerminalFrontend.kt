package com.yang136.sshhelper.ui

import android.content.Context
import com.yang136.sshhelper.ui.theme.TerminalPalette

/**
 * Ghostty-backed terminal frontend.
 *
 * This is a Step 3 development placeholder. The concrete native renderer and
 * input implementation is added in later migration steps (JNI core, Canvas
 * renderer, IME/touch). It intentionally does not silently fall back to the
 * Xterm backend; instead [TerminalScreen] shows an explicit development
 * placeholder surface while all terminal operations are no-ops.
 */
internal class GhosttyTerminalFrontend : TerminalFrontend {
    override var onSelectionStateChanged: ((Boolean, Boolean) -> Unit)? = null
    override var onCopied: ((Int) -> Unit)? = null
    override var onSearchResults: ((Int, Int) -> Unit)? = null
    override var onOpenLink: ((String) -> Unit)? = null
    override var onCtrlArmed: ((Boolean) -> Unit)? = null
    override var onRenderingDelayed: ((Boolean) -> Unit)? = null

    override suspend fun write(bytes: ByteArray) = Unit
    override suspend fun reset() = Unit
    override fun setAppearance(palette: TerminalPalette, fontSize: Int) = Unit
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
    override fun close() = Unit
}
