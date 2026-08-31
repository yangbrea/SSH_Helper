package com.yang136.sshhelper.diagnosticlog

import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

object DiagnosticLogExporter {
    fun export(trace: DiagnosticTrace, events: List<DiagnosticEvent>, output: OutputStream) {
        val root = JSONObject()
            .put("app", "SSH Helper")
            .put("kind", "diagnostic-trace")
            .put("version", 1)
            .put("trace", trace.toJson())
            .put("events", JSONArray().apply { events.sortedBy(DiagnosticEvent::sequence).forEach { put(it.toJson()) } })
        output.writer(Charsets.UTF_8).use { it.write(root.toString(2)) }
    }
}

private fun DiagnosticTrace.toJson() = JSONObject()
    .put("id", id)
    .put("source", source.name)
    .put("target", target ?: JSONObject.NULL)
    .put("hostId", hostId ?: JSONObject.NULL)
    .put("sessionId", sessionId ?: JSONObject.NULL)
    .put("feature", feature ?: JSONObject.NULL)
    .put("startedAt", startedAt)
    .put("endedAt", endedAt ?: JSONObject.NULL)
    .put("status", status.name)
    .put("summary", summary ?: JSONObject.NULL)

private fun DiagnosticEvent.toJson() = JSONObject()
    .put("sequence", sequence)
    .put("timestamp", timestamp)
    .put("elapsedMillis", elapsedMillis)
    .put("level", level.name)
    .put("stage", stage.name)
    .put("hop", hop?.name ?: JSONObject.NULL)
    .put("code", code)
    .put("message", message)
    .put("details", JSONObject(details))
