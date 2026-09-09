package com.yang136.sshhelper.ssh.native

/** Immutable event copied out of the native SSH owner loop. */
data class NativeSshEvent(
    val kind: Int,
    val requestId: Long,
    val completionKind: Int,
    val sessionState: Int,
    val errorDomain: String,
    val errorCode: String,
    val errorMessage: String,
    val payload: ByteArray,
) {
    companion object {
        const val KIND_COMPLETION = 0
        const val KIND_SESSION_STATE_CHANGED = 1

        const val COMPLETION_SUCCEEDED = 0
        const val COMPLETION_FAILED = 1
        const val COMPLETION_CANCELLED = 2
    }
}
