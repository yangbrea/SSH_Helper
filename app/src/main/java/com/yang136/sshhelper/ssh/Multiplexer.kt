package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.MultiplexerType

data class RemoteMultiplexerSession(
    val name: String,
    val attachedClients: Int,
) {
    val attached: Boolean get() = attachedClients > 0
}

sealed interface MultiplexerAvailability {
    data object Available : MultiplexerAvailability
    data class Missing(val type: MultiplexerType) : MultiplexerAvailability
    data class Error(val message: String) : MultiplexerAvailability
}

data class RemoteCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
    val output: String get() = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n")
}

sealed interface TerminalTarget {
    data object PlainShell : TerminalTarget
    data class Persistent(
        val type: MultiplexerType,
        val name: String,
        val create: Boolean,
    ) : TerminalTarget
}

interface Multiplexer {
    val type: MultiplexerType
    fun availabilityCommand(): String
    fun listSessionsCommand(): String
    fun parseSessions(output: String): List<RemoteMultiplexerSession>
    fun createCommand(sessionName: String): String
    fun attachCommand(sessionName: String): String
    fun deleteCommand(sessionName: String): String
}

object MultiplexerRegistry {
    fun forType(type: MultiplexerType): Multiplexer? = when (type) {
        MultiplexerType.NONE -> null
        MultiplexerType.TMUX -> TmuxMultiplexer
        MultiplexerType.ZMX -> ZmxMultiplexer
    }
}

object TmuxMultiplexer : Multiplexer {
    override val type = MultiplexerType.TMUX

    override fun availabilityCommand(): String = "command -v tmux >/dev/null 2>&1"

    override fun listSessionsCommand(): String =
        "if ! command -v tmux >/dev/null 2>&1; then printf 'tmux executable not found\\n' >&2; exit 127; fi; " +
            "output=\$(LC_ALL=C tmux list-sessions -F '#{session_name}\\t#{session_attached}' 2>&1); status=\$?; " +
            "if [ \"\$status\" -eq 0 ]; then [ -z \"\$output\" ] || printf '%s\\n' \"\$output\"; exit 0; fi; " +
            "if [ \"\$status\" -eq 1 ] && printf '%s' \"\$output\" | grep -Eq " +
            "'no server running|failed to connect to server|error connecting to .*\\(No such file or directory\\)'; " +
            "then exit 0; fi; " +
            "printf '%s\\n' \"\$output\" >&2; exit \"\$status\""

    override fun parseSessions(output: String): List<RemoteMultiplexerSession> = output
        .lineSequence()
        .mapNotNull { line ->
            // tmux -F '#{session_name}\t#{session_attached}' keeps the backslash-t as two
            // characters on some builds/shells instead of emitting a real tab. Accept both
            // so the real session name is not parsed as "shh-1\t0".
            val fields = line.trimEnd('\r').split(Regex("\\\\t|\t"))
            val name = fields.firstOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            RemoteMultiplexerSession(name, fields.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
        }
        .toList()

    override fun createCommand(sessionName: String): String {
        requireGeneratedSessionName(sessionName)
        // Enable tmux mouse support for app-managed sessions so mobile touch scrolling
        // can be delivered as wheel events; Ghostty only forwards wheels when the remote
        // application has mouse reporting active.
        return "exec tmux new-session -s ${shellQuote(sessionName)} \\; set -g mouse on"
    }

    override fun attachCommand(sessionName: String): String {
        val name = shellQuote(sessionName)
        val exact = shellQuote("=$sessionName")
        return "if tmux has-session -t $exact 2>/dev/null; then " +
            "exec tmux set -g mouse on \\; attach-session -t $exact; " +
            "else printf 'tmux session %s is no longer available\\n' $name >&2; exit 44; fi"
    }

    override fun deleteCommand(sessionName: String): String =
        "tmux kill-session -t ${shellQuote("=$sessionName")}"
}

/** Kept behind the rollout gate until tmux has completed device validation. */
object ZmxMultiplexer : Multiplexer {
    override val type = MultiplexerType.ZMX

    private val pathExport: String
        get() = "export PATH=\"\$HOME/.local/bin:\$PATH\"; "

    override fun availabilityCommand(): String =
        "${pathExport}command -v zmx >/dev/null 2>&1"

    override fun listSessionsCommand(): String =
        "${pathExport}if ! command -v zmx >/dev/null 2>&1; then printf 'zmx executable not found\\n' >&2; exit 127; " +
            "else zmx list 2>/dev/null; fi"

    override fun parseSessions(output: String): List<RemoteMultiplexerSession> = output
        .lineSequence()
        .mapNotNull { line ->
            val fields = line.trim().split('\t').mapNotNull { field ->
                val split = field.indexOf('=')
                if (split <= 0) null else field.substring(0, split) to field.substring(split + 1)
            }.toMap()
            val name = fields["name"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            RemoteMultiplexerSession(name, fields["clients"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
        }
        .toList()

    override fun createCommand(sessionName: String): String {
        requireGeneratedSessionName(sessionName)
        return zmxAttach(sessionName)
    }

    override fun attachCommand(sessionName: String): String {
        val name = shellQuote(sessionName)
        return "${pathExport}if zmx list --short 2>/dev/null | grep -Fqx -- $name; then ${zmxAttach(sessionName)}; " +
            "else printf 'zmx session %s is no longer available\\n' $name >&2; exit 44; fi"
    }

    private fun zmxAttach(sessionName: String): String =
        "${pathExport}unset ZMX_SESSION; export ZMX_NO_DETACH_KEY=1; exec zmx attach ${shellQuote(sessionName)}"

    override fun deleteCommand(sessionName: String): String =
        "${pathExport}zmx kill ${shellQuote(sessionName)}"
}

private val generatedSessionName = Regex("^shh-[1-9][0-9]*$")

internal fun requireGeneratedSessionName(name: String): String {
    require(generatedSessionName.matches(name)) { "Invalid SH-Helper multiplexer session name" }
    return name
}

internal fun nextGeneratedSessionName(
    remoteSessions: Collection<RemoteMultiplexerSession>,
    localSessionNames: Collection<String>,
): String {
    val used = remoteSessions.mapTo(mutableSetOf(), RemoteMultiplexerSession::name)
    used += localSessionNames
    var index = 1
    while ("shh-$index" in used) index++
    return "shh-$index"
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
