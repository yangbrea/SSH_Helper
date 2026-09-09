package com.yang136.sshhelper.ui

import com.yang136.sshhelper.ui.theme.TerminalPalette

internal fun TerminalPalette.toXterm256Argb(): IntArray {
    val values = IntArray(256)
    val ansi = listOf(
        black, red, green, yellow, blue, magenta, cyan, white,
        brightBlack, brightRed, brightGreen, brightYellow,
        brightBlue, brightMagenta, brightCyan, brightWhite,
    )
    ansi.forEachIndexed { index, value -> values[index] = terminalColorToArgb(value) }
    val levels = intArrayOf(0, 95, 135, 175, 215, 255)
    var index = 16
    for (red in levels) for (green in levels) for (blue in levels) {
        values[index++] = argb(red, green, blue)
    }
    for (step in 0 until 24) {
        val value = 8 + step * 10
        values[232 + step] = argb(value, value, value)
    }
    return values
}

internal fun isDarkTerminalColor(argb: Int): Boolean {
    val luminance = (
        0.2126 * ((argb ushr 16) and 0xff) +
            0.7152 * ((argb ushr 8) and 0xff) +
            0.0722 * (argb and 0xff)
        ) / 255.0
    return luminance < 0.5
}

private fun argb(red: Int, green: Int, blue: Int): Int =
    0xff000000.toInt() or (red shl 16) or (green shl 8) or blue

internal fun terminalColorToArgb(value: String): Int {
    require(value.startsWith('#')) { "Terminal colors must use hexadecimal notation" }
    return when (value.length) {
        7 -> 0xff000000.toInt() or value.substring(1).toInt(16)
        9 -> {
            // TerminalPalette strings follow CSS #RRGGBBAA notation, whereas
            // Android Color.parseColor interprets eight digits as #AARRGGBB.
            val rgba = value.substring(1).toLong(16)
            (((rgba and 0xff) shl 24) or (rgba ushr 8)).toInt()
        }
        else -> error("Unsupported terminal color: $value")
    }
}
