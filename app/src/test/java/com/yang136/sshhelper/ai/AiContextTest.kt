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

    @Test
    fun extractCommandPrefersFencedCodeBlock() {
        val answer = "可以这样查看磁盘占用：\n\n```bash\ndu -sh /* | sort -rh | head\n```\n\n按回车执行。"
        assertEquals("du -sh /* | sort -rh | head", AiContext.extractCommand(answer))
    }

    @Test
    fun extractCommandAcceptsSingleBareLine() {
        assertEquals("df -h", AiContext.extractCommand("df -h"))
    }

    @Test
    fun extractCommandReturnsNullForProseWithoutCodeBlock() {
        val prose = "磁盘空间不足通常是日志或缓存占用的。你可以先查看 /var 目录的大小分布，再清理无用的日志文件。"
        assertEquals(null, AiContext.extractCommand(prose))
    }

    @Test
    fun extractCommandReturnsNullForSingleCommentLine() {
        assertEquals(null, AiContext.extractCommand("# 这是注释不是命令"))
        assertEquals(null, AiContext.extractCommand("// 注释"))
    }

    @Test
    fun extractCommandReturnsNullForBlankAnswer() {
        assertEquals(null, AiContext.extractCommand("   "))
    }

    @Test
    fun parseSegmentsSplitsProseAndCodeBlocks() {
        val answer = "先看磁盘：\n\n```bash\ndf -h\n```\n\n再清理。"
        val segments = parseSegments(answer)
        assertEquals(3, segments.size)
        assertTrue(segments[0].text.contains("先看磁盘"))
        assertTrue(!segments[0].isCode)
        assertTrue(segments[1].isCode)
        assertTrue(segments[1].text.contains("df -h"))
        assertTrue(segments[2].text.contains("再清理"))
        assertTrue(!segments[2].isCode)
    }

    @Test
    fun parseSegmentsHandlesMultipleBlocksAndLanguageTag() {
        val answer = "```bash\nls\n```\n中间\n```sh\npwd\n```"
        val segments = parseSegments(answer)
        assertEquals(3, segments.size)
        assertEquals(true, segments[0].isCode)
        assertTrue("语言标注应被剥离", !segments[0].text.contains("bash"))
        assertEquals("ls", segments[0].text.trim())
        assertTrue(!segments[1].isCode)
        assertTrue(segments[1].text.contains("中间"))
        assertEquals(true, segments[2].isCode)
        assertEquals("pwd", segments[2].text.trim())
    }

    @Test
    fun parseSegmentsPlainProse() {
        val segments = parseSegments("只是一段普通说明文字。")
        assertEquals(1, segments.size)
        assertTrue(!segments[0].isCode)
    }

    @Test
    fun parseSegmentsBlankAnswer() {
        assertEquals(emptyList<AnswerSegment>(), parseSegments("   "))
    }
}
