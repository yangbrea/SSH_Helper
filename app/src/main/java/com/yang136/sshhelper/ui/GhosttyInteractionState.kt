package com.yang136.sshhelper.ui

internal class GhosttyImeState {
    var composingText: String = ""
        private set

    val isComposing: Boolean get() = composingText.isNotEmpty()

    fun setComposing(text: CharSequence?) {
        composingText = text?.toString().orEmpty()
    }

    fun commit(text: CharSequence?): String? {
        composingText = ""
        return text?.toString()?.takeIf(String::isNotEmpty)?.let(::normalizeTerminalInput)
    }

    fun cancel() {
        composingText = ""
    }

    fun deleteCount(beforeLength: Int, maximum: Int): Int =
        if (isComposing) 0 else beforeLength.coerceIn(0, maximum)
}

internal enum class GhosttyTouchMode {
    IDLE,
    MOUSE,
    TWO_FINGER_SCROLL,
    SELECTION_ARMED,
    SELECTION,
}

internal class GhosttyTouchState {
    var mode: GhosttyTouchMode = GhosttyTouchMode.IDLE
        private set

    val mousePressed: Boolean get() = mode == GhosttyTouchMode.MOUSE
    val twoFingerScrolling: Boolean get() = mode == GhosttyTouchMode.TWO_FINGER_SCROLL
    val selectionArmed: Boolean get() = mode == GhosttyTouchMode.SELECTION_ARMED
    val selectionActive: Boolean get() = mode == GhosttyTouchMode.SELECTION

    fun armSelection(): Boolean {
        val releaseMouse = mousePressed
        mode = GhosttyTouchMode.SELECTION_ARMED
        return releaseMouse
    }

    fun beginMouse() {
        if (mode == GhosttyTouchMode.IDLE) mode = GhosttyTouchMode.MOUSE
    }

    /** Returns true when a terminal mouse release must be sent first. */
    fun beginTwoFingerScroll(): Boolean {
        val releaseMouse = mousePressed
        mode = GhosttyTouchMode.TWO_FINGER_SCROLL
        return releaseMouse
    }

    /** Returns true when a terminal mouse release must be sent first. */
    fun beginSelection(): Boolean {
        val releaseMouse = mousePressed
        mode = GhosttyTouchMode.SELECTION
        return releaseMouse
    }

    fun releaseMouse(): Boolean {
        if (!mousePressed) return false
        mode = GhosttyTouchMode.IDLE
        return true
    }

    fun finishTwoFingerScroll(): Boolean {
        if (!twoFingerScrolling) return false
        mode = GhosttyTouchMode.IDLE
        return true
    }

    fun finishSelection(): Boolean {
        if (!selectionActive) return false
        mode = GhosttyTouchMode.IDLE
        return true
    }

    fun clearSelection() {
        if (selectionActive || selectionArmed) mode = GhosttyTouchMode.IDLE
    }
}
