package com.yang136.sshhelper.ui

import com.yang136.sshhelper.ui.theme.TerminalPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhosttyPaletteTest {
    private val palette = TerminalPalette(
        background = "#000000",
        foreground = "#ffffff",
        cursor = "#ffffff",
        cursorAccent = "#000000",
        selectionBackground = "#333333",
        black = "#010101",
        red = "#020202",
        green = "#030303",
        yellow = "#040404",
        blue = "#050505",
        magenta = "#060606",
        cyan = "#070707",
        white = "#080808",
        brightBlack = "#090909",
        brightRed = "#0a0a0a",
        brightGreen = "#0b0b0b",
        brightYellow = "#0c0c0c",
        brightBlue = "#0d0d0d",
        brightMagenta = "#0e0e0e",
        brightCyan = "#0f0f0f",
        brightWhite = "#101010",
    )

    @Test
    fun mapsThemeAnsiColorCubeAndGrayRamp() {
        val colors = palette.toXterm256Argb()
        assertEquals(256, colors.size)
        assertEquals(0xff010101.toInt(), colors[0])
        assertEquals(0xff101010.toInt(), colors[15])
        assertEquals(0xff000000.toInt(), colors[16])
        assertEquals(0xffff0000.toInt(), colors[196])
        assertEquals(0xffffffff.toInt(), colors[231])
        assertEquals(0xff080808.toInt(), colors[232])
        assertEquals(0xffeeeeee.toInt(), colors[255])
    }

    @Test
    fun classifiesDarkAndLightBackgrounds() {
        assertTrue(isDarkTerminalColor(0xff101010.toInt()))
        assertFalse(isDarkTerminalColor(0xfff0f0f0.toInt()))
        assertEquals(0x99155e75.toInt(), terminalColorToArgb("#155e7599"))
    }
}
