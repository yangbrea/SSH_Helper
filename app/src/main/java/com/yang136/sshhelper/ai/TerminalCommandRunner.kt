package com.yang136.sshhelper.ai

import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.SessionManager
import com.yang136.sshhelper.ssh.TerminalOutputEvent
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class TerminalCommandResult(
    val status: CommandExecutionStatus,
    val uiOutput: String,
    val modelOutput: String,
    val exitCode: Int? = null,
    val truncated: Boolean = false,
    val message: String? = null,
)

sealed interface TerminalCommandUpdate {
    data class Partial(val result: TerminalCommandResult) : TerminalCommandUpdate
    data class TimedOut(val result: TerminalCommandResult) : TerminalCommandUpdate
}

internal interface TerminalIo {
    fun state(sessionId: SessionId): StateFlow<ManagedSessionState>?
    fun output(sessionId: SessionId): Flow<TerminalOutputEvent>
    suspend fun write(sessionId: SessionId, data: ByteArray)
}

private class SessionManagerTerminalIo(private val sessions: SessionManager) : TerminalIo {
    override fun state(sessionId: SessionId) = sessions.state(sessionId)
    override fun output(sessionId: SessionId) = sessions.output(sessionId)
    override suspend fun write(sessionId: SessionId, data: ByteArray) = sessions.write(sessionId, data)
}

interface TerminalAgentCommandRunner {
    suspend fun execute(
        sessionId: SessionId,
        command: String,
        timeoutMillis: Long,
        onUpdate: (TerminalCommandUpdate) -> Unit,
    ): TerminalCommandResult
    suspend fun interrupt(sessionId: SessionId)
    fun stopWaiting(sessionId: SessionId)
    fun clear(sessionId: SessionId)
}

class TerminalCommandRunner internal constructor(
    private val terminal: TerminalIo,
    private val defaultTimeoutMillis: Long = 120_000,
    private val probeTimeoutMillis: Long = 8_000,
) : TerminalAgentCommandRunner {
    constructor(sessions: SessionManager) : this(SessionManagerTerminalIo(sessions))

    private sealed interface Signal {
        data class Bytes(val bytes: ByteArray) : Signal
        data object Disconnected : Signal
        data object Stop : Signal
    }

    private data class ActiveRun(val signals: Channel<Signal>)

    private val active = ConcurrentHashMap<SessionId, ActiveRun>()
    private val confirmedShells = ConcurrentHashMap<SessionId, String>()
    private val random = SecureRandom()

    override suspend fun execute(
        sessionId: SessionId,
        command: String,
        timeoutMillis: Long,
        onUpdate: (TerminalCommandUpdate) -> Unit,
    ): TerminalCommandResult {
        validateCommand(command)?.let {
            return TerminalCommandResult(CommandExecutionStatus.FAILED, "", "", message = it)
        }
        if (terminal.state(sessionId)?.value?.connection !is ConnectionState.Connected) {
            confirmedShells.remove(sessionId)
            return TerminalCommandResult(CommandExecutionStatus.DISCONNECTED, "", "", message = "SSH 会话未连接")
        }
        val shell = confirmedShells[sessionId] ?: probeShell(sessionId)?.also { confirmedShells[sessionId] = it }
        if (shell !in SUPPORTED_SHELLS) {
            return TerminalCommandResult(
                CommandExecutionStatus.UNSUPPORTED_SHELL,
                "",
                "",
                message = if (shell == null) "无法确认当前 Shell" else "当前 Shell（$shell）不支持自动采集",
            )
        }

        val signals = Channel<Signal>(Channel.UNLIMITED)
        val run = ActiveRun(signals)
        if (active.putIfAbsent(sessionId, run) != null) {
            return TerminalCommandResult(CommandExecutionStatus.FAILED, "", "", message = "该会话已有命令正在采集")
        }
        return try {
            collectCommand(sessionId, command, timeoutMillis, signals, onUpdate)
        } finally {
            active.remove(sessionId, run)
            signals.close()
        }
    }

    suspend fun execute(
        sessionId: SessionId,
        command: String,
        onUpdate: (TerminalCommandUpdate) -> Unit = {},
    ): TerminalCommandResult = execute(sessionId, command, defaultTimeoutMillis, onUpdate)

    override suspend fun interrupt(sessionId: SessionId) {
        if (active.containsKey(sessionId)) terminal.write(sessionId, byteArrayOf(3))
    }

    override fun stopWaiting(sessionId: SessionId) {
        active[sessionId]?.signals?.trySend(Signal.Stop)
    }

    override fun clear(sessionId: SessionId) {
        stopWaiting(sessionId)
        confirmedShells.remove(sessionId)
    }

    private suspend fun collectCommand(
        sessionId: SessionId,
        command: String,
        timeoutMillis: Long,
        signals: Channel<Signal>,
        onUpdate: (TerminalCommandUpdate) -> Unit,
    ): TerminalCommandResult = coroutineScope {
        val snapshotReady = CompletableDeferred<Long>()
        val outputJob = launch(start = CoroutineStart.UNDISPATCHED) {
            var baseline = Long.MAX_VALUE
            terminal.output(sessionId).collect { event ->
                when (event) {
                    is TerminalOutputEvent.Snapshot -> {
                        baseline = event.sequence
                        if (!snapshotReady.isCompleted) snapshotReady.complete(baseline)
                    }
                    is TerminalOutputEvent.Chunk -> {
                        if (!snapshotReady.isCompleted) snapshotReady.complete(event.sequence - 1)
                        if (event.sequence > baseline) signals.send(Signal.Bytes(event.bytes))
                    }
                }
            }
        }
        val stateJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val state = terminal.state(sessionId) ?: run {
                signals.send(Signal.Disconnected)
                return@launch
            }
            state.drop(1).collect {
                if (it.connection !is ConnectionState.Connected) {
                    confirmedShells.remove(sessionId)
                    signals.send(Signal.Disconnected)
                    return@collect
                }
            }
        }
        snapshotReady.await()
        val token = randomToken()
        val beginMarker = "__SH_HELPER_BEGIN_${token}__"
        val endPrefix = "__SH_HELPER_END_${token}__:"
        val wrapper = buildWrapper(command, beginMarker, endPrefix)
        terminal.write(sessionId, wrapper.encodeToByteArray())

        val capture = CommandOutputCapture(beginMarker, endPrefix)
        var timedOut = false
        var lastUpdateBytes = 0L
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        try {
            while (true) {
                val remainingMillis = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                val signal = if (!timedOut) withTimeoutOrNull(remainingMillis) { signals.receive() } else signals.receive()
                if (signal == null) {
                    timedOut = true
                    onUpdate(TerminalCommandUpdate.TimedOut(capture.result(CommandExecutionStatus.TIMED_OUT)))
                    continue
                }
                when (signal) {
                    is Signal.Bytes -> {
                        capture.append(signal.bytes)
                        capture.completedExitCode?.let { exitCode ->
                            return@coroutineScope capture.result(
                                if (exitCode == 0) CommandExecutionStatus.SUCCEEDED else CommandExecutionStatus.FAILED,
                                exitCode,
                            )
                        }
                        if (capture.totalCapturedBytes - lastUpdateBytes >= PARTIAL_UPDATE_BYTES) {
                            lastUpdateBytes = capture.totalCapturedBytes
                            onUpdate(TerminalCommandUpdate.Partial(capture.result(if (timedOut) CommandExecutionStatus.TIMED_OUT else CommandExecutionStatus.RUNNING)))
                        }
                    }
                    Signal.Disconnected -> return@coroutineScope capture.result(
                        CommandExecutionStatus.DISCONNECTED,
                        message = "SSH 会话已断开，未收到结束标记",
                    )
                    Signal.Stop -> return@coroutineScope capture.result(
                        CommandExecutionStatus.STOPPED,
                        message = "已停止采集；命令仍可能在终端运行",
                    )
                }
            }
            @Suppress("UNREACHABLE_CODE")
            capture.result(CommandExecutionStatus.STOPPED)
        } finally {
            outputJob.cancel()
            stateJob.cancel()
        }
    }

    private suspend fun probeShell(sessionId: SessionId): String? = coroutineScope {
        val token = randomToken()
        val prefix = "__SH_HELPER_SHELL_${token}__:"
        val found = CompletableDeferred<String?>()
        val ready = CompletableDeferred<Long>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            var baseline = Long.MAX_VALUE
            val lineBuffer = StringBuilder()
            terminal.output(sessionId).collect { event ->
                when (event) {
                    is TerminalOutputEvent.Snapshot -> {
                        baseline = event.sequence
                        if (!ready.isCompleted) ready.complete(baseline)
                    }
                    is TerminalOutputEvent.Chunk -> if (event.sequence > baseline) {
                        lineBuffer.append(AiContext.stripAnsi(event.bytes.toString(Charsets.UTF_8)).replace('\r', '\n'))
                        while ('\n' in lineBuffer) {
                            val end = lineBuffer.indexOf("\n")
                            val line = lineBuffer.substring(0, end)
                            lineBuffer.delete(0, end + 1)
                            if (line.startsWith(prefix) && !found.isCompleted) {
                                found.complete(normalizeShell(line.removePrefix(prefix)))
                            }
                        }
                    }
                }
            }
        }
        ready.await()
        terminal.write(sessionId, "printf '\\n${prefix}%s\\n' \"\$0\"\n".encodeToByteArray())
        val result = withTimeoutOrNull(probeTimeoutMillis) { found.await() }
        collector.cancel()
        result
    }

    private fun normalizeShell(value: String): String = value.trim().substringAfterLast('/').removePrefix("-").lowercase()

    private fun randomToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    internal fun buildWrapper(command: String, beginMarker: String, endPrefix: String): String {
        val escaped = command.replace("'", "'\"'\"'")
        return "printf '\\n${beginMarker}\\n'; eval '$escaped'; __sh_helper_ec=\$?; printf '\\n${endPrefix}%s\\n' \"\$__sh_helper_ec\"\n"
    }

    private companion object {
        val SUPPORTED_SHELLS = setOf("sh", "bash", "zsh", "ash", "dash", "ksh")
        const val PARTIAL_UPDATE_BYTES = 4 * 1024L
    }
}

private class CommandOutputCapture(
    private val beginMarker: String,
    private val endPrefix: String,
) {
    private val output = HeadTailByteBuffer(32 * 1024, 32 * 1024)
    private val modelOutput = HeadTailByteBuffer(8 * 1024, 8 * 1024)
    private val lineBuffer = StringBuilder()
    private var capturing = false
    var completedExitCode: Int? = null
        private set
    val totalCapturedBytes: Long get() = output.totalBytes

    fun append(bytes: ByteArray) {
        lineBuffer.append(AiContext.stripAnsi(bytes.toString(Charsets.UTF_8)).replace("\r\n", "\n").replace('\r', '\n'))
        while (true) {
            val end = lineBuffer.indexOf("\n")
            if (end < 0) break
            processLine(lineBuffer.substring(0, end))
            lineBuffer.delete(0, end + 1)
        }
        if (lineBuffer.length > MAX_PENDING_LINE_CHARS) {
            if (capturing) appendOutput(lineBuffer.substring(0, lineBuffer.length - MARKER_LOOKBEHIND).encodeToByteArray())
            lineBuffer.delete(0, lineBuffer.length - MARKER_LOOKBEHIND)
        }
    }

    private fun processLine(line: String) {
        when {
            !capturing && line == beginMarker -> capturing = true
            capturing && line.startsWith(endPrefix) && line.removePrefix(endPrefix).toIntOrNull() != null -> {
                completedExitCode = line.removePrefix(endPrefix).toInt()
                capturing = false
            }
            capturing -> appendOutput((line + "\n").encodeToByteArray())
        }
    }

    private fun appendOutput(bytes: ByteArray) {
        output.append(bytes)
        modelOutput.append(bytes)
    }

    fun result(status: CommandExecutionStatus, exitCode: Int? = null, message: String? = null): TerminalCommandResult {
        val ui = output.snapshot()
        val model = modelOutput.snapshot()
        return TerminalCommandResult(status, ui.text.trimEnd('\n'), model.text.trimEnd('\n'), exitCode, ui.truncated, message)
    }

    private companion object {
        const val MAX_PENDING_LINE_CHARS = 128 * 1024
        const val MARKER_LOOKBEHIND = 128
    }
}

private data class BufferSnapshot(val text: String, val truncated: Boolean)

private class HeadTailByteBuffer(private val headLimit: Int, private val tailLimit: Int) {
    private val head = ByteArrayOutputStream(headLimit)
    private val tail = ArrayDeque<Byte>()
    var totalBytes: Long = 0
        private set

    fun append(bytes: ByteArray) {
        totalBytes += bytes.size
        var index = 0
        if (head.size() < headLimit) {
            val count = minOf(headLimit - head.size(), bytes.size)
            head.write(bytes, 0, count)
            index = count
        }
        while (index < bytes.size) {
            tail.addLast(bytes[index++])
            if (tail.size > tailLimit) tail.removeFirst()
        }
    }

    fun snapshot(): BufferSnapshot {
        val headBytes = head.toByteArray()
        val truncated = totalBytes > headBytes.size + tail.size
        val bytes = if (!truncated) headBytes + tail.toByteArrayFast() else {
            val omitted = totalBytes - headBytes.size - tail.size
            headBytes + "\n… 已截断 $omitted 字节 …\n".encodeToByteArray() + tail.toByteArrayFast()
        }
        return BufferSnapshot(bytes.toString(Charsets.UTF_8), truncated)
    }
}

private fun ArrayDeque<Byte>.toByteArrayFast(): ByteArray = ByteArray(size).also { target ->
    forEachIndexed { index, byte -> target[index] = byte }
}
