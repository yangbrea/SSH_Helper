package com.yang136.sshhelper.diagnosticlog

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    @Test fun redactsSecretsPrivateKeysAndControls() {
        val input = "password=hunter2 Authorization:BearerToken\n-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----\u0001"
        val result = DiagnosticRedactor.redact(input)
        assertFalse(result.contains("hunter2"))
        assertFalse(result.contains("BearerToken"))
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
}
