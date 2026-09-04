package com.yang136.sshhelper.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplexerTest {
    @Test
    fun tmuxListParsesEverySessionAndRealClientCounts() {
        val sessions = TmuxMultiplexer.parseSessions("work\t0\r\nshared name\t3\n")

        assertEquals(
            listOf(
                RemoteMultiplexerSession("work", 0),
                RemoteMultiplexerSession("shared name", 3),
            ),
            sessions,
        )
        assertFalse(sessions.first().attached)
        assertTrue(sessions.last().attached)
    }

    @Test
    fun tmuxListParsesLiteralBackslashTabDelimiterUsedBySomeShells() {
        // Some tmux/shell combinations return the -F separator as literal "\t"
        // rather than a real tab. This used to produce a fake session named
        // "shh-1\t0" and made the real "shh-1" impossible to attach.
        val sessions = TmuxMultiplexer.parseSessions("shh-1\\t0\r\nwork\\t1\n")

        assertEquals(
            listOf(
                RemoteMultiplexerSession("shh-1", 0),
                RemoteMultiplexerSession("work", 1),
            ),
            sessions,
        )
    }

    @Test
    fun emptyTmuxOutputMeansNoSessions() {
        assertEquals(emptyList<RemoteMultiplexerSession>(), TmuxMultiplexer.parseSessions(""))
        assertTrue(TmuxMultiplexer.listSessionsCommand().contains("status=\$?"))
        assertTrue(TmuxMultiplexer.listSessionsCommand().contains("error connecting to"))
        assertTrue(TmuxMultiplexer.listSessionsCommand().contains("No such file or directory"))
    }

    @Test
    fun zmxDetailedListParsesNamesAndClients() {
        val sessions = ZmxMultiplexer.parseSessions(
            "  name=alpha\tpid=10\tclients=2\nname=beta\tclients=0\tcmd=/bin/zsh\n",
        )

        assertEquals(
            listOf(RemoteMultiplexerSession("alpha", 2), RemoteMultiplexerSession("beta", 0)),
            sessions,
        )
    }

    @Test
    fun generatedNamesUseTheLowestUnusedNumberAcrossRemoteAndLocalSessions() {
        val remote = listOf(
            RemoteMultiplexerSession("shh-1", 0),
            RemoteMultiplexerSession("personal", 1),
            RemoteMultiplexerSession("shh-3", 2),
        )

        assertEquals("shh-4", nextGeneratedSessionName(remote, listOf("shh-2")))
    }

    @Test
    fun shellQuotingCoversWhitespaceQuotesAndMetacharacters() {
        val hostile = "a b'c;\$(touch nope)\n*"
        assertEquals("'a b'\\''c;\$(touch nope)\n*'", shellQuote(hostile))
        val attach = TmuxMultiplexer.attachCommand(hostile)
        assertTrue(attach.contains(shellQuote(hostile)))
        assertTrue(attach.contains(shellQuote("=$hostile")))
        assertTrue(attach.contains("set -g mouse on"))
        assertTrue(TmuxMultiplexer.createCommand("shh-1").contains("set -g mouse on"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun strictCreateRejectsNonGeneratedNames() {
        TmuxMultiplexer.createCommand("someone else's session")
    }

    @Test
    fun deleteCommandsUseExactSessionTargets() {
        val name = "shh-1"
        val tmux = TmuxMultiplexer.deleteCommand(name)
        assertTrue(tmux.contains("tmux kill-session"))
        assertTrue(tmux.contains(shellQuote("=$name")))

        val zmx = ZmxMultiplexer.deleteCommand("space and ' quote")
        assertTrue(zmx.contains("zmx kill"))
        assertTrue(zmx.contains(shellQuote("space and ' quote")))
    }

    @Test
    fun zmxAttachClearsNestedSessionAndDisablesDetachKey() {
        val command = ZmxMultiplexer.attachCommand("space and ' quote")
        assertTrue(command.contains("unset ZMX_SESSION"))
        assertTrue(command.contains("ZMX_NO_DETACH_KEY=1"))
        assertTrue(command.contains("zmx list --short"))
    }
}
