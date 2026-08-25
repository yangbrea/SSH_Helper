package com.yang136.sshhelper.ai

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser

data class MarkdownInline(
    val text: String,
    val code: Boolean = false,
    val emphasis: Boolean = false,
    val strong: Boolean = false,
    val link: String? = null,
)

sealed interface MarkdownBlock {
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class ListEntry(val marker: String, val content: List<MarkdownInline>) : MarkdownBlock
    data class Quote(val content: List<MarkdownInline>) : MarkdownBlock
    data class CodeBlock(val code: String, val language: String?, val shellExecutable: Boolean) : MarkdownBlock
}

data class MarkdownDocument(val blocks: List<MarkdownBlock>) {
    fun shellCommands(): List<Pair<String, String>> = blocks.mapNotNull { block ->
        val code = block as? MarkdownBlock.CodeBlock ?: return@mapNotNull null
        if (!code.shellExecutable) return@mapNotNull null
        val command = normalizeConsoleCommand(code.code, code.language)
        if (validateCommand(command) != null) null else command to (code.language ?: "shell")
    }
}

object MarkdownContentParser {
    private val parser = Parser.builder().build()

    fun parse(markdown: String): MarkdownDocument {
        if (markdown.isBlank()) return MarkdownDocument(emptyList())
        val document = parser.parse(markdown)
        val blocks = mutableListOf<MarkdownBlock>()
        var child = document.firstChild
        while (child != null) {
            appendBlock(child, blocks)
            child = child.next
        }
        return MarkdownDocument(blocks)
    }

    private fun appendBlock(node: Node, output: MutableList<MarkdownBlock>) {
        when (node) {
            is FencedCodeBlock -> {
                val language = node.info.trim().substringBefore(' ').takeIf(String::isNotBlank)
                output += MarkdownBlock.CodeBlock(node.literal.trimEnd('\n'), language, isShellLanguage(language))
            }
            is IndentedCodeBlock -> output += MarkdownBlock.CodeBlock(node.literal.trimEnd('\n'), null, false)
            is Heading -> output += MarkdownBlock.Heading(node.level, inlineContent(node))
            is Paragraph -> output += MarkdownBlock.Paragraph(inlineContent(node))
            is BulletList -> appendList(node, ordered = false, output)
            is OrderedList -> appendList(node, ordered = true, output)
            is BlockQuote -> output += MarkdownBlock.Quote(inlineContent(node))
            is HtmlBlock -> output += MarkdownBlock.Paragraph(listOf(MarkdownInline(node.literal)))
            else -> {
                var child = node.firstChild
                while (child != null) {
                    appendBlock(child, output)
                    child = child.next
                }
            }
        }
    }

    private fun appendList(node: Node, ordered: Boolean, output: MutableList<MarkdownBlock>) {
        var item = node.firstChild
        var number = (node as? OrderedList)?.markerStartNumber ?: 1
        while (item != null) {
            if (item is ListItem) {
                output += MarkdownBlock.ListEntry(if (ordered) "${number++}." else "•", inlineContent(item))
            }
            item = item.next
        }
    }

    private fun inlineContent(parent: Node): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        fun visit(node: Node, emphasis: Boolean = false, strong: Boolean = false, link: String? = null) {
            when (node) {
                is Text -> result += MarkdownInline(node.literal, emphasis = emphasis, strong = strong, link = link)
                is Code -> result += MarkdownInline(node.literal, code = true, emphasis = emphasis, strong = strong, link = link)
                is SoftLineBreak, is HardLineBreak -> result += MarkdownInline("\n")
                is HtmlInline -> result += MarkdownInline(node.literal, emphasis = emphasis, strong = strong)
                else -> {
                    val nextEmphasis = emphasis || node is Emphasis
                    val nextStrong = strong || node is StrongEmphasis
                    val nextLink = if (node is Link) safeHttpLink(node.destination) else link
                    var child = node.firstChild
                    while (child != null) {
                        visit(child, nextEmphasis, nextStrong, nextLink)
                        child = child.next
                    }
                }
            }
        }
        var child = parent.firstChild
        while (child != null) {
            visit(child)
            child = child.next
        }
        return result
    }
}

val SHELL_LANGUAGES = setOf("sh", "bash", "zsh", "ash", "dash", "ksh", "shell", "console", "terminal")

fun isShellLanguage(language: String?): Boolean = language?.lowercase() in SHELL_LANGUAGES

fun safeHttpLink(destination: String): String? = runCatching {
    val uri = java.net.URI(destination)
    destination.takeIf { uri.scheme.equals("http", true) || uri.scheme.equals("https", true) }
}.getOrNull()

private fun normalizeConsoleCommand(code: String, language: String?): String {
    if (language?.lowercase() !in setOf("console", "terminal")) return code.trim()
    val prompted = code.lines().mapNotNull { line ->
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("$ ") -> trimmed.removePrefix("$ ")
            trimmed.startsWith("# ") -> trimmed.removePrefix("# ")
            else -> null
        }
    }
    return (prompted.takeIf(List<String>::isNotEmpty)?.joinToString("\n") ?: code).trim()
}
