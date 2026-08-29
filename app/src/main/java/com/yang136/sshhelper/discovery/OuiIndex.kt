package com.yang136.sshhelper.discovery

class OuiIndex private constructor(private val entries: List<Entry>) : MacVendorResolver {
    private data class Entry(val prefix: Long, val prefixLength: Int, val vendor: String)

    override fun vendorFor(macAddress: String): String? {
        val normalized = ArpTableParser.normalizeMac(macAddress) ?: return null
        val value = normalized.replace(":", "").toLong(16)
        return entries.firstOrNull { entry ->
            val shift = 48 - entry.prefixLength
            value ushr shift == entry.prefix
        }?.vendor
    }

    companion object {
        val Empty = OuiIndex(emptyList())

        fun parse(lines: Sequence<String>): OuiIndex {
            val parsed = lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapNotNull null
                val columns = trimmed.split('\t', limit = 2)
                if (columns.size != 2) return@mapNotNull null
                val key = columns[0].split('/', limit = 2)
                if (key.size != 2) return@mapNotNull null
                val prefixLength = key[1].toIntOrNull()?.takeIf { it in 1..48 } ?: return@mapNotNull null
                val requiredHex = (prefixLength + 3) / 4
                val hex = key[0].replace(Regex("[^0-9A-Fa-f]"), "")
                if (hex.length < requiredHex) return@mapNotNull null
                val prefix = hex.take(requiredHex).toLongOrNull(16) ?: return@mapNotNull null
                val extraBits = requiredHex * 4 - prefixLength
                Entry(prefix ushr extraBits, prefixLength, columns[1].trim())
            }.sortedByDescending(Entry::prefixLength).toList()
            return OuiIndex(parsed)
        }
    }
}

