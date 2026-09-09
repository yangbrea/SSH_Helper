package com.yang136.sshhelper.ui

import android.content.Context
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
    var onBell: (() -> Unit)?
    var onTitleChange: ((String) -> Unit)?
    var onPwdChange: ((String) -> Unit)?

    suspend fun write(bytes: ByteArray)
    /** Replays buffered output without sending historical terminal replies to the live PTY. */
    suspend fun restore(bytes: ByteArray) = write(bytes)
    suspend fun reset()
    fun setAppearance(palette: TerminalPalette, fontSize: Int)
    fun setImeVisible(visible: Boolean)

    fun paste(context: Context)
    fun pasteText(text: String)
    fun sendInput(bytes: ByteArray)

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

/** Ghostty is the only terminal frontend. */
internal fun createTerminalFrontend(): GhosttyTerminalFrontend = GhosttyTerminalFrontend()
