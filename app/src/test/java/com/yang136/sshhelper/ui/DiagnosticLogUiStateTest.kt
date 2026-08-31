package com.yang136.sshhelper.ui

import com.yang136.sshhelper.diagnosticlog.DiagnosticTrace
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceSource
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogUiStateTest {
    private val traces = listOf(
        DiagnosticTrace("1", DiagnosticTraceSource.SSH_CONNECTION, "server.example:22", 1, null, "SHELL", 2, 3, DiagnosticTraceStatus.SUCCEEDED, "连接完成"),
        DiagnosticTrace("2", DiagnosticTraceSource.PORT_SCAN, "router.local", null, null, "TCP", 1, 2, DiagnosticTraceStatus.FAILED, "扫描失败"),
    )

    @Test fun filtersByQuerySourceAndStatusTogether() {
        assertEquals(listOf("2"), DiagnosticLogUiState(traces = traces, query = "router", sourceFilter = DiagnosticTraceSource.PORT_SCAN, statusFilter = DiagnosticTraceStatus.FAILED).visibleTraces.map(DiagnosticTrace::id))
        assertEquals(emptyList<String>(), DiagnosticLogUiState(traces = traces, sourceFilter = DiagnosticTraceSource.SSH_CONNECTION, statusFilter = DiagnosticTraceStatus.FAILED).visibleTraces.map(DiagnosticTrace::id))
    }
}
