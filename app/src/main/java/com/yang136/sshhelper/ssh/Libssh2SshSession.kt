package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.ssh.native.NativeSshBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Minimal libssh2-backed [SshSession] POC.
 *
 * This implementation currently uses the synchronous
 * [NativeSshBridge.nativeConnectExec] path for direct password connections and
 * exec only. It intentionally does not yet implement SFTP/forwarding, persistent
 * channels, proxy/jump, host-key persistence or the non-blocking event loop.
 */
class Libssh2SshSession : SshSession {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val mutableOutput = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    private val mutableTerminalState = MutableStateFlow<TerminalChannelState>(TerminalChannelState.Closed)
    private val mutableHostKeyRequest = MutableStateFlow<HostKeyRequest?>(null)
    private val mutableStage = MutableStateFlow(ConnectionStage.READY)

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    override val output: Flow<ByteArray> = mutableOutput.asSharedFlow()
    override val terminalState: StateFlow<TerminalChannelState> = mutableTerminalState.asStateFlow()
    override val hostKeyRequest: StateFlow<HostKeyRequest?> = mutableHostKeyRequest.asStateFlow()
    override val stage: StateFlow<ConnectionStage> = mutableStage.asStateFlow()

    private var route: SshRoute? = null
    private var password: String? = null
    private var closed = false

    override suspend fun connect(
        route: SshRoute,
        credentials: RouteCredentials,
        openShell: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        if (route.jump != null) {
            mutableState.value = ConnectionState.Error("libssh2 POC 暂不支持跳板机")
            return@withContext
        }
        val targetCredential = credentials.target
        if (targetCredential !is Credential.Password) {
            mutableState.value = ConnectionState.Error("libssh2 POC 暂只支持密码认证")
            return@withContext
        }
        mutableState.value = ConnectionState.Connecting
        mutableStage.value = ConnectionStage.TARGET_AUTH
        try {
            val passwordText = targetCredential.value.concatToString()
            NativeSshBridge.nativeConnectExec(
                route.target.hostname,
                route.target.port,
                route.target.username,
                passwordText,
                "true",
            )
            this@Libssh2SshSession.route = route
            password = passwordText
            mutableState.value = ConnectionState.Connected("${route.target.username}@${route.target.hostname}")
            mutableStage.value = ConnectionStage.READY
        } catch (error: Throwable) {
            mutableStage.value = ConnectionStage.READY
            mutableState.value = ConnectionState.Error(error.message ?: "SSH 连接失败")
        }
    }

    override suspend fun execute(
        command: String,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): RemoteCommandResult = withContext(Dispatchers.IO) {
        val activeRoute = route
        val activePassword = password
        if (activeRoute == null || activePassword == null) {
            return@withContext RemoteCommandResult(
                exitCode = REMOTE_COMMAND_TIMEOUT_EXIT_CODE,
                stdout = "",
                stderr = "SSH 连接不可用",
            )
        }
        try {
            val raw = NativeSshBridge.nativeConnectExec(
                activeRoute.target.hostname,
                activeRoute.target.port,
                activeRoute.target.username,
                activePassword,
                command,
            )
            val newline = raw.indexOf('\n')
            val exitLine = if (newline >= 0) raw.substring(0, newline) else raw
            val output = if (newline >= 0) raw.substring(newline + 1) else ""
            val exitCode = exitLine.removePrefix("exit=").toIntOrNull() ?: 0
            RemoteCommandResult(exitCode = exitCode, stdout = output, stderr = "")
        } catch (error: Throwable) {
            RemoteCommandResult(
                exitCode = REMOTE_COMMAND_TIMEOUT_EXIT_CODE,
                stdout = "",
                stderr = error.message ?: "native exec failed",
            )
        }
    }

    override suspend fun openTerminal(target: TerminalTarget) {
        mutableTerminalState.value = TerminalChannelState.Error("libssh2 POC 暂不支持交互终端")
    }

    override suspend fun closeTerminal() {
        mutableTerminalState.value = TerminalChannelState.Closed
    }

    override suspend fun write(data: ByteArray) {
        // No persistent channel yet; dropping is acceptable only for the POC.
    }

    override suspend fun resize(columns: Int, rows: Int) = Unit

    override suspend fun disconnect() {
        route = null
        password = null
        mutableState.value = ConnectionState.Disconnected("已断开", DisconnectCause.USER)
        mutableTerminalState.value = TerminalChannelState.Closed
    }

    override fun respondToHostKey(accept: Boolean) {
        mutableHostKeyRequest.value = null
    }

    override fun close() {
        closed = true
        route = null
        password = null
        mutableState.value = ConnectionState.Disconnected("应用已关闭", DisconnectCause.APP_CLOSED)
        mutableTerminalState.value = TerminalChannelState.Closed
    }
}
