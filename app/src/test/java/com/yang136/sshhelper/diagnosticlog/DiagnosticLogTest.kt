package com.yang136.sshhelper.diagnosticlog

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    @Test fun redactsSecretsPrivateKeysAndControls() {
        val input = "password=hunter2\nAuthorization: Bearer token-value\n-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----\u0001"
        val result = DiagnosticRedactor.redact(input)
        assertFalse(result.contains("hunter2"))
        assertFalse(result.contains("token-value"))
        assertFalse(result.contains("\nsecret\n"))
        assertTrue(result.contains("password=<redacted>"))
        assertTrue(result.contains("<redacted-private-key>"))
        assertTrue(result.endsWith("�"))
    }

    @Test fun limitsThirdPartyText() {
        assertEquals(MAX_DIAGNOSTIC_TEXT_LENGTH, DiagnosticRedactor.redact("x".repeat(10_000)).length)
    }

    @Test fun exporterOrdersEventsAndKeepsStructuredFields() {
        val trace = DiagnosticTrace("t1", DiagnosticTraceSource.SSH_CONNECTION, "host:22", 1, "s1", "SHELL", 10, 30, DiagnosticTraceStatus.SUCCEEDED, "ok")
        val events = listOf(
            DiagnosticEvent(traceId = "t1", sequence = 2, timestamp = 20, elapsedMillis = 10, level = DiagnosticEventLevel.INFO, stage = DiagnosticEventStage.AUTH, hop = DiagnosticHop.TARGET, code = "auth.ok", message = "ok"),
            DiagnosticEvent(traceId = "t1", sequence = 1, timestamp = 15, elapsedMillis = 5, level = DiagnosticEventLevel.INFO, stage = DiagnosticEventStage.TCP, hop = DiagnosticHop.DIRECT, code = "tcp.ok", message = "connected", details = mapOf("port" to "22")),
        )
        val output = ByteArrayOutputStream()
        DiagnosticLogExporter.export(trace, events, output)
        val json = JSONObject(output.toString(Charsets.UTF_8.name()))
        assertEquals("diagnostic-trace", json.getString("kind"))
        assertEquals(1L, json.getJSONArray("events").getJSONObject(0).getLong("sequence"))
        assertEquals("22", json.getJSONArray("events").getJSONObject(0).getJSONObject("details").getString("port"))
    }

    @Test fun storageFailureFallsBackWithoutBreakingProducer() = runBlocking {
        val repository = DiagnosticLogRepository(FailingInsertDao())
        val id = repository.startTrace(DiagnosticTraceContext(DiagnosticTraceSource.SSH_CONNECTION, "host:22"))
        assertEquals("noop", id)
        repository.record(id, DiagnosticEventStage.TCP, "tcp.start", "start")
        repository.finishTrace(id, DiagnosticTraceStatus.FAILED, "failed")
    }
}

private class FailingInsertDao : DiagnosticLogDao {
    override fun observeTraces(): Flow<List<DiagnosticTraceEntity>> = flowOf(emptyList())
    override fun observeEvents(traceId: String): Flow<List<DiagnosticEventEntity>> = flowOf(emptyList())
    override suspend fun trace(traceId: String): DiagnosticTraceEntity? = null
    override suspend fun events(traceId: String): List<DiagnosticEventEntity> = emptyList()
    override suspend fun insertTrace(trace: DiagnosticTraceEntity) { error("disk unavailable") }
    override suspend fun insertEvents(events: List<DiagnosticEventEntity>) = Unit
    override suspend fun finish(traceId: String, endedAt: Long, status: String, summary: String?) = Unit
    override suspend fun markInterrupted(now: Long) = Unit
    override suspend fun deleteOlderThan(cutoff: Long) = Unit
    override suspend fun trimTo(maximum: Int) = Unit
    override suspend fun deleteTrace(traceId: String) = Unit
    override suspend fun clear() = Unit
}
