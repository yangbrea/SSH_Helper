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
     * Extracts the command to fill into the terminal from an AI answer: the first fenced code
     * block wins; otherwise the whole answer is returned so the user can judge.
     */
    fun extractCommand(answer: String): String {
        val fenced = Regex("```(?:[\\w+-]*\\n)?([\\s\\S]*?)```").find(answer)
        val block = fenced?.groupValues?.get(1)?.trim()
        if (!block.isNullOrBlank()) return block
        // A single shell-looking line with no explanation is used as-is.
        val singleLine = answer.trim().lines().singleOrNull()
        return singleLine?.takeIf { !it.startsWith("#") && !it.startsWith("//") } ?: answer.trim()
    }

    const val DEFAULT_CONTEXT_BYTES = 6 * 1024
    const val MAX_CONTEXT_CHARS = 4_000
}
