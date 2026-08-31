package com.yang136.sshhelper.diagnosticlog

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

class DiagnosticLogRepository(
    private val dao: DiagnosticLogDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val retentionMillis: Long = DEFAULT_DIAGNOSTIC_RETENTION_MILLIS,
    private val maximumTraces: Int = DEFAULT_MAX_DIAGNOSTIC_TRACES,
) : DiagnosticSink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<DiagnosticEventEntity>(capacity = EVENT_QUEUE_CAPACITY)
    private val sequences = ConcurrentHashMap<String, AtomicLong>()
    private val starts = ConcurrentHashMap<String, Long>()
    private val dropped = ConcurrentHashMap<String, AtomicLong>()
    private val ready: Deferred<Unit> = scope.async {
        runCatching {
            dao.markInterrupted(now())
            prune()
        }
    }

    init {
        scope.launch {
            for (first in pending) {
                val batch = ArrayList<DiagnosticEventEntity>(EVENT_BATCH_SIZE)
                batch += first
                while (batch.size < EVENT_BATCH_SIZE) {
                    val next = pending.tryReceive().getOrNull() ?: break
                    batch += next
                }
                // Diagnostics must never take down SSH or scanning when storage is temporarily
                // unavailable. Later events can still be accepted after a failed batch.
                runCatching { dao.insertEvents(batch) }
            }
        }
    }

    fun observeTraces(): Flow<List<DiagnosticTrace>> = dao.observeTraces().map { rows -> rows.map(DiagnosticTraceEntity::toModel) }
    fun observeEvents(traceId: String): Flow<List<DiagnosticEvent>> =
        dao.observeEvents(traceId).map { rows -> rows.map(DiagnosticEventEntity::toModel) }

    suspend fun trace(traceId: String): DiagnosticTrace? = dao.trace(traceId)?.toModel()
    suspend fun events(traceId: String): List<DiagnosticEvent> = dao.events(traceId).map(DiagnosticEventEntity::toModel)
    suspend fun delete(traceId: String) = dao.deleteTrace(traceId)
    suspend fun clear() = dao.clear()

    override suspend fun startTrace(context: DiagnosticTraceContext): String {
        ready.await()
        val id = UUID.randomUUID().toString()
        val startedAt = now()
        return runCatching {
            dao.insertTrace(
                DiagnosticTraceEntity(
                    id = id,
                    source = context.source.name,
                    target = DiagnosticRedactor.redactNullable(context.target),
                    hostId = context.hostId,
                    sessionId = context.sessionId,
                    feature = context.feature,
                    startedAt = startedAt,
                    endedAt = null,
                    status = DiagnosticTraceStatus.RUNNING.name,
                    summary = null,
                ),
            )
            starts[id] = startedAt
            sequences[id] = AtomicLong(0)
            id
        }.getOrDefault("noop")
    }

    override fun record(
        traceId: String,
        stage: DiagnosticEventStage,
        code: String,
        message: String,
        level: DiagnosticEventLevel,
        hop: DiagnosticHop?,
        details: Map<String, String>,
    ) {
        if (traceId == "noop") return
        val timestamp = now()
        val sequence = sequences.getOrPut(traceId) { AtomicLong(0) }.incrementAndGet()
        val entity = DiagnosticEventEntity(
            traceId = traceId,
            sequence = sequence,
            timestamp = timestamp,
            elapsedMillis = (timestamp - (starts[traceId] ?: timestamp)).coerceAtLeast(0),
            level = level.name,
            stage = stage.name,
            hop = hop?.name,
            code = code.take(MAX_CODE_LENGTH),
            message = DiagnosticRedactor.redact(message),
            detailsJson = details.toSafeJson(),
        )
        if (!pending.trySend(entity).isSuccess) {
            dropped.getOrPut(traceId) { AtomicLong(0) }.incrementAndGet()
        }
    }

    override suspend fun finishTrace(traceId: String, status: DiagnosticTraceStatus, summary: String?) {
        if (traceId == "noop") return
        dropped.remove(traceId)?.get()?.takeIf { it > 0 }?.let { count ->
            record(
                traceId,
                DiagnosticEventStage.LIFECYCLE,
                "diagnostic.events_dropped",
                "诊断事件队列已满，丢弃 $count 条低层事件",
                DiagnosticEventLevel.WARNING,
                details = mapOf("count" to count.toString()),
            )
        }
        runCatching { dao.finish(traceId, now(), status.name, DiagnosticRedactor.redactNullable(summary)) }
        starts.remove(traceId)
        sequences.remove(traceId)
    }

    private suspend fun prune() {
        dao.deleteOlderThan(now() - retentionMillis)
        dao.trimTo(maximumTraces)
    }
}

object DiagnosticRedactor {
    private val privateKey = Regex("-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----[\\s\\S]*?-----END(?: [A-Z0-9]+)? PRIVATE KEY-----", RegexOption.IGNORE_CASE)
    private val authorizationHeader = Regex("(?im)^(authorization|proxy-authorization)\\s*:\\s*[^\\r\\n]*")
    private val namedSecret = Regex("(?i)(password|passphrase|private[_ -]?key)\\s*[:=]\\s*([^,;\\r\\n]+)")
    private val control = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    fun redact(value: String): String = value
        .replace(privateKey, "<redacted-private-key>")
        .replace(authorizationHeader) { "${it.groupValues[1]}: <redacted>" }
        .replace(namedSecret) { "${it.groupValues[1]}=<redacted>" }
        .replace(control, "�")
        .take(MAX_DIAGNOSTIC_TEXT_LENGTH)

    fun redactNullable(value: String?): String? = value?.let(::redact)
}

private fun Map<String, String>.toSafeJson(): String = JSONObject().apply {
    entries.sortedBy(Map.Entry<String, String>::key).forEach { (key, value) ->
        put(key.take(MAX_CODE_LENGTH), DiagnosticRedactor.redact(value))
    }
}.toString()

private fun DiagnosticTraceEntity.toModel() = DiagnosticTrace(
    id, DiagnosticTraceSource.valueOf(source), target, hostId, sessionId, feature,
    startedAt, endedAt, DiagnosticTraceStatus.valueOf(status), summary,
)

private fun DiagnosticEventEntity.toModel(): DiagnosticEvent {
    val json = runCatching { JSONObject(detailsJson) }.getOrDefault(JSONObject())
    val details = buildMap { json.keys().forEach { key -> put(key, json.optString(key)) } }
    return DiagnosticEvent(
        id, traceId, sequence, timestamp, elapsedMillis, DiagnosticEventLevel.valueOf(level),
        DiagnosticEventStage.valueOf(stage), hop?.let(DiagnosticHop::valueOf), code, message, details,
    )
}

const val DEFAULT_DIAGNOSTIC_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
const val DEFAULT_MAX_DIAGNOSTIC_TRACES = 500
private const val EVENT_QUEUE_CAPACITY = 1_024
private const val EVENT_BATCH_SIZE = 64
private const val MAX_CODE_LENGTH = 96
const val MAX_DIAGNOSTIC_TEXT_LENGTH = 2_048
