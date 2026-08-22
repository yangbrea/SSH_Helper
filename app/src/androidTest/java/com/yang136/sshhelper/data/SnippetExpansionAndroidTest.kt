package com.yang136.sshhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetExpansionAndroidTest {
    private val profile = HostProfile(
        id = 1,
        name = "测试机",
        hostname = "server.example",
        port = 22,
        username = "tester",
        authType = AuthType.PASSWORD,
    )

    @Test
    fun regexPatternsCompileAndExpandOnAndroid() {
        val snippet = CommandSnippet(
            title = "状态",
            command = "echo ${'$'}{input:前缀} ${'$'}{user}@${'$'}{host}",
            executeImmediately = true,
        )

        val expanded = expandSnippet(snippet, profile, mapOf("前缀" to "ready"))

        assertEquals("echo ready tester@server.example", expanded.text)
        assertNull(expanded.error)
    }
}
