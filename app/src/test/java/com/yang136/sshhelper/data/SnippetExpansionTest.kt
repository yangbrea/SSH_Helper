package com.yang136.sshhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetExpansionTest {
    private val profile = HostProfile(
        id = 7,
        name = "生产机",
        hostname = "server.example",
        port = 2222,
        username = "deploy",
        authType = AuthType.PRIVATE_KEY,
    )

    @Test
    fun expandsBuiltInsAndRequestedInputs() {
        val snippet = CommandSnippet(title = "日志", command = "ssh \${user}@\${host} -p \${port}; tail -n \${input:行数} app.log")
        val missing = expandSnippet(snippet, profile)
        assertEquals(listOf("行数"), missing.missingInputs)

        val expanded = expandSnippet(snippet, profile, mapOf("行数" to "50"))
        assertEquals("ssh deploy@server.example -p 2222; tail -n 50 app.log", expanded.text)
        assertNull(expanded.error)
    }

    @Test
    fun expandsImmediateCommandWithoutCustomInputs() {
        val expanded = expandSnippet(
            CommandSnippet(title = "状态", command = "echo ${'$'}{profile}; uptime", executeImmediately = true),
            profile,
        )

        assertEquals("echo 生产机; uptime", expanded.text)
        assertNull(expanded.error)
        assertTrue(expanded.missingInputs.isEmpty())
    }

    @Test
    fun rejectsUnknownVariablesAndImmediateMultiline() {
        assertTrue(expandSnippet(CommandSnippet(title = "x", command = "echo \${unknown}"), profile).error != null)
        assertTrue(expandSnippet(CommandSnippet(title = "x", command = "one\ntwo", executeImmediately = true), profile).error != null)
    }

    @Test
    fun entityDisablesImmediateExecutionForMultilineCommand() {
        val entity = CommandSnippet(title = "x", command = "one\ntwo", executeImmediately = true).toEntity()
        assertEquals(false, entity.executeImmediately)
    }
}
