package com.yang136.sshhelper.ui

/** Converts text committed by an Android IME to terminal line discipline. */
internal fun normalizeTerminalInput(text: String): String =
    text.replace("\r\n", "\r").replace('\n', '\r')

/** Android wheel-up is positive; Ghostty viewport-up is negative. */
internal fun mouseWheelViewportDelta(axisValue: Float): Int = when {
    axisValue > 0f -> -wheelEventCount(axisValue)
    axisValue < 0f -> wheelEventCount(-axisValue)
    else -> 0
}

internal fun wheelEventCount(magnitude: Float): Int =
    kotlin.math.ceil(magnitude.toDouble()).toInt().coerceIn(1, 10)
