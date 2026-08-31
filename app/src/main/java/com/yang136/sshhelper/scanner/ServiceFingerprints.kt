package com.yang136.sshhelper.scanner

import java.util.Locale

internal val PASSIVE_BANNER_PORTS = setOf(21, 22, 23, 25, 110, 143, 587)
internal val HTTP_PORTS = setOf(80, 8000, 8080)
internal val TLS_PORTS = setOf(443, 993, 995, 8443, 8883)

fun fingerprintFromBanner(port: Int, banner: String): ServiceFingerprint {
    val clean = sanitizeBanner(banner)
    val upper = clean.uppercase(Locale.ROOT)
    return when {
        upper.lineSequence().any { it.startsWith("SSH-") } -> {
            val identity = clean.lineSequence().first { it.startsWith("SSH-") }
            val software = identity.substringAfter("SSH-").substringAfter('-', "").trim().ifBlank { null }
            ServiceFingerprint("SSH", software?.substringBefore('_'), software?.substringAfter('_', "")?.ifBlank { null }, clean, "SSH identification", FingerprintConfidence.HIGH)
        }
        upper.startsWith("HTTP/") -> {
            val server = Regex("(?im)^server:\\s*([^\\r\\n]+)").find(clean)?.groupValues?.get(1)
            ServiceFingerprint("HTTP", server?.substringBefore('/'), server?.substringAfter('/', "")?.ifBlank { null }, clean, "HTTP response", FingerprintConfidence.HIGH)
        }
        upper.startsWith("220") && ("FTP" in upper || port == 21) -> ServiceFingerprint("FTP", banner = clean, evidence = "FTP greeting", confidence = FingerprintConfidence.HIGH)
        upper.startsWith("220") && ("SMTP" in upper || "ESMTP" in upper || port == 25 || port == 587) -> ServiceFingerprint("SMTP", banner = clean, evidence = "SMTP greeting", confidence = FingerprintConfidence.HIGH)
        upper.startsWith("+OK") && port == 110 -> ServiceFingerprint("POP3", banner = clean, evidence = "POP3 greeting", confidence = FingerprintConfidence.HIGH)
        upper.startsWith("* OK") && port == 143 -> ServiceFingerprint("IMAP", banner = clean, evidence = "IMAP greeting", confidence = FingerprintConfidence.HIGH)
        else -> serviceFromPort(port, clean, FingerprintConfidence.MEDIUM, "被动 Banner 与端口映射")
    }
}

fun fallbackFingerprint(port: Int): ServiceFingerprint = serviceFromPort(port, null, FingerprintConfidence.LOW, "仅按常见端口推测")

fun tlsFingerprint(
    port: Int,
    protocol: String,
    cipherSuite: String,
    subject: String?,
    issuer: String?,
    applicationProtocol: String?,
): ServiceFingerprint {
    val service = when (port) {
        443, 8443 -> "HTTPS"
        993 -> "IMAPS"
        995 -> "POP3S"
        8883 -> "MQTT-TLS"
        else -> "TLS"
    }
    val evidence = buildList {
        add("$protocol · $cipherSuite")
        applicationProtocol?.takeIf(String::isNotBlank)?.let { add("ALPN $it") }
        subject?.let { add("Subject $it") }
        issuer?.let { add("Issuer $it") }
    }.joinToString("\n")
    return ServiceFingerprint(service, product = protocol, banner = evidence, evidence = "TLS handshake", confidence = FingerprintConfidence.HIGH, tlsUnverified = true)
}

fun sanitizeBanner(value: String): String = buildString {
    value.take(MAX_PORT_BANNER_BYTES).forEach { character ->
        append(if (character == '\n' || character == '\r' || character == '\t' || character.code >= 32) character else '�')
    }
}.trim()

private fun serviceFromPort(port: Int, banner: String?, confidence: FingerprintConfidence, evidence: String): ServiceFingerprint {
    val service = when (port) {
        21 -> "FTP"; 22 -> "SSH"; 23 -> "TELNET"; 25, 587 -> "SMTP"; 53 -> "DNS"
        80, 8000, 8080 -> "HTTP"; 110 -> "POP3"; 143 -> "IMAP"; 443, 8443 -> "HTTPS"
        445 -> "SMB"; 554 -> "RTSP"; 631 -> "IPP"; 993 -> "IMAPS"; 995 -> "POP3S"
        1433 -> "MSSQL"; 1521 -> "ORACLE"; 1883 -> "MQTT"; 3306 -> "MYSQL"; 3389 -> "RDP"
        5432 -> "POSTGRESQL"; 5900 -> "VNC"; 6379 -> "REDIS"; 8883 -> "MQTT-TLS"
        9100 -> "JETDIRECT"; 27017 -> "MONGODB"; else -> "UNKNOWN"
    }
    return ServiceFingerprint(service, banner = banner, evidence = evidence, confidence = confidence)
}
