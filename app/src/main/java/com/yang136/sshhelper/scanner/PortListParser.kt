package com.yang136.sshhelper.scanner

fun parsePortScanList(input: String): Result<Set<Int>> = runCatching {
    val value = input.trim()
    require(value.isNotEmpty()) { "请输入端口或端口范围" }
    val ports = sortedSetOf<Int>()
    value.split(',').forEach { rawToken ->
        val token = rawToken.trim()
        require(token.isNotEmpty()) { "端口列表包含空项" }
        val separator = token.indexOf('-')
        if (separator < 0) {
            val port = token.toIntOrNull() ?: throw IllegalArgumentException("无效端口：$token")
            require(port in 1..65_535) { "端口必须在 1–65535 之间" }
            ports += port
        } else {
            require(token.indexOf('-', separator + 1) < 0) { "无效端口范围：$token" }
            val start = token.substring(0, separator).trim().toIntOrNull()
                ?: throw IllegalArgumentException("无效端口范围：$token")
            val end = token.substring(separator + 1).trim().toIntOrNull()
                ?: throw IllegalArgumentException("无效端口范围：$token")
            require(start in 1..65_535 && end in 1..65_535 && start <= end) { "无效端口范围：$token" }
            require(ports.size + (end - start + 1) <= MAX_PORT_SCAN_PORTS) { "一次最多扫描 65535 个端口" }
            ports.addAll(start..end)
        }
        require(ports.size <= MAX_PORT_SCAN_PORTS) { "一次最多扫描 65535 个端口" }
    }
    ports
}

fun commonPortScanInput(): String = COMMON_SCAN_PORTS.joinToString(",")
