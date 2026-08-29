package com.yang136.sshhelper.discovery

data class ArpEntry(val address: String, val macAddress: String)

object ArpTableParser {
    private val macPattern = Regex("^[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}$")

    fun parse(content: String, interfaceName: String): List<ArpEntry> = content.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size < 6 || columns[5] != interfaceName) return@mapNotNull null
            val address = columns[0].takeIf { parseIpv4(it) != null } ?: return@mapNotNull null
            val flags = columns[2].removePrefix("0x").toIntOrNull(16) ?: return@mapNotNull null
            if (flags and 0x2 == 0) return@mapNotNull null
            val mac = normalizeMac(columns[3]) ?: return@mapNotNull null
            ArpEntry(address, mac)
        }
        .toList()

    fun normalizeMac(value: String): String? {
        if (!macPattern.matches(value)) return null
        val octets = value.split(':').map { it.toInt(16) }
        if (octets.all { it == 0 } || octets.first() and 1 != 0) return null
        return octets.joinToString(":") { it.toString(16).padStart(2, '0').uppercase() }
    }
}

