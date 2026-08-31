package com.yang136.sshhelper.scanner

import com.yang136.sshhelper.diagnosticlog.DiagnosticEventLevel
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventStage
import com.yang136.sshhelper.diagnosticlog.DiagnosticSink
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceContext
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceSource
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceStatus
import com.yang136.sshhelper.diagnosticlog.NoOpDiagnosticSink
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class DefaultPortScanner(
    private val backend: PortScanBackend,
    private val diagnostics: DiagnosticSink = NoOpDiagnosticSink,
) : PortScanner {
    private val activeJob = AtomicReference<Job?>(null)

    override suspend fun availableNetworks(): List<PortScanNetwork> = backend.availableNetworks()
    override suspend fun resolve(networkId: String, target: String): List<String> = backend.resolve(networkId, target)

    override fun scan(request: PortScanRequest): Flow<PortScanEvent> = channelFlow {
        require(request.ports.isNotEmpty()) { "请选择至少一个端口" }
        require(request.ports.size <= MAX_PORT_SCAN_PORTS) { "一次最多扫描 65535 个端口" }
        val job = currentCoroutineContext()[Job] ?: error("扫描缺少协程任务")
        activeJob.getAndSet(job)?.cancel()
        val traceId = diagnostics.startTrace(
            DiagnosticTraceContext(
                source = DiagnosticTraceSource.PORT_SCAN,
                target = request.target,
                feature = "TCP_CONNECT_SCAN",
            ),
        )
        try {
            val addresses = backend.resolve(request.networkId, request.target)
            require(addresses.isNotEmpty()) { "目标没有可用的 IP 地址" }
            val selected = request.selectedAddress?.takeIf(addresses::contains) ?: addresses.first()
            send(PortScanEvent.Resolved(addresses, selected))
            send(PortScanEvent.Started(request.ports.size))
            diagnostics.record(traceId, DiagnosticEventStage.DNS, "scan.target_resolved", "目标解析完成", details = mapOf("address" to selected, "count" to addresses.size.toString()))

            val completed = AtomicInteger(0)
            val open = AtomicInteger(0)
            val refused = AtomicInteger(0)
            val timeout = AtomicInteger(0)
            val unreachable = AtomicInteger(0)
            val errors = AtomicInteger(0)
            val targets = Channel<Int>(request.concurrency.coerceIn(1, 256) * 2)
            val workers = List(request.concurrency.coerceIn(1, 256)) {
                launch(Dispatchers.IO) {
                    for (port in targets) {
                        val result = backend.probe(
                            request.networkId,
                            request.target,
                            selected,
                            port,
                            request.connectTimeoutMillis.coerceIn(100, 30_000),
                            request.bannerTimeoutMillis.coerceIn(100, 10_000),
                        )
                        when (result.state) {
                            PortState.OPEN -> {
                                open.incrementAndGet()
                                diagnostics.record(
                                    traceId,
                                    DiagnosticEventStage.SCAN,
                                    "scan.port_open",
                                    "TCP $port 开放",
                                    details = mapOf(
                                        "port" to port.toString(),
                                        "service" to (result.fingerprint?.service ?: "UNKNOWN"),
                                        "confidence" to (result.fingerprint?.confidence?.name ?: "LOW"),
                                    ),
                                )
                            }
                            PortState.REFUSED -> refused.incrementAndGet()
                            PortState.TIMEOUT_FILTERED -> timeout.incrementAndGet()
                            PortState.UNREACHABLE -> unreachable.incrementAndGet()
                            PortState.ERROR -> errors.incrementAndGet()
                        }
                        // Closed/filtered results are deliberately aggregated. Emitting 65k
                        // objects and Compose state updates makes a full scan needlessly costly.
                        if (result.state == PortState.OPEN) send(PortScanEvent.Result(result))
                        val progress = completed.incrementAndGet()
                        if (progress == request.ports.size || progress % PROGRESS_GRANULARITY == 0) {
                            send(PortScanEvent.Progress(progress, request.ports.size))
                        }
                    }
                }
            }
            request.ports.sorted().forEach { targets.send(it) }
            targets.close()
            workers.joinAll()
            val summary = PortScanSummary(selected, request.ports.size, open.get(), refused.get(), timeout.get(), unreachable.get(), errors.get())
            diagnostics.record(traceId, DiagnosticEventStage.SCAN, "scan.completed", "端口扫描完成", details = summary.toDetails())
            diagnostics.finishTrace(traceId, DiagnosticTraceStatus.SUCCEEDED, "发现 ${summary.open} 个开放端口")
            send(PortScanEvent.Completed(summary, traceId))
        } catch (cancelled: CancellationException) {
            diagnostics.record(traceId, DiagnosticEventStage.SCAN, "scan.cancelled", "端口扫描已取消", DiagnosticEventLevel.WARNING)
            diagnostics.finishTrace(traceId, DiagnosticTraceStatus.CANCELLED, "扫描已取消")
            throw cancelled
        } catch (failure: Throwable) {
            diagnostics.record(traceId, DiagnosticEventStage.SCAN, "scan.failed", failure.message ?: "端口扫描失败", DiagnosticEventLevel.ERROR)
            diagnostics.finishTrace(traceId, DiagnosticTraceStatus.FAILED, failure.message ?: "端口扫描失败")
            throw failure
        } finally {
            backend.cancel()
            activeJob.compareAndSet(job, null)
        }
    }

    override fun cancel() {
        activeJob.getAndSet(null)?.cancel()
        backend.cancel()
    }
}

private const val PROGRESS_GRANULARITY = 32

private fun PortScanSummary.toDetails() = mapOf(
    "address" to address,
    "total" to total.toString(),
    "open" to open.toString(),
    "refused" to refused.toString(),
    "timeoutFiltered" to timeoutFiltered.toString(),
    "unreachable" to unreachable.toString(),
    "errors" to errors.toString(),
)
