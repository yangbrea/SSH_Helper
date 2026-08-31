package com.yang136.sshhelper.diagnosticlog

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSchemaContractTest {
    @Test fun roomV7SchemaContainsTraceAndEventTables() {
        val schema = File("schemas/com.yang136.sshhelper.data.AppDatabase/7.json")
        assertTrue("Room v7 schema must be exported", schema.isFile)
        val text = schema.readText()
        assertTrue(text.contains("diagnostic_traces"))
        assertTrue(text.contains("diagnostic_events"))
        assertTrue(text.contains("index_diagnostic_events_traceId_sequence"))
    }

    @Test fun databaseRegistersExplicitV6ToV7Migration() {
        val source = File("src/main/java/com/yang136/sshhelper/data/AppDatabase.kt").readText()
        assertTrue(source.contains("version = 7"))
        assertTrue(source.contains("MIGRATION_6_7"))
        assertTrue(source.contains("FOREIGN KEY(traceId) REFERENCES diagnostic_traces(id)"))
    }

    @Test fun retentionDefaultsMatchProductPolicy() {
        assertTrue(DEFAULT_DIAGNOSTIC_RETENTION_MILLIS == 30L * 24 * 60 * 60 * 1_000)
        assertTrue(DEFAULT_MAX_DIAGNOSTIC_TRACES == 500)
    }
}
