package com.yang136.sshhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    private fun valid() = HostProfile(
        name = "生产服务器",
        hostname = "server.example.com",
        port = 22,
        username = "deploy",
        authType = AuthType.PASSWORD,
    )

    @Test fun validProfileHasNoError() {
        assertNull(valid().validationError())
    }

    @Test fun blankHostIsRejected() {
        assertEquals("请输入服务器地址", valid().copy(hostname = " ").validationError())
    }

    @Test fun portMustBeInTcpRange() {
        assertEquals("端口必须在 1 到 65535 之间", valid().copy(port = 65536).validationError())
    }

    @Test fun defaultSshPortIs22() {
        assertEquals(22, valid().port)
    }
}
