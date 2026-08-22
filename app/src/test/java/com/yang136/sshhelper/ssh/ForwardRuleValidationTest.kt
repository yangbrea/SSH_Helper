package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.ForwardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForwardRuleValidationTest {
    private fun rule(
        type: ForwardType = ForwardType.LOCAL,
        name: String = "测试",
        bindAddress: String = "127.0.0.1",
        listenPort: Int = 0,
        targetHost: String? = "db.internal",
        targetPort: Int? = 5432,
        autoStart: Boolean = false,
    ) = PortForwardRule(
        id = 0, hostId = 1, name = name, type = type, bindAddress = bindAddress,
        listenPort = listenPort, targetHost = targetHost, targetPort = targetPort, autoStart = autoStart,
    )

    @Test
    fun validLocalWithAutoPortPasses() {
        assertNull(validateForwardRule(rule()))
    }

    @Test
    fun localFixedPortMustBeAbove1023() {
        assertEquals("监听端口必须在 1024–65535 之间（0 表示自动分配）", validateForwardRule(rule(listenPort = 1023)))
        assertEquals("监听端口必须在 1024–65535 之间（0 表示自动分配）", validateForwardRule(rule(listenPort = 65536)))
    }

    @Test
    fun dynamicSocksOnlyLoopback() {
        assertNull(validateForwardRule(rule(type = ForwardType.DYNAMIC)))
        assertEquals(
            "动态代理只允许监听回环地址，不提供无认证的局域网公开代理",
            validateForwardRule(rule(type = ForwardType.DYNAMIC, bindAddress = "0.0.0.0")),
        )
    }

    @Test
    fun remoteListenPortInFullRange() {
        assertNull(validateForwardRule(rule(type = ForwardType.REMOTE, listenPort = 2222)))
        assertEquals("服务器监听端口必须在 1–65535 之间", validateForwardRule(rule(type = ForwardType.REMOTE, listenPort = 0)))
    }

    @Test
    fun targetFieldsRequiredExceptDynamic() {
        assertEquals("请输入目标主机", validateForwardRule(rule(targetHost = null)))
        assertEquals("请输入目标主机", validateForwardRule(rule(targetHost = "  ")))
        assertEquals("目标端口必须在 1–65535 之间", validateForwardRule(rule(targetPort = null)))
        assertEquals("目标端口必须在 1–65535 之间", validateForwardRule(rule(targetPort = 0)))
        assertEquals("目标端口必须在 1–65535 之间", validateForwardRule(rule(targetPort = 65536)))
    }

    @Test
    fun nameRequired() {
        assertEquals("请输入规则名称", validateForwardRule(rule(name = "")))
        assertEquals("请输入规则名称", validateForwardRule(rule(name = "   ")))
    }
}
