package com.yang136.sshhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyValidationTest {
    private fun host(
        proxyType: ProxyType? = null,
        proxyHost: String? = null,
        proxyPort: Int? = null,
    ) = HostProfile(
        id = 1, name = "测试", hostname = "10.0.0.1", port = 22, username = "user",
        authType = AuthType.PASSWORD, proxyType = proxyType, proxyHost = proxyHost, proxyPort = proxyPort,
    )

    @Test
    fun directConnectionIsAlwaysValid() {
        assertNull(validateProxy(host()))
        assertNull(validateProxy(host(proxyType = null, proxyHost = "ignored", proxyPort = 8080)))
    }

    @Test
    fun missingProxyHostIsRejected() {
        assertEquals("请输入代理服务器地址", validateProxy(host(ProxyType.HTTP, proxyHost = null, proxyPort = 8080)))
        assertEquals("请输入代理服务器地址", validateProxy(host(ProxyType.SOCKS5, proxyHost = "  ", proxyPort = 1080)))
    }

    @Test
    fun invalidProxyPortIsRejected() {
        assertEquals("代理端口必须在 1–65535 之间", validateProxy(host(ProxyType.HTTP, "proxy.test", null)))
        assertEquals("代理端口必须在 1–65535 之间", validateProxy(host(ProxyType.HTTP, "proxy.test", 0)))
        assertEquals("代理端口必须在 1–65535 之间", validateProxy(host(ProxyType.HTTP, "proxy.test", 65536)))
    }

    @Test
    fun validProxyPasses() {
        assertNull(validateProxy(host(ProxyType.HTTP, "proxy.test", 3128)))
        assertNull(validateProxy(host(ProxyType.SOCKS5, "10.0.0.2", 1080)))
    }
}
