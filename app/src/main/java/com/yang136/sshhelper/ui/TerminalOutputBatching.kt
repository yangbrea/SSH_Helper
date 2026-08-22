package com.yang136.sshhelper.ui

internal const val MAX_TERMINAL_RENDER_BATCH_BYTES = 48 * 1024
internal const val TERMINAL_RENDER_QUEUE_CAPACITY = 256
internal const val TERMINAL_RENDER_WARNING_BYTES = 256 * 1024
internal const val TERMINAL_RENDER_RECOVERED_BYTES = 64 * 1024

internal fun splitTerminalOutput(
    bytes: ByteArray,
    maximumBytes: Int = MAX_TERMINAL_RENDER_BATCH_BYTES,
): List<ByteArray> {
    require(maximumBytes > 0)
    if (bytes.isEmpty()) return emptyList()
    if (bytes.size <= maximumBytes) return listOf(bytes.copyOf())
    return buildList {
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + maximumBytes).coerceAtMost(bytes.size)
            add(bytes.copyOfRange(offset, end))
            offset = end
        }
    }
}
