package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.sftp.SftpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val label: String) : ConnectionState
    data class Disconnected(
        val reason: String = "连接已关闭",
        val cause: DisconnectCause = DisconnectCause.UNKNOWN,
    ) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

sealed interface TerminalChannelState {
    data object Closed : TerminalChannelState
    data object Opening : TerminalChannelState
    data class Active(val target: TerminalTarget) : TerminalChannelState
    data class Ended(val reason: String, val exitCode: Int? = null) : TerminalChannelState
    data class Error(val message: String) : TerminalChannelState
}

enum class DisconnectCause {
    USER,
    REMOTE_SHELL_EXIT,
    REMOTE_CHANNEL_CLOSED,
    TRANSPORT_CLOSED,
    KEEPALIVE_TIMEOUT,
    READ_ERROR,
    WRITE_ERROR,
    APP_CLOSED,
    UNKNOWN,
}

enum class HostKeyIssue { UNKNOWN, CHANGED }

data class HostKeyRequest(
    val hostname: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val previousFingerprint: String? = null,
    val issue: HostKeyIssue,
    val subject: HostKeySubject = HostKeySubject.TARGET,
)

interface SshSession {
    val state: StateFlow<ConnectionState>
    val output: Flow<ByteArray>
    val terminalState: StateFlow<TerminalChannelState>
    val hostKeyRequest: StateFlow<HostKeyRequest?>
    val stage: StateFlow<ConnectionStage>

    suspend fun connect(route: SshRoute, credentials: RouteCredentials, openShell: Boolean = true)
    suspend fun execute(command: String, timeoutMillis: Long = 10_000, maxOutputBytes: Int = 1024 * 1024): RemoteCommandResult
    suspend fun openTerminal(target: TerminalTarget)
    suspend fun closeTerminal()
    suspend fun write(data: ByteArray)
    suspend fun resize(columns: Int, rows: Int)
    suspend fun disconnect()
    fun respondToHostKey(accept: Boolean)
    fun close()
}

interface SftpCapableSession {
    suspend fun openSftpClient(): SftpClient
}
