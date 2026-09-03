package com.yang136.sshhelper.ui

import android.content.Context
import com.yang136.sshhelper.settings.TerminalBackend
import com.yang136.sshhelper.ui.theme.TerminalPalette

/**
 * Backend-independent terminal surface contract.
 *
 * SessionManager/JSch must never depend on this interface's implementations.
 */
internal interface TerminalFrontend {
    /** Whether this backend can honor case-sensitive terminal search. */
    val supportsCaseSensitiveSearch: Boolean get() = true

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
 * XTERM returns the WebView/xterm compatibility frontend. GHOSTTY returns the
 * native Canvas frontend. The rollout setting keeps both paths reversible.
 */
internal fun createTerminalFrontend(backend: TerminalBackend): TerminalFrontend =
    when (backend) {
        TerminalBackend.XTERM -> XtermTerminalFrontend()
        TerminalBackend.GHOSTTY -> GhosttyTerminalFrontend()
    }
