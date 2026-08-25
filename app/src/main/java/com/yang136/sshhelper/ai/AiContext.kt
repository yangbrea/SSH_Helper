package com.yang136.sshhelper.ai

/** Terminal output helpers: ANSI stripping and bounded tail extraction. */
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

    const val DEFAULT_CONTEXT_BYTES = 6 * 1024
    const val MAX_CONTEXT_CHARS = 4_000
}
