package com.yang136.sshhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalInputTest {
    @Test
    fun normalizesImeNewlinesWithoutDuplicatingCarriageReturns() {
        assertEquals("one\rtwo\rthree", normalizeTerminalInput("one\ntwo\r\nthree"))
        assertEquals("already\rnormalized", normalizeTerminalInput("already\rnormalized"))
    }

    @Test
    fun mapsAndroidWheelDirectionToGhosttyViewportDirection() {
        assertEquals(-1, mouseWheelViewportDelta(0.2f))
        assertEquals(-3, mouseWheelViewportDelta(2.2f))
        assertEquals(2, mouseWheelViewportDelta(-1.2f))
        assertEquals(0, mouseWheelViewportDelta(0f))
    }
}
