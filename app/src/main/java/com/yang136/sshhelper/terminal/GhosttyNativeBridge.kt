package com.yang136.sshhelper.terminal

/**
 * Managed JNI bridge to libghostty-vt.
 *
 * Handles are opaque registry IDs rather than native pointers. The engine
 * serializes terminal access; clipboard resolve/cancel and repeated close are
 * additionally safe while a synchronous native callback is pending.
 */
object GhosttyNativeBridge {
    const val PASTE_RESULT_WRITTEN = 0
    const val PASTE_RESULT_EMPTY = 1
    const val PASTE_RESULT_REJECTED = 2
    const val PASTE_RESULT_ERROR = 3
    const val PASTE_SOURCE_CLIPBOARD = 0
    const val PASTE_SOURCE_TEXT = 1

    data class ClipboardWriteRequest(
        val handle: Long,
        val requestId: Long,
        val text: String,
        val programName: String,
    )

    private val clipboardListeners = java.util.concurrent.ConcurrentHashMap<
        Long,
        (ClipboardWriteRequest) -> Unit
    >()

    init {
        System.loadLibrary("sshhelper_terminal")
    }

    /** Returns an opaque managed handle, or 0/throws when creation fails. */
    external fun nativeCreateManaged(cols: Int, rows: Int): Long

    /** Frees a managed handle; 0 is a safe no-op. */
    external fun nativeFreeManaged(handle: Long)

    /** Returns the libghostty-vt version string baked into the native build. */
    external fun nativeVersion(): String

    /** Resets terminal state and increments the generation. */
    external fun nativeReset(handle: Long)

    /** Feeds PTY output bytes through the VT parser. */
    external fun nativeWrite(handle: Long, data: ByteArray)

    /** Pastes text into the terminal according to current bracketed-paste mode. */
    external fun nativePasteText(
        handle: Long,
        data: ByteArray,
        source: Int,
        allowUnsafe: Boolean,
    ): Int

    /** Scrolls the viewport by a signed row delta (negative scrolls up). */
    external fun nativeScrollViewport(handle: Long, deltaRows: Int)

    /** Returns the viewport to the active screen before user input. */
    external fun nativeScrollViewportToBottom(handle: Long)

    /** Selects all terminal content and installs it as the active selection. */
    external fun nativeSelectAll(handle: Long): Boolean

    /** Starts a selection gesture press at a viewport cell. */
    external fun nativeSelectionPress(handle: Long, col: Int, row: Int): Boolean

    /** Extends the active selection gesture to a viewport cell. */
    external fun nativeSelectionDrag(handle: Long, col: Int, row: Int): Boolean

    /** Ends the active selection gesture at a viewport cell (may be -1,-1). */
    external fun nativeSelectionRelease(handle: Long, col: Int, row: Int): Boolean

    /** Clears the active selection and resets selection gesture state. */
    external fun nativeSelectionClear(handle: Long)

    /** Returns true when the terminal has enabled any mouse reporting mode. */
    external fun nativeMouseReportingActive(handle: Long): Boolean

    /** Returns the hyperlink URI at a viewport cell, or null. */
    external fun nativeLinkUriAt(handle: Long, col: Int, row: Int): ByteArray?

    /** Returns the active selection as plain UTF-8 text, or null. */
    external fun nativeCopySelection(handle: Long): ByteArray?

    /** Resizes the terminal grid and pixel cell size. */
    external fun nativeResize(
        handle: Long,
        cols: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
    )

    /** Sets native default colors so render snapshots use the app palette. */
    external fun nativeSetAppearance(
        handle: Long,
        backgroundArgb: Int,
        foregroundArgb: Int,
        cursorArgb: Int,
        paletteArgb: IntArray,
        dark: Boolean,
    )

    /** Encodes focus state only when DEC mode 1004 is enabled. */
    external fun nativeEncodeFocus(handle: Long, focused: Boolean): ByteArray?
    external fun nativeFocusReportingActive(handle: Long): Boolean

    /** Thread-safe reply/cancel operations; these never call into Ghostty. */
    external fun nativeResolveClipboardWrite(handle: Long, requestId: Long, allowed: Boolean)
    external fun nativeCancelClipboardWrites(handle: Long)

    fun registerClipboardListener(handle: Long, listener: (ClipboardWriteRequest) -> Unit) {
        clipboardListeners[handle] = listener
    }

    fun unregisterClipboardListener(handle: Long) {
        clipboardListeners.remove(handle)
    }

    @JvmStatic
    private fun onNativeClipboardWrite(
        handle: Long,
        requestId: Long,
        data: ByteArray,
        programName: ByteArray,
    ) {
        val listener = clipboardListeners[handle]
        if (listener == null) {
            nativeResolveClipboardWrite(handle, requestId, false)
            return
        }
        listener(
            ClipboardWriteRequest(
                handle = handle,
                requestId = requestId,
                text = data.decodeToString(),
                programName = programName.decodeToString(),
            ),
        )
    }

    /** Drains bytes libghostty asked to write back to the PTY. */
    external fun nativeDrainPtyWrites(handle: Long): ByteArray?

    /** Returns and clears event flags (bell/title/pwd) since last call. */
    external fun nativeTakeEventFlags(handle: Long): Int

    /** Returns current OSC title as UTF-8, or null. */
    external fun nativeGetTitle(handle: Long): ByteArray?

    /** Returns current OSC working directory as UTF-8, or null. */
    external fun nativeGetPwd(handle: Long): ByteArray?

    /** Sets the search needle and restarts incremental search work. */
    external fun nativeSearchSet(handle: Long, query: ByteArray?, caseSensitive: Boolean)

    /** Performs at most roughly 4 ms of search work; true when caught up. */
    external fun nativeSearchStep(handle: Long): Boolean

    /** Selects next/previous search match; returns selected index or -1. */
    external fun nativeSearchSelect(handle: Long, backwards: Boolean): Int

    /** Returns the selected match index without moving it. */
    external fun nativeSearchSelectedIndex(handle: Long): Int

    /** Returns current total search match count. */
    external fun nativeSearchTotal(handle: Long): Int

    /** Encodes a normalized key event to terminal bytes, or null. */
    external fun nativeEncodeKey(
        handle: Long,
        action: Int,
        keyCode: Int,
        mods: Int,
        unshiftedCodepoint: Int,
        utf8: ByteArray?,
    ): ByteArray?

    /** Encodes a normalized mouse event to terminal bytes, or null. */
    external fun nativeEncodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
    ): ByteArray?

    /** Clears search needle. */
    external fun nativeSearchClear(handle: Long)

    /**
     * Writes the current render snapshot into [buffer] (a direct
     * little-endian ByteBuffer). Returns the number of dirty rows written, or
     * -1 when [buffer] is too small.
     */
    external fun nativeRenderSnapshot(handle: Long, buffer: java.nio.ByteBuffer): Int
}
