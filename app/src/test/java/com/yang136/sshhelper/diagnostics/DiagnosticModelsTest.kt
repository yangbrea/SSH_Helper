package com.yang136.sshhelper.diagnostics

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.ProxyType
import com.yang136.sshhelper.discovery.SshBanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticModelsTest {
    private val target = host(1, "Target", "target.example", 22)

    @Test
    fun validatesAndNormalizesTargets() {
        assertNull(validateDiagnosticTarget("example.com", "22"))
        assertNull(validateDiagnosticTarget("[2001:db8::1]", "65535"))
        assertEquals("2001:db8::1", normalizeDiagnosticHostname(" [2001:db8::1] "))
        assertTrue(validateDiagnosticTarget("", "22")!!.contains("地址"))
        assertTrue(validateDiagnosticTarget("host", "0")!!.contains("65535"))
        assertTrue(validateDiagnosticTarget("host", "abc")!!.contains("整数"))
    }

    @Test
    fun resolvesDirectAndProxyFirstHop() {
        val direct = diagnosticEndpointFor(target, listOf(target))
        assertEquals(DiagnosticEndpointKind.SSH, direct.kind)
        assertEquals("target.example", direct.hostname)
        assertEquals(22, direct.port)
        assertNull(direct.limitation)

        val proxied = target.copy(
            proxyType = ProxyType.SOCKS5,
            proxyHost = "proxy.example",
            proxyPort = 1080,
        )
        val proxy = diagnosticEndpointFor(proxied, listOf(proxied))
        assertEquals(DiagnosticEndpointKind.PROXY, proxy.kind)
        assertEquals("proxy.example", proxy.hostname)
        assertEquals(1080, proxy.port)
        assertTrue(proxy.routeSummary.contains("SOCKS5"))
        assertTrue(proxy.limitation!!.contains("第一个连接节点"))
    }

    @Test
    fun resolvesJumpAndJumpProxyFirstHop() {
        val jump = host(2, "Jump", "jump.example", 2222)
        val viaJump = target.copy(jumpHostId = jump.id)
        val endpoint = diagnosticEndpointFor(viaJump, listOf(viaJump, jump))
        assertEquals("jump.example", endpoint.hostname)
        assertEquals(2222, endpoint.port)
        assertEquals(DiagnosticEndpointKind.SSH, endpoint.kind)
        assertTrue(endpoint.routeSummary.contains("跳板机"))

        val proxiedJump = jump.copy(proxyType = ProxyType.HTTP, proxyHost = "edge.example", proxyPort = 8080)
        val viaProxy = diagnosticEndpointFor(viaJump, listOf(viaJump, proxiedJump))
        assertEquals("edge.example", viaProxy.hostname)
        assertEquals(8080, viaProxy.port)
        assertEquals(DiagnosticEndpointKind.PROXY, viaProxy.kind)
        assertTrue(viaProxy.routeSummary.contains("HTTP 代理"))
    }

    @Test
    fun aggregatesSamplesAndClassifiesResults() {
        val samples = listOf(
            DiagnosticSample.Success(1, "192.0.2.1", 10.0),
            DiagnosticSample.Success(2, "192.0.2.1", 20.0),
            DiagnosticSample.Failure(3, DiagnosticFailureKind.TIMEOUT, "timeout"),
        )
        val report = DiagnosticReport(
            request = DiagnosticRequest("n1", "host", 22, sampleCount = 3),
            snapshot = snapshot(),
            resolvedAddresses = listOf("192.0.2.1"),
            dnsDurationMillis = 2.0,
            samples = samples,
            banner = null,
            conclusion = classifyDiagnostic(samples, null, true),
        )
        assertEquals(10.0, report.minimumMillis!!, 0.001)
        assertEquals(15.0, report.averageMillis!!, 0.001)
        assertEquals(20.0, report.maximumMillis!!, 0.001)
        assertEquals(33, report.failureRatePercent)
        assertEquals(DiagnosticConclusionKind.PARTIAL_FAILURE, report.conclusion.kind)

        val allSuccess = (1..5).map { DiagnosticSample.Success(it, "192.0.2.1", 5.0) }
        assertEquals(DiagnosticConclusionKind.NON_SSH_SERVICE, classifyDiagnostic(allSuccess, null, true).kind)
        assertEquals(
            DiagnosticConclusionKind.HEALTHY,
            classifyDiagnostic(allSuccess, SshBanner("SSH-2.0-Test", "2.0", "Test"), true).kind,
        )
    }

    private fun host(id: Long, name: String, hostname: String, port: Int) = HostProfile(
        id = id,
        name = name,
        hostname = hostname,
        port = port,
        username = "user",
        authType = AuthType.PASSWORD,
    )

    private fun snapshot() = NetworkSnapshot(
        network = DiagnosticNetwork("n1", "Wi-Fi", setOf(DiagnosticTransport.WIFI), true),
        interfaceName = "wlan0",
        addresses = listOf("192.0.2.2/24"),
        gateways = listOf("192.0.2.1"),
        dnsServers = listOf("192.0.2.1"),
        mtu = 1500,
        privateDnsActive = false,
        privateDnsServerName = null,
        httpProxy = null,
        metered = false,
        hasInternetCapability = true,
        validated = true,
        captivePortal = false,
    )
}
