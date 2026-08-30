package com.yang136.sshhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLayoutStateTest {
    @Test
    fun panelsAreMutuallyExclusiveAndToggleClosed() {
        val sessions = reduceTerminalLayout(
            TerminalLayoutState(),
            TerminalLayoutAction.TogglePanel(TerminalPanel.SESSIONS),
        )
        val search = reduceTerminalLayout(
            sessions,
            TerminalLayoutAction.TogglePanel(TerminalPanel.SEARCH),
        )
        val closed = reduceTerminalLayout(
            search,
            TerminalLayoutAction.TogglePanel(TerminalPanel.SEARCH),
        )

        assertEquals(TerminalPanel.SESSIONS, sessions.panel)
        assertEquals(TerminalPanel.SEARCH, search.panel)
        assertEquals(TerminalPanel.NONE, closed.panel)
    }

    @Test
    fun selectionOwnsPanelOnlyWhileActive() {
        val selecting = reduceTerminalLayout(
            TerminalLayoutState(panel = TerminalPanel.SEARCH),
            TerminalLayoutAction.SelectionChanged(active = true),
        )
        val finished = reduceTerminalLayout(
            selecting,
            TerminalLayoutAction.SelectionChanged(active = false),
        )

        assertEquals(TerminalPanel.SELECTION, selecting.panel)
        assertEquals(TerminalPanel.NONE, finished.panel)
    }

    @Test
    fun extraKeysAreIndependentFromContextPanel() {
        val opened = reduceTerminalLayout(
            TerminalLayoutState(panel = TerminalPanel.SESSIONS),
            TerminalLayoutAction.ToggleExtraKeys,
        )
        val closed = reduceTerminalLayout(opened, TerminalLayoutAction.ToggleExtraKeys)

        assertEquals(TerminalPanel.SESSIONS, opened.panel)
        assertTrue(opened.extraKeysVisible)
        assertFalse(closed.extraKeysVisible)
    }
}
