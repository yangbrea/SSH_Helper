package com.yang136.sshhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yang136.sshhelper.AppContainer
import com.yang136.sshhelper.diagnosticlog.DiagnosticEvent
import com.yang136.sshhelper.diagnosticlog.DiagnosticLogExporter
import com.yang136.sshhelper.diagnosticlog.DiagnosticLogRepository
import com.yang136.sshhelper.diagnosticlog.DiagnosticTrace
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceSource
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceStatus
import java.io.OutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticLogUiState(
    val traces: List<DiagnosticTrace> = emptyList(),
    val selectedTrace: DiagnosticTrace? = null,
    val events: List<DiagnosticEvent> = emptyList(),
    val query: String = "",
    val sourceFilter: DiagnosticTraceSource? = null,
    val statusFilter: DiagnosticTraceStatus? = null,
    val error: String? = null,
) {
    val visibleTraces: List<DiagnosticTrace> get() {
        val needle = query.trim().lowercase()
        return traces.filter { trace ->
            (sourceFilter == null || trace.source == sourceFilter) &&
                (statusFilter == null || trace.status == statusFilter) &&
                (needle.isBlank() || trace.target.orEmpty().lowercase().contains(needle) ||
                    trace.source.name.lowercase().contains(needle) || trace.summary.orEmpty().lowercase().contains(needle))
        }
    }
}

class DiagnosticLogViewModel(private val repository: DiagnosticLogRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(DiagnosticLogUiState())
    val state: StateFlow<DiagnosticLogUiState> = mutableState.asStateFlow()
    private var eventJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeTraces().collect { traces ->
                mutableState.update { current -> current.copy(traces = traces, selectedTrace = current.selectedTrace?.let { selected -> traces.firstOrNull { it.id == selected.id } }) }
            }
        }
    }

    fun updateQuery(value: String) = mutableState.update { it.copy(query = value) }
    fun selectSource(value: DiagnosticTraceSource?) = mutableState.update { it.copy(sourceFilter = value) }
    fun selectStatus(value: DiagnosticTraceStatus?) = mutableState.update { it.copy(statusFilter = value) }

    fun open(trace: DiagnosticTrace) {
        eventJob?.cancel()
        mutableState.update { it.copy(selectedTrace = trace, events = emptyList(), error = null) }
        eventJob = viewModelScope.launch {
            repository.observeEvents(trace.id).collect { events -> mutableState.update { it.copy(events = events) } }
        }
    }

    fun closeDetail() { eventJob?.cancel(); eventJob = null; mutableState.update { it.copy(selectedTrace = null, events = emptyList()) } }
    fun deleteSelected() = mutableState.value.selectedTrace?.let { trace -> viewModelScope.launch { repository.delete(trace.id); closeDetail() } }
    fun clearAll() = viewModelScope.launch { repository.clear(); closeDetail() }

    suspend fun exportSelected(output: OutputStream) {
        val trace = mutableState.value.selectedTrace ?: error("未选择诊断记录")
        DiagnosticLogExporter.export(trace, repository.events(trace.id), output)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DiagnosticLogViewModel(container.diagnosticLogRepository) as T
        }
    }
}
