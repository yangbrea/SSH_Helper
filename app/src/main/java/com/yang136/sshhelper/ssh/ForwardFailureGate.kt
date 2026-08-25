package com.yang136.sshhelper.ssh

import java.util.concurrent.ConcurrentHashMap

internal enum class ForwardFailureKind { AUTH, TRANSIENT }

/**
 * Prevents a desired forwarding rule from creating a fresh SSH session on every reconcile.
 * Authentication failures require an explicit retry; transport failures may also be released
 * by a network-available callback.
 */
internal class ForwardFailureGate {
    private val blocked = ConcurrentHashMap<Long, ForwardFailureKind>()

    fun block(ruleId: Long, kind: ForwardFailureKind) {
        blocked[ruleId] = kind
    }

    fun isBlocked(ruleId: Long): Boolean = blocked.containsKey(ruleId)

    fun allowExplicitRetry(ruleId: Long) {
        blocked.remove(ruleId)
    }

    fun allowNetworkRetry(): List<Long> = blocked.entries
        .filter { it.value == ForwardFailureKind.TRANSIENT }
        .mapNotNull { (ruleId, kind) ->
            if (blocked.remove(ruleId, kind)) ruleId else null
        }
}

internal fun classifyForwardFailure(message: String): ForwardFailureKind =
    if (
        message.contains("认证", ignoreCase = true) ||
        message.contains("私钥", ignoreCase = true) ||
        message.contains("主机", ignoreCase = true) ||
        message.contains("auth", ignoreCase = true) ||
        message.contains("permission denied", ignoreCase = true) ||
        message.contains("host key", ignoreCase = true)
    ) {
        ForwardFailureKind.AUTH
    } else {
        ForwardFailureKind.TRANSIENT
    }
