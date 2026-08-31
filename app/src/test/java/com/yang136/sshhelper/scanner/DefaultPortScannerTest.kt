package com.yang136.sshhelper.scanner

import com.yang136.sshhelper.diagnosticlog.DiagnosticEventLevel
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventStage
import com.yang136.sshhelper.diagnosticlog.DiagnosticHop
import com.yang136.sshhelper.diagnosticlog.DiagnosticSink
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceContext
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPortScannerTest {
    @Test fun streamsResultsSummarizesAndLogsOnlyOpenPorts() = runTest {
        val backend = FakeBackend(
            mapOf(
                22 to PortProbeResult("192.0.2.4", 22, PortState.OPEN, 4.0, fallbackFingerprint(22)),
                23 to PortProbeResult("192.0.2.4", 23, PortState.REFUSED),
                24 to PortProbeResult("192.0.2.4", 24, PortState.TIMEOUT_FILTERED),
            ),
        )
        val sink = RecordingSink()
        val events = DefaultPortScanner(backend, sink).scan(
            PortScanRequest("n1", "host.test", setOf(22, 23, 24), concurrency = 2),
        ).toList()
        val completed = events.filterIsInstance<PortScanEvent.Completed>().single()
        assertEquals(1, completed.summary.open)
        assertEquals(1, completed.summary.refused)
        assertEquals(1, completed.summary.timeoutFiltered)
        assertEquals(listOf("scan.port_open"), sink.codes.filter { it == "scan.port_open" })
        assertEquals(DiagnosticTraceStatus.SUCCEEDED, sink.status)
        assertTrue(backend.cancelled)
    }
}

private class FakeBackend(private val results: Map<Int, PortProbeResult>) : PortScanBackend {
    var cancelled = false
    override suspend fun availableNetworks() = listOf(PortScanNetwork("n1", "Wi-Fi", true))
    override suspend fun resolve(networkId: String, target: String) = listOf("192.0.2.4")
    override suspend fun probe(networkId: String, targetHost: String, address: String, port: Int, connectTimeoutMillis: Int, bannerTimeoutMillis: Int) =
        results.getValue(port)
    override fun cancel() { cancelled = true }
}

private class RecordingSink : DiagnosticSink {
    val codes = mutableListOf<String>()
    var status: DiagnosticTraceStatus? = null
    override suspend fun startTrace(context: DiagnosticTraceContext) = "trace"
    override fun record(traceId: String, stage: DiagnosticEventStage, code: String, message: String, level: DiagnosticEventLevel, hop: DiagnosticHop?, details: Map<String, String>) { codes += code }
    override suspend fun finishTrace(traceId: String, status: DiagnosticTraceStatus, summary: String?) { this.status = status }
}
