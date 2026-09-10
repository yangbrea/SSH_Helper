package com.yang136.sshhelper.ssh

import java.security.MessageDigest
import java.util.Base64

/**
 * Declarations every SSH backend shares.
 *
 * These used to sit at the bottom of `JschSshSession.kt`, simply because that backend needed
 * them first. They are not JSch-specific: `Libssh2SshSession` reads the timeouts and keepalive
 * budget, `SessionManager` maps [REMOTE_COMMAND_TIMEOUT_EXIT_CODE] and
 * [REMOTE_COMMAND_OUTPUT_LIMIT_EXIT_CODE] into UI state, and the host-key comparison is policy
 * that both backends must implement identically. Deleting the JSch backend therefore moved them
 * here instead of removing them with it.
 */

internal enum class HostKeyMatch { UNKNOWN, MATCH, CHANGED }

internal fun compareHostKey(expectedBase64: String?, key: ByteArray): HostKeyMatch {
    if (expectedBase64 == null) return HostKeyMatch.UNKNOWN
    val presented = Base64.getEncoder().encodeToString(key)
    return if (MessageDigest.isEqual(expectedBase64.encodeToByteArray(), presented.encodeToByteArray())) {
        HostKeyMatch.MATCH
    } else {
        HostKeyMatch.CHANGED
    }
}

internal fun sha256Fingerprint(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

internal const val SSH_CONNECT_TIMEOUT_MS = 15_000
internal const val SSH_CHANNEL_CONNECT_TIMEOUT_MS = 10_000
internal const val REMOTE_COMMAND_TIMEOUT_EXIT_CODE = 124
internal const val REMOTE_COMMAND_OUTPUT_LIMIT_EXIT_CODE = 125
internal const val REMOTE_COMMAND_POLL_INTERVAL_MS = 20L
/**
 * 保活探针间隔 5s：比默认 20s 更激进，配合服务端/NAT 空闲清理（常见 30s~5min）
 * 留足余量；即使个别探针被 Wi-Fi 省电延迟，也能赶在路径超时前刷新连接。
 * 判定死亡 ≈ 间隔 × 容忍次数 = 5s × 6 = 30s。
 */
internal const val SSH_KEEPALIVE_INTERVAL_MS = 5_000
internal const val SSH_KEEPALIVE_MAX_MISSES = 6
/** 无 shell 转发会话的传输监视轮询间隔。 */
internal const val TRANSPORT_WATCH_INTERVAL_MS = 5_000L
/** 未知主机密钥确认超时；超时按拒绝处理。 */
internal const val HOST_KEY_CONFIRM_TIMEOUT_MS = 60_000L
