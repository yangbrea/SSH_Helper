package com.yang136.sshhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhosttyInteractionStateTest {
    @Test
    fun imeCompositionCommitsOnceAndSuppressesRemoteDeleteWhileComposing() {
        val state = GhosttyImeState()
        state.setComposing("中文")
        assertTrue(state.isComposing)
        assertEquals(0, state.deleteCount(1, 64))
        assertEquals("中文\r", state.commit("中文\n"))
        assertFalse(state.isComposing)
        assertNull(state.commit(null))
        assertEquals(64, state.deleteCount(100, 64))
        state.setComposing("cancelled")
        state.cancel()
        assertEquals("", state.composingText)
    }

    @Test
    fun touchTransitionsReleaseMouseBeforeScrollOrSelection() {
        val state = GhosttyTouchState()
        state.beginMouse()
        assertTrue(state.mousePressed)
        assertTrue(state.beginTwoFingerScroll())
        assertTrue(state.twoFingerScrolling)
        assertTrue(state.finishTwoFingerScroll())

        state.beginMouse()
        assertTrue(state.beginSelection())
        assertTrue(state.selectionActive)
        assertTrue(state.finishSelection())
        state.armSelection()
        assertTrue(state.selectionArmed)
        assertFalse(state.beginSelection())
        state.clearSelection()
        assertEquals(GhosttyTouchMode.IDLE, state.mode)

        state.beginMouse()
        assertTrue(state.armSelection())
        assertTrue(state.selectionArmed)
    }
}
