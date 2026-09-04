package com.yang136.sshhelper.ssh

import java.io.ByteArrayOutputStream

/**
 * Bounded raw terminal replay data.
 *
 * When old bytes must be discarded, a terminal RIS prefix establishes parser
 * ground state before the retained tail. Without it, a replay can begin in the
 * middle of CSI/OSC/UTF-8 state and accidentally restore mouse or alt-screen
 * modes from an incomplete escape sequence.
 */
internal class TerminalReplayBuffer(private val maxBytes: Int) {
    private val bytes = ByteArrayOutputStream(maxBytes.coerceAtMost(8 * 1024).coerceAtLeast(32))

    init {
        require(maxBytes > TERMINAL_RESET.size)
    }

    @Synchronized
    fun append(incoming: ByteArray) {
        if (incoming.isEmpty()) return
        if (bytes.size() + incoming.size <= maxBytes) {
            bytes.write(incoming)
            return
        }

        val incomingTailSize = minOf(incoming.size, maxBytes - TERMINAL_RESET.size)
        val retainedCapacity = (maxBytes - TERMINAL_RESET.size - incomingTailSize)
            .coerceAtMost(maxBytes / 2)
            .coerceAtLeast(0)
        val previous = bytes.toByteArray()
        val retainedStart = (previous.size - retainedCapacity).coerceAtLeast(0)
        val incomingStart = incoming.size - incomingTailSize

        bytes.reset()
        bytes.write(TERMINAL_RESET)
        bytes.write(previous, retainedStart, previous.size - retainedStart)
        bytes.write(incoming, incomingStart, incomingTailSize)
    }

    @Synchronized
    fun snapshot(): ByteArray = bytes.toByteArray()

    @Synchronized
    fun clear() = bytes.reset()

    private companion object {
        // RIS: reset terminal state and parser before replaying a truncated tail.
        val TERMINAL_RESET = byteArrayOf(0x1B, 'c'.code.toByte())
    }
}
