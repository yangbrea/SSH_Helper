package com.yang136.sshhelper.ui

import android.content.Context
import com.yang136.sshhelper.ui.theme.TerminalPalette

/**
 * Ghostty-backed terminal frontend.
 *
 * This is a Step 3 skeleton only. The concrete renderer/input implementation
 * is added in later migration steps (JNI core, Canvas renderer, IME/touch).
 * It is intentionally not returned by [createTerminalFrontend] until those
 * steps are complete so the app always keeps a usable terminal.
 */
internal class GhosttyTerminalFrontend : TerminalFrontend {
    override var onSelectionStateChanged: ((Boolean, Boolean) -> Unit)? = null
    override var onCopied: ((Int) -> Unit)? = null
    override var onSearchResults: ((Int, Int) -> Unit)? = null
    override var onOpenLink: ((String) -> Unit)? = null
    override var onCtrlArmed: ((Boolean) -> Unit)? = null
    override var onRenderingDelayed: ((Boolean) -> Unit)? = null

    override suspend fun write(bytes: ByteArray) = unsupported()
    override suspend fun reset() = unsupported()
    override fun setAppearance(palette: TerminalPalette, fontSize: Int) = unsupported()
    override fun setImeVisible(visible: Boolean) = unsupported()

    override fun paste(context: Context) = unsupported()
    override fun pasteText(text: String) = unsupported()

    override fun enterSelectionMode() = unsupported()
    override fun selectAll() = unsupported()
    override fun copySelection() = unsupported()
    override fun clearSelection() = unsupported()

    override fun search(query: String, backwards: Boolean, caseSensitive: Boolean) = unsupported()
    override fun clearSearch() = unsupported()

    override fun armCtrl() = unsupported()
    override fun focusAndShowKeyboard() = unsupported()
    override fun hideKeyboard() = unsupported()
    override fun close() = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Ghostty terminal renderer is not implemented yet")
}
