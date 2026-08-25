package com.yang136.sshhelper.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextTest {

    @Test
    fun stripAnsiRemovesCsiAndOscSequences() {
        val input = "\u001B[32mgreen\u001B[0m \u001B]0;title\u0007osc \u001B[2K\r\nnext"
        val result = AiContext.stripAnsi(input)
        assertEquals("green osc \r\nnext", result)
    }

    @Test
    fun stripAnsiRemovesRawControlChars() {
        val input = "a\u0000b\u0001c\u007Fd"
        assertEquals("abcd", AiContext.stripAnsi(input))
    }

    @Test
    fun recentTerminalTextTakesTailAndStripsAnsi() {
        val head = "old-line\n".repeat(200)
        val tail = "\u001B[31merror here\u001B[0m\n"
        val result = AiContext.recentTerminalText((head + tail).encodeToByteArray(), maxBytes = 64)
        assertTrue("应包含尾部内容，实际：$result", result.contains("error here"))
        assertTrue("应剥离 ANSI 转义", !result.contains("\u001B"))
    }

    @Test
    fun recentTerminalTextCutsPartialUtf8AtHead() {
        // A multi-byte character split by the byte cut must not produce garbage.
        val prefix = "前".repeat(100).encodeToByteArray()
        val suffix = "\n结尾".encodeToByteArray()
        val result = AiContext.recentTerminalText(prefix + suffix, maxBytes = 128)
        assertEquals("结尾", result)
    }

}
