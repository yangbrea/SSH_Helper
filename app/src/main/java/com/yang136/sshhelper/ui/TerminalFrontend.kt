package com.yang136.sshhelper.ui

import android.content.Context
import com.yang136.sshhelper.settings.TerminalBackend
import com.yang136.sshhelper.ui.theme.TerminalPalette

/**
 * Backend-independent terminal surface contract.
 *
 * [XtermTerminalFrontend] is the production backend until the Ghostty canvas
 * renderer lands. SessionManager/JSch must never depend on this interface's
 * implementations.
 */
internal interface TerminalFrontend {
    var onSelectionStateChanged: ((Boolean, Boolean) -> Unit)?
    var onCopied: ((Int) -> Unit)?
    var onSearchResults: ((Int, Int) -> Unit)?
    var onOpenLink: ((String) -> Unit)?
    var onCtrlArmed: ((Boolean) -> Unit)?
    var onRenderingDelayed: ((Boolean) -> Unit)?

    suspend fun write(bytes: ByteArray)
    suspend fun reset()
    fun setAppearance(palette: TerminalPalette, fontSize: Int)
    fun setImeVisible(visible: Boolean)

    fun paste(context: Context)
    fun pasteText(text: String)

    fun enterSelectionMode()
    fun selectAll()
    fun copySelection()
    fun clearSelection()

    fun search(query: String, backwards: Boolean, caseSensitive: Boolean)
    fun clearSearch()

    fun armCtrl()
    fun focusAndShowKeyboard()
    fun hideKeyboard()
    fun close()
}

/**
 * Factory for the development/gray rollout switch.
 *
 * XTERM returns the production WebView/xterm frontend. GHOSTTY returns the
 * Step 3 placeholder; [TerminalScreen] renders an explicit "not implemented"
 * surface until the native renderer lands in later steps.
 */
internal fun createTerminalFrontend(backend: TerminalBackend): TerminalFrontend =
    when (backend) {
        TerminalBackend.XTERM -> XtermTerminalFrontend()
        TerminalBackend.GHOSTTY -> GhosttyTerminalFrontend()
    }
