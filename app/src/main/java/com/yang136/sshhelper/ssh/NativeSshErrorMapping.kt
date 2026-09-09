package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.diagnosticlog.DiagnosticRedactor
import com.yang136.sshhelper.ssh.native.NativeSshException

/**
 * Converts structured native SSH errors into stable product-level disconnect
 * causes and user-facing text. Raw libssh2 numbers are intentionally not shown.
 */
internal fun NativeSshException.toDisconnectCause(): DisconnectCause {
    val normalizedCode = code.lowercase()
    return when {
        normalizedCode == "keepalive_timeout" ||
            normalizedCode.contains("keepalive") && domain == "timeout" ->
            DisconnectCause.KEEPALIVE_TIMEOUT

        normalizedCode == "transport_closed" ||
            normalizedCode.contains("transport_closed") ||
            normalizedCode == "socket_disconnect" ||
            normalizedCode == "eof" ->
            DisconnectCause.TRANSPORT_CLOSED

        normalizedCode.contains("read") || normalizedCode.contains("recv") ->
            DisconnectCause.READ_ERROR

        normalizedCode.contains("write") || normalizedCode.contains("send") ->
            DisconnectCause.WRITE_ERROR

        domain == "channel" && normalizedCode.contains("closed") ->
            DisconnectCause.REMOTE_CHANNEL_CLOSED

        else -> DisconnectCause.UNKNOWN
    }
}

/** Returns a user-facing error string without exposing internal numeric codes. */
internal fun NativeSshException.toUserMessage(fallback: String = "SSH 连接失败"): String {
    val mapped = when {
        domain == "timeout" && code.contains("keepalive") -> "SSH keepalive 超时，连接已断开"
        domain == "system" && code == "transport_closed" -> "SSH 传输连接已关闭"
        domain == "system" && code.contains("recv") -> "SSH 读取失败"
        domain == "system" && code.contains("send") -> "SSH 写入失败"
        domain == "host_key" -> "主机密钥验证失败"
        domain == "auth" -> "SSH 身份认证失败"
        else -> null
    }
    return mapped ?: DiagnosticRedactor.redact(message ?: "").ifBlank { fallback }
}

internal fun sanitizeNativeSshMessage(message: String): String =
    DiagnosticRedactor.redact(message)
