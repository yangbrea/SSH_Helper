package com.yang136.sshhelper.ai

/** Terminal output helpers: ANSI stripping, bounded tail extraction, command extraction. */
object AiContext {

    private val csiSequence = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val oscSequence = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val otherEscape = Regex("\u001B[()][0-9A-B]|[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

    /** Removes ANSI CSI/OSC and other control sequences, collapsing to plain text. */
    fun stripAnsi(input: String): String = input
        .replace(oscSequence, "")
        .replace(csiSequence, "")
        .replace(otherEscape, "")

    /** Takes the tail of raw terminal bytes, decodes leniently, strips ANSI, and bounds the size. */
    fun recentTerminalText(bytes: ByteArray, maxBytes: Int = DEFAULT_CONTEXT_BYTES): String {
        val tail = bytes.takeLast(maxBytes).toByteArray()
        val text = tail.toString(Charsets.UTF_8)
            .let { raw ->
                // Cut at a potential partial multi-byte boundary at the head.
                raw.substringAfter('\n')
            }
        return stripAnsi(text).trim().takeLast(MAX_CONTEXT_CHARS)
    }

    /**
     * Extracts the command to fill into the terminal from an AI answer, or null when the answer
     * contains no explicit command. The first fenced code block wins; a single bare command line
     * (ASCII, no comment prefix) is also accepted. Prose — multi-line or containing Chinese text —
     * yields null, so explanatory text is never pasted into the terminal.
     */
    fun extractCommand(answer: String): String? {
        val fenced = Regex("```(?:[\\w+-]*\\n)?([\\s\\S]*?)```").find(answer)
        val block = fenced?.groupValues?.get(1)?.trim()
        if (!block.isNullOrBlank()) return block
        val singleLine = answer.trim().lines().singleOrNull()?.trim()
        if (!singleLine.isNullOrBlank() &&
            !singleLine.startsWith("#") &&
            !singleLine.startsWith("//") &&
            !containsCjk(singleLine)
        ) {
            return singleLine
        }
        return null
    }

    private fun containsCjk(text: String): Boolean {
        for (character in text) {
            if (character in '\u4e00'..'\u9fff') return true
        }
        return false
    }

    const val DEFAULT_CONTEXT_BYTES = 6 * 1024
    const val MAX_CONTEXT_CHARS = 4_000
}
