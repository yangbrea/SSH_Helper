package com.yang136.sshhelper.diagnosticlog

enum class DiagnosticTraceSource { SSH_CONNECTION, NETWORK_DIAGNOSTIC, PORT_SCAN }
enum class DiagnosticTraceStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED, ABORTED }
enum class DiagnosticEventLevel { DEBUG, INFO, WARNING, ERROR }
enum class DiagnosticHop { DIRECT, JUMP, TARGET }
enum class DiagnosticEventStage {
    LIFECYCLE, NETWORK, DNS, TCP, PROXY, SSH_VERSION, KEX, HOST_KEY, AUTH,
    CHANNEL, KEEPALIVE, DISCONNECT, SCAN,
}

data class DiagnosticTrace(
    val id: String,
    val source: DiagnosticTraceSource,
    val target: String?,
    val hostId: Long?,
    val sessionId: String?,
    val feature: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val status: DiagnosticTraceStatus,
    val summary: String?,
)

data class DiagnosticEvent(
    val id: Long = 0,
    val traceId: String,
    val sequence: Long,
    val timestamp: Long,
    val elapsedMillis: Long,
    val level: DiagnosticEventLevel,
    val stage: DiagnosticEventStage,
    val hop: DiagnosticHop?,
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

data class DiagnosticTraceContext(
    val source: DiagnosticTraceSource,
    val target: String? = null,
    val hostId: Long? = null,
    val sessionId: String? = null,
    val feature: String? = null,
)

interface DiagnosticSink {
    suspend fun startTrace(context: DiagnosticTraceContext): String

    fun record(
        traceId: String,
        stage: DiagnosticEventStage,
        code: String,
        message: String,
        level: DiagnosticEventLevel = DiagnosticEventLevel.INFO,
        hop: DiagnosticHop? = null,
        details: Map<String, String> = emptyMap(),
    )

    suspend fun finishTrace(
        traceId: String,
        status: DiagnosticTraceStatus,
        summary: String? = null,
    )
}

object NoOpDiagnosticSink : DiagnosticSink {
    override suspend fun startTrace(context: DiagnosticTraceContext): String = "noop"
    override fun record(
        traceId: String,
        stage: DiagnosticEventStage,
        code: String,
        message: String,
        level: DiagnosticEventLevel,
        hop: DiagnosticHop?,
        details: Map<String, String>,
    ) = Unit
    override suspend fun finishTrace(traceId: String, status: DiagnosticTraceStatus, summary: String?) = Unit
}
