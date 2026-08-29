package com.yang136.sshhelper.discovery

@ConsistentCopyVisibility
data class Ipv4Cidr private constructor(
    val network: Long,
    val prefixLength: Int,
) {
    val addressCount: Long = 1L shl (32 - prefixLength)
    val broadcast: Long = network + addressCount - 1

    override fun toString(): String = "${formatIpv4(network)}/$prefixLength"

    fun contains(address: String): Boolean = parseIpv4(address)?.let { it in network..broadcast } == true

    fun usableAddresses(ownAddress: String? = null): List<String> {
        val own = ownAddress?.let(::parseIpv4)
        val first = if (prefixLength <= 30) network + 1 else network
        val last = if (prefixLength <= 30) broadcast - 1 else broadcast
        if (last < first) return emptyList()
        return (first..last).asSequence()
            .filter { it != own }
            .map(::formatIpv4)
            .toList()
    }

    fun isAllowedLanRange(): Boolean = ALLOWED_LAN_RANGES.any { range ->
        network >= range.first && broadcast <= range.last
    }

    companion object {
        fun parse(value: String): Result<Ipv4Cidr> = runCatching {
            val parts = value.trim().split('/')
            require(parts.size == 2) { "请输入 IPv4 CIDR，例如 192.168.1.0/24" }
            val address = parseIpv4(parts[0]) ?: error("IPv4 地址格式不正确")
            val prefix = parts[1].toIntOrNull() ?: error("CIDR 前缀格式不正确")
            require(prefix in 0..32) { "CIDR 前缀必须在 0–32 之间" }
            val mask = prefixMask(prefix)
            Ipv4Cidr(address and mask, prefix)
        }

        fun defaultFor(address: String, prefixLength: Int): Ipv4Cidr {
            val ip = requireNotNull(parseIpv4(address)) { "IPv4 地址格式不正确" }
            val safePrefix = prefixLength.coerceIn(0, 32)
            val actual = Ipv4Cidr(ip and prefixMask(safePrefix), safePrefix)
            return if (actual.addressCount <= MAX_SCAN_ADDRESSES) actual
            else Ipv4Cidr(ip and prefixMask(24), 24)
        }
    }
}

fun validateScanCidr(cidr: Ipv4Cidr): String? = when {
    !cidr.isAllowedLanRange() -> "扫描范围必须位于私有、CGNAT 或 IPv4 Link-local 网段"
    cidr.addressCount > MAX_SCAN_ADDRESSES -> "单次最多扫描 $MAX_SCAN_ADDRESSES 个 IPv4 地址"
    else -> null
}

fun parsePortList(value: String): Result<Set<Int>> = runCatching {
    val tokens = value.split(',', '，', ' ', '\n', '\t').filter(String::isNotBlank)
    require(tokens.isNotEmpty()) { "请至少输入一个 SSH 端口" }
    val ports = tokens.map { token ->
        token.toIntOrNull()?.also { require(it in 1..65_535) { "端口必须在 1–65535 之间" } }
            ?: error("端口“$token”格式不正确")
    }.toCollection(linkedSetOf())
    require(ports.size <= MAX_SCAN_PORTS) { "单次最多扫描 $MAX_SCAN_PORTS 个端口" }
    ports
}

fun parseGeneralPortList(value: String): Result<Set<Int>> = runCatching {
    if (value.isBlank()) return@runCatching emptySet()
    val ports = parsePortList(value).getOrThrow()
    require(ports.size <= MAX_GENERAL_CUSTOM_PORTS) {
        "通用模式最多添加 $MAX_GENERAL_CUSTOM_PORTS 个第一阶段端口"
    }
    ports
}

fun parseIpv4(value: String): Long? {
    val parts = value.trim().split('.')
    if (parts.size != 4) return null
    var result = 0L
    for (part in parts) {
        if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        result = (result shl 8) or octet.toLong()
    }
    return result
}

internal fun formatIpv4(value: Long): String = listOf(24, 16, 8, 0)
    .joinToString(".") { shift -> ((value ushr shift) and 0xff).toString() }

private fun prefixMask(prefix: Int): Long = when (prefix) {
    0 -> 0L
    else -> (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
}

private fun range(cidr: String): LongRange {
    val parsed = Ipv4Cidr.parse(cidr).getOrThrow()
    return parsed.network..parsed.broadcast
}

private val ALLOWED_LAN_RANGES = listOf(
    range("10.0.0.0/8"),
    range("100.64.0.0/10"),
    range("169.254.0.0/16"),
    range("172.16.0.0/12"),
    range("192.168.0.0/16"),
)
