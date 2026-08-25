package com.yang136.sshhelper.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownContentTest {
    @Test
    fun parsesFencedTildeIndentedAndUnclosedCodeBlocks() {
        val document = MarkdownContentParser.parse(
            """
            # 标题
            `inline`

            ~~~python
            print('x')
            ~~~

                indented()

            ```bash
            df -h
            """.trimIndent(),
        )

        val blocks = document.blocks.filterIsInstance<MarkdownBlock.CodeBlock>()
        assertEquals(3, blocks.size)
        assertEquals("python", blocks[0].language)
        assertFalse(blocks[0].shellExecutable)
        assertNull(blocks[1].language)
        assertTrue(blocks[2].shellExecutable)
        assertEquals(listOf("df -h" to "bash"), document.shellCommands())
    }

    @Test
    fun preservesInlineStylesListsQuotesAndSafeLinks() {
        val document = MarkdownContentParser.parse(
            "**粗体**、*强调*、[安全](https://example.com)、[危险](javascript:alert(1))\n\n- 项目\n\n> 引用",
        )
        val inlines = document.blocks.filterIsInstance<MarkdownBlock.Paragraph>().first().content
        assertTrue(inlines.any { it.strong && it.text == "粗体" })
        assertTrue(inlines.any { it.emphasis && it.text == "强调" })
        assertTrue(inlines.any { it.link == "https://example.com" })
        assertTrue(inlines.none { it.link?.startsWith("javascript") == true })
        assertTrue(document.blocks.any { it is MarkdownBlock.ListEntry })
        assertTrue(document.blocks.any { it is MarkdownBlock.Quote })
    }

    @Test
    fun consoleBlocksKeepOnlyPromptLinesForCommandCard() {
        val document = MarkdownContentParser.parse("```console\n$ pwd\n/home/user\n$ id\nuid=1000\n```")
        assertEquals(listOf("pwd\nid" to "console"), document.shellCommands())
    }
}
