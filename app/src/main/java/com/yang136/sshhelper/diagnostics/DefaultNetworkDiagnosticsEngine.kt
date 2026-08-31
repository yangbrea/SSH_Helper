package com.yang136.sshhelper.diagnostics

import com.yang136.sshhelper.discovery.SshBanner
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventLevel
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventStage
import com.yang136.sshhelper.diagnosticlog.DiagnosticSink
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceContext
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceSource
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceStatus
import com.yang136.sshhelper.diagnosticlog.NoOpDiagnosticSink
import java.net.UnknownHostException
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

data class DiagnosticConnection(
    val address: String,
    val durationMillis: Double,
    val banner: SshBanner?,
)

sealed interface DiagnosticConnectResult {
    data class Connected(val value: DiagnosticConnection) : DiagnosticConnectResult
    data class Failed(val kind: DiagnosticFailureKind, val message: String) : DiagnosticConnectResult
}

interface DiagnosticBackend {
    suspend fun availableNetworks(): List<DiagnosticNetwork>
    suspend fun snapshot(networkId: String): NetworkSnapshot?
    suspend fun resolve(networkId: String, hostname: String): List<String>
    suspend fun connect(
        networkId: String,
        address: String,
        port: Int,
        timeoutMillis: Int,
        readSshBanner: Boolean,
    ): DiagnosticConnectResult
    fun cancel()
}

class DefaultNetworkDiagnosticsEngine(
    private val backend: DiagnosticBackend,
    private val diagnostics: DiagnosticSink = NoOpDiagnosticSink,
    private val nanoTime: () -> Long = System::nanoTime,
) : NetworkDiagnosticsEngine {
    override suspend fun availableNetworks(): List<DiagnosticNetwork> = backend.availableNetworks()

    override suspend fun snapshot(networkId: String): NetworkSnapshot? = backend.snapshot(networkId)

    override fun diagnose(request: DiagnosticRequest): Flow<DiagnosticEvent> = flow {
        require(request.sampleCount > 0) { "采样次数必须大于 0" }
        require(request.port in 1..65_535) { "端口必须是 1–65535 之间的整数" }
        val traceId = diagnostics.startTrace(
            DiagnosticTraceContext(
                source = DiagnosticTraceSource.NETWORK_DIAGNOSTIC,
                target = "${request.hostname}:${request.port}",
                feature = "DNS_TCP_DIAGNOSTIC",
            ),
        )
        val snapshot = backend.snapshot(request.networkId)
        if (snapshot == null) {
            val conclusion = DiagnosticConclusion(
                DiagnosticConclusionKind.NO_NETWORK,
                "所选网络不可用",
                "网络可能已断开或切换，请刷新后重试。",
            )
            diagnostics.record(traceId, DiagnosticEventStage.NETWORK, "network.unavailable", conclusion.detail, DiagnosticEventLevel.ERROR)
            diagnostics.finishTrace(traceId, DiagnosticTraceStatus.FAILED, conclusion.title)
            emit(DiagnosticEvent.Failed(conclusion, conclusion.detail))
            return@flow
        }
        emit(DiagnosticEvent.Started(snapshot))
        diagnostics.record(traceId, DiagnosticEventStage.NETWORK, "network.snapshot", "已获取网络接口快照", details = mapOf("network" to snapshot.network.label))

        try {
            val dnsStarted = nanoTime()
            val resolved = withTimeout(request.dnsTimeoutMillis) {
                backend.resolve(request.networkId, request.hostname)
            }.distinct()
            val dnsDuration = elapsedMillis(dnsStarted)
            if (resolved.isEmpty()) throw UnknownHostException(request.hostname)
            diagnostics.record(traceId, DiagnosticEventStage.DNS, "network.dns_resolved", "域名解析完成", details = mapOf("addresses" to resolved.joinToString(","), "durationMillis" to "%.2f".format(dnsDuration)))
            emit(DiagnosticEvent.DnsResolved(resolved, dnsDuration))

            val samples = mutableListOf<DiagnosticSample>()
            var preferredAddress: String? = null
            var banner: SshBanner? = null
            var bannerAttempted = false
            for (index in 1..request.sampleCount) {
                if (index > 1) delay(request.sampleIntervalMillis)
                val deadline = nanoTime() + request.connectTimeoutMillis * NANOS_PER_MILLI
                val candidates = preferredAddress?.let(::listOf) ?: resolved
                var sample: DiagnosticSample? = null
                var lastFailure = DiagnosticConnectResult.Failed(DiagnosticFailureKind.TIMEOUT, "连接超时")
                for (address in candidates) {
                    val remaining = ceil((deadline - nanoTime()).coerceAtLeast(0) / NANOS_PER_MILLI.toDouble()).toInt()
                    if (remaining <= 0) break
                    when (val result = backend.connect(
                        request.networkId,
                        address,
                        request.port,
                        remaining,
                        request.readSshBanner && !bannerAttempted,
                    )) {
                        is DiagnosticConnectResult.Connected -> {
                            preferredAddress = result.value.address
                            if (request.readSshBanner && !bannerAttempted) bannerAttempted = true
                            banner = banner ?: result.value.banner
                            sample = DiagnosticSample.Success(index, result.value.address, result.value.durationMillis)
                            break
                        }
                        is DiagnosticConnectResult.Failed -> lastFailure = result
                    }
                }
                val completed = sample ?: DiagnosticSample.Failure(index, lastFailure.kind, lastFailure.message)
                samples += completed
                when (completed) {
                    is DiagnosticSample.Success -> diagnostics.record(traceId, DiagnosticEventStage.TCP, "network.tcp_sample_success", "TCP 采样 ${completed.index} 成功", details = mapOf("address" to completed.address, "durationMillis" to "%.2f".format(completed.durationMillis)))
                    is DiagnosticSample.Failure -> diagnostics.record(traceId, DiagnosticEventStage.TCP, "network.tcp_sample_failed", completed.message, DiagnosticEventLevel.WARNING, details = mapOf("kind" to completed.kind.name, "sample" to completed.index.toString()))
                }
                emit(DiagnosticEvent.Sampled(completed))
                if (completed is DiagnosticSample.Failure && completed.kind == DiagnosticFailureKind.PERMISSION) break
            }
            val report = DiagnosticReport(
                request = request,
                snapshot = snapshot,
                resolvedAddresses = resolved,
                dnsDurationMillis = dnsDuration,
                samples = samples,
                banner = banner,
                conclusion = classifyDiagnostic(samples, banner, request.readSshBanner),
            )
            diagnostics.record(traceId, DiagnosticEventStage.LIFECYCLE, "network.diagnostic_completed", report.conclusion.title, details = mapOf("failureRatePercent" to report.failureRatePercent.toString()))
            diagnostics.finishTrace(traceId, if (report.conclusion.kind == DiagnosticConclusionKind.HEALTHY) DiagnosticTraceStatus.SUCCEEDED else DiagnosticTraceStatus.FAILED, report.conclusion.title)
            emit(DiagnosticEvent.Completed(report))
        } catch (cancelled: CancellationException) {
            diagnostics.finishTrace(traceId, DiagnosticTraceStatus.CANCELLED, "网络诊断已取消")
            throw cancelled
        } catch (security: SecurityException) {
            val conclusion = DiagnosticConclusion(
                DiagnosticConclusionKind.PERMISSION_DENIED,
                "系统拒绝网络访问",
                "请检查本地网络或附近设备权限，然后重新测试。",
            )
            diagnostics.record(traceId, DiagnosticEventStage.NETWORK, "network.permission_denied", security.message ?: conclusion.detail, DiagnosticEventLevel.ERROR)
            diagnostics.finishTrace(traceId, DiagnosticTraceStatus.FAILED, conclusion.title)
            emit(DiagnosticEvent.Failed(conclusion, security.message ?: conclusion.detail, snapshot))
        } catch (failure: Throwable) {
            val conclusion = DiagnosticConclusion(
                DiagnosticConclusionKind.DNS_FAILURE,
                "域名解析失败",
                "请检查目标地址、DNS 配置和所选网络。IP 地址也可能因网络切换而不可达。",
            )
            diagnostics.record(traceId, DiagnosticEventStage.DNS, "network.dns_failed", failure.message ?: conclusion.detail, DiagnosticEventLevel.ERROR)
            diagnostics.finishTrace(traceId, DiagnosticTraceStatus.FAILED, conclusion.title)
            emit(DiagnosticEvent.Failed(conclusion, failure.message ?: conclusion.detail, snapshot))
        } finally {
            backend.cancel()
        }
    }

    override fun cancel() = backend.cancel()

    private fun elapsedMillis(started: Long): Double = (nanoTime() - started) / NANOS_PER_MILLI.toDouble()

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
