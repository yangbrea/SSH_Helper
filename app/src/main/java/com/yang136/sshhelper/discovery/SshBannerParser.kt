package com.yang136.sshhelper.discovery

object SshBannerParser {
    private val identification = Regex("^SSH-([0-9]+(?:\\.[0-9]+)?)-([^\\s]+)(?: .*)?$")

    fun parse(bytes: ByteArray): SshBanner? {
        val bounded = bytes.copyOfRange(0, minOf(bytes.size, 2_048))
        val lines = bounded.toString(Charsets.ISO_8859_1).split('\n').take(9)
        for (rawLine in lines) {
            val line = rawLine.removeSuffix("\r")
            if (!line.startsWith("SSH-")) continue
            if (line.length + 2 > 255 || line.any { it.code !in 0x20..0x7e }) return null
            val match = identification.matchEntire(line) ?: return null
            return SshBanner(
                raw = line,
                protocolVersion = match.groupValues[1],
                softwareVersion = match.groupValues[2],
            )
        }
        return null
    }
}
