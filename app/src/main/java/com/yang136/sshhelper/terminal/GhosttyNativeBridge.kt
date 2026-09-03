package com.yang136.sshhelper.terminal

/**
 * Managed JNI bridge to libghostty-vt.
 *
 * A handle points to a C++ [NativeTerminal] wrapper that owns the Ghostty
 * terminal, render state, row iterators and WRITE_PTY response buffer. All
 * methods for the same handle must be called from a single thread.
 */
object GhosttyNativeBridge {
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
    external fun nativePasteText(handle: Long, data: ByteArray)

    /** Scrolls the viewport by a signed row delta (negative scrolls up). */
    external fun nativeScrollViewport(handle: Long, deltaRows: Int)

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
    external fun nativeSetDefaultColors(
        handle: Long,
        backgroundArgb: Int,
        foregroundArgb: Int,
        cursorArgb: Int,
    )

    /** Drains bytes libghostty asked to write back to the PTY. */
    external fun nativeDrainPtyWrites(handle: Long): ByteArray?

    /** Returns and clears event flags (bell/title/pwd) since last call. */
    external fun nativeTakeEventFlags(handle: Long): Int

    /** Returns current OSC title as UTF-8, or null. */
    external fun nativeGetTitle(handle: Long): ByteArray?

    /** Returns current OSC working directory as UTF-8, or null. */
    external fun nativeGetPwd(handle: Long): ByteArray?

    /** Sets search needle and returns total match count. */
    external fun nativeSearchSet(handle: Long, query: ByteArray?): Int

    /** Selects next/previous search match; returns selected index or -1. */
    external fun nativeSearchSelect(handle: Long, backwards: Boolean): Int

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
