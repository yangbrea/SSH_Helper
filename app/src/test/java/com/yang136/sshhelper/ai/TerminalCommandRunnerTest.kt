package com.yang136.sshhelper.ai

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.TerminalOutputEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandRunnerTest {
    @Test
    fun capturesOnlyMarkerBoundedOutputAndHandlesSplitMarkers() = runBlocking {
        val terminal = FakeTerminalIo()
        val runner = TerminalCommandRunner(terminal, defaultTimeoutMillis = 1_000, probeTimeoutMillis = 500)

        val result = runner.execute(terminal.id, "printf '%s' 'hello'")

        assertEquals(CommandExecutionStatus.SUCCEEDED, result.status)
        assertEquals(0, result.exitCode)
        assertEquals("hello\nworld", result.uiOutput)
        assertFalse(result.uiOutput.contains("SH_HELPER"))
        assertFalse(result.uiOutput.contains("\u001B"))
        val wrapper = terminal.writes.last().toString(Charsets.UTF_8)
        assertTrue(wrapper.contains("'\"'\"'"))
    }

    @Test
    fun timeoutKeepsCollectingUntilUserStopsWaiting() = runBlocking {
        val terminal = FakeTerminalIo(completeCommands = false)
        val runner = TerminalCommandRunner(terminal, defaultTimeoutMillis = 20, probeTimeoutMillis = 500)
        val timedOut = CompletableDeferred<Unit>()
        val running = async {
            runner.execute(terminal.id, "tail -f log", onUpdate = {
                if (it is TerminalCommandUpdate.TimedOut) timedOut.complete(Unit)
            })
        }

        timedOut.await()
        assertFalse(running.isCompleted)
        runner.interrupt(terminal.id)
        assertTrue(terminal.writes.any { it.contentEquals(byteArrayOf(3)) })
        runner.stopWaiting(terminal.id)

        assertEquals(CommandExecutionStatus.STOPPED, running.await().status)
    }

    @Test
    fun disconnectWithoutEndMarkerIsDistinctResult() = runBlocking {
        val terminal = FakeTerminalIo(completeCommands = false, disconnectAfterCommand = true)
        val runner = TerminalCommandRunner(terminal, defaultTimeoutMillis = 1_000, probeTimeoutMillis = 500)

        val result = runner.execute(terminal.id, "sleep 60")

        assertEquals(CommandExecutionStatus.DISCONNECTED, result.status)
        assertTrue(result.message.orEmpty().contains("未收到结束标记"))
    }

    @Test
    fun unsupportedShellNeverWritesCommandWrapper() = runBlocking {
        val terminal = FakeTerminalIo(shell = "fish")
        val runner = TerminalCommandRunner(terminal, probeTimeoutMillis = 500)

        val result = runner.execute(terminal.id, "pwd")

        assertEquals(CommandExecutionStatus.UNSUPPORTED_SHELL, result.status)
        assertEquals(1, terminal.writes.size)
    }

    @Test
    fun boundsUiAndModelOutputWithExplicitTruncationNotice() = runBlocking {
        val terminal = FakeTerminalIo(commandOutput = "x".repeat(80 * 1024))
        val runner = TerminalCommandRunner(terminal, defaultTimeoutMillis = 1_000, probeTimeoutMillis = 500)

        val result = runner.execute(terminal.id, "generate-output")

        assertTrue(result.truncated)
        assertTrue(result.uiOutput.contains("已截断"))
        assertTrue(result.uiOutput.encodeToByteArray().size < 66 * 1024)
        assertTrue(result.modelOutput.encodeToByteArray().size < 18 * 1024)
    }

    @Test
    fun decodesUtf8AndAnsiSequencesSplitAcrossChunks() = runBlocking {
        val raw = "中文 \u001B[32mgreen\u001B[0m".encodeToByteArray()
        val terminal = FakeTerminalIo(
            rawCommandChunks = listOf(
                raw.copyOfRange(0, 1),
                raw.copyOfRange(1, 8),
                raw.copyOfRange(8, 11),
                raw.copyOfRange(11, raw.size),
            ),
        )
        val runner = TerminalCommandRunner(terminal, defaultTimeoutMillis = 1_000, probeTimeoutMillis = 500)

        val result = runner.execute(terminal.id, "printf output")

        assertEquals("中文 green", result.uiOutput)
        assertFalse(result.uiOutput.contains('\uFFFD'))
        assertFalse(result.uiOutput.contains('\u001B'))
    }
}

private class FakeTerminalIo(
    private val shell: String = "bash",
    private val completeCommands: Boolean = true,
    private val disconnectAfterCommand: Boolean = false,
    private val commandOutput: String = "\u001B[31mhello\u001B[0m\r\nworld",
    private val rawCommandChunks: List<ByteArray>? = null,
) : TerminalIo {
    val id = SessionId("test")
    private val chunks = MutableSharedFlow<TerminalOutputEvent.Chunk>(extraBufferCapacity = 32)
    private var sequence = 20L
    val state = MutableStateFlow(
        ManagedSessionState(
            id = id,
            profile = HostProfile(1, "test", "localhost", username = "user", authType = AuthType.PASSWORD),
            displayName = "test",
            connection = ConnectionState.Connected("test"),
        ),
    )
    val writes = mutableListOf<ByteArray>()

    override fun state(sessionId: SessionId) = state

    override fun output(sessionId: SessionId): Flow<TerminalOutputEvent> = flow {
        emit(TerminalOutputEvent.Snapshot(sequence, "old output".encodeToByteArray()))
        chunks.collect { emit(it) }
    }

    override suspend fun write(sessionId: SessionId, data: ByteArray) {
        writes += data.copyOf()
        if (data.contentEquals(byteArrayOf(3))) return
        val text = data.toString(Charsets.UTF_8)
        val probePrefix = Regex("(__SH_HELPER_SHELL_[a-f0-9]+__:)").find(text)?.groupValues?.get(1)
        if (probePrefix != null) {
            emit("$text\r\n$probePrefix/bin/$shell\r\n")
            return
        }
        val begin = Regex("(__SH_HELPER_BEGIN_[a-f0-9]+__)").find(text)?.groupValues?.get(1) ?: return
        val end = Regex("(__SH_HELPER_END_[a-f0-9]+__:)").find(text)?.groupValues?.get(1) ?: return
        emit(text + "\r\n") // PTY echo must not be captured.
        emit("noise before\r\n${begin.take(13)}")
        emit(begin.drop(13) + "\r\n")
        if (rawCommandChunks != null) {
            rawCommandChunks.forEach { emitBytes(it) }
            emit("\r\n")
        } else {
            emit("$commandOutput\r\n")
        }
        when {
            disconnectAfterCommand -> state.value = state.value.copy(connection = ConnectionState.Error("lost"))
            completeCommands -> {
                emit(end.take(12))
                emit(end.drop(12) + "0\r\n")
            }
        }
    }

    private suspend fun emit(text: String) {
        emitBytes(text.encodeToByteArray())
    }

    private suspend fun emitBytes(bytes: ByteArray) {
        chunks.emit(TerminalOutputEvent.Chunk(++sequence, bytes))
    }
}
