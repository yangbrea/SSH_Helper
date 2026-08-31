package com.yang136.sshhelper.ssh

import com.jcraft.jsch.Logger
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventLevel
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventStage
import com.yang136.sshhelper.diagnosticlog.DiagnosticHop
import com.yang136.sshhelper.diagnosticlog.DiagnosticSink

internal data class ClassifiedJschLog(
    val stage: DiagnosticEventStage,
    val code: String,
    val level: DiagnosticEventLevel,
)

internal fun classifyJschLog(jschLevel: Int, message: String): ClassifiedJschLog? {
    val text = message.lowercase()
    val stageAndCode = when {
        text.startsWith("connecting to") -> DiagnosticEventStage.TCP to "ssh.tcp_connecting"
        text == "connection established" -> DiagnosticEventStage.TCP to "ssh.tcp_connected"
        text.startsWith("remote version string:") -> DiagnosticEventStage.SSH_VERSION to "ssh.remote_version"
        text.startsWith("local version string:") -> DiagnosticEventStage.SSH_VERSION to "ssh.local_version"
        text.startsWith("kex:") || text.contains("kexinit") || text.contains("newkeys") ||
            text.contains("strict kex") || text.startsWith("server proposal:") ||
            text.startsWith("client proposal:") -> DiagnosticEventStage.KEX to "ssh.kex"
        text.contains("hostkey") || text.startsWith("host '") || text.contains("host key") ->
            DiagnosticEventStage.HOST_KEY to "ssh.host_key"
        text.contains("authentication") || text.startsWith("authentications that can continue") ||
            text.startsWith("next authentication method") || text.contains(" auth success") ||
            text.contains(" auth failure") -> DiagnosticEventStage.AUTH to "ssh.authentication"
        text.contains("keepalive") -> DiagnosticEventStage.KEEPALIVE to "ssh.keepalive"
        text.contains("ssh_msg_disconnect") || text.startsWith("disconnecting from") ||
            text.contains("closed by foreign host") || text.contains("leaving main loop") ->
            DiagnosticEventStage.DISCONNECT to "ssh.disconnect"
        else -> null
    } ?: return null
    val level = when (jschLevel) {
        Logger.FATAL, Logger.ERROR -> DiagnosticEventLevel.ERROR
        Logger.WARN -> DiagnosticEventLevel.WARNING
        Logger.DEBUG -> DiagnosticEventLevel.DEBUG
        else -> DiagnosticEventLevel.INFO
    }
    return ClassifiedJschLog(stageAndCode.first, stageAndCode.second, level)
}

internal class JschDiagnosticLogger(
    private val sink: DiagnosticSink,
    private val traceId: String,
    private val hop: DiagnosticHop,
) : Logger {
    override fun isEnabled(level: Int): Boolean = true

    override fun log(level: Int, message: String) {
        val classified = classifyJschLog(level, message) ?: return
        sink.record(
            traceId = traceId,
            stage = classified.stage,
            code = classified.code,
            message = message,
            level = classified.level,
            hop = hop,
        )
    }

    override fun log(level: Int, message: String, cause: Throwable?) {
        log(level, cause?.message?.let { "$message: $it" } ?: message)
    }
}
