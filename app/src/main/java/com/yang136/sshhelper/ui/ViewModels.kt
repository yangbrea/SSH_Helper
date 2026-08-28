package com.yang136.sshhelper.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yang136.sshhelper.AppContainer
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.CommandSnippet
import com.yang136.sshhelper.data.ProxyType
import com.yang136.sshhelper.data.validationError
import com.yang136.sshhelper.data.validateJumpRoute
import com.yang136.sshhelper.data.validateProxy
import com.yang136.sshhelper.data.TransferStatus
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.SessionFeature
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.settings.ExtraKeyId
import com.yang136.sshhelper.settings.ThemeSource
import com.yang136.sshhelper.settings.ImageThemeVariant
import com.yang136.sshhelper.settings.defaultImageOverlayStrength
import com.yang136.sshhelper.theme.ImageFocalTransform
import com.yang136.sshhelper.theme.ImageThemeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class HostsViewModel(private val container: AppContainer) : ViewModel() {
    val hosts = container.hostRepository.hosts.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val mutableDeleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = mutableDeleteError.asStateFlow()

    fun delete(profile: HostProfile) = viewModelScope.launch {
        runCatching {
            container.transferManager.cancelForHost(profile.id)
            container.documentAccessManager.disable(profile.id)
            container.hostRepository.delete(profile)
        }.onFailure { mutableDeleteError.value = it.message ?: "删除失败" }
    }

    fun clearDeleteError() {
        mutableDeleteError.value = null
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { HostsViewModel(container) }
    }
}

class SessionsViewModel(private val container: AppContainer) : ViewModel() {
    private val manager = container.sessionManager
    val sessions: StateFlow<List<ManagedSessionState>> = manager.sessions

    fun create(profile: HostProfile, feature: SessionFeature = SessionFeature.SHELL): SessionId? =
        manager.create(profile, feature)
    fun output(id: SessionId) = manager.output(id)
    fun recentOutput(id: SessionId, maxBytes: Int): ByteArray = manager.recentOutput(id, maxBytes)
    fun aiState(id: SessionId) = container.aiAgentManager.state(id)
    fun sendAi(id: SessionId, prompt: String, settings: AppSettings) = viewModelScope.launch {
        container.aiAgentManager.send(id, prompt, settings)
    }
    fun confirmAiCommand(id: SessionId, suggestionId: String) = viewModelScope.launch {
        container.aiAgentManager.confirmCommand(id, suggestionId)
    }
    fun cancelAiGeneration(id: SessionId) = container.aiAgentManager.cancelGeneration(id)
    fun interruptAiCommand(id: SessionId) = container.aiAgentManager.interruptCommand(id)
    fun stopAiWaiting(id: SessionId) = container.aiAgentManager.stopWaiting(id)
    fun analyzePartialAiOutput(id: SessionId, suggestionId: String) =
        container.aiAgentManager.analyzePartial(id, suggestionId)
    fun clearAi(id: SessionId) = container.aiAgentManager.clear(id)
    fun enableFeature(id: SessionId, feature: SessionFeature) = manager.enableFeature(id, feature)
    fun connect(id: SessionId, credential: Credential, remember: Boolean) = viewModelScope.launch { manager.connect(id, credential, remember) }
    fun send(id: SessionId, bytes: ByteArray) = viewModelScope.launch { manager.write(id, bytes) }
    private val resizeJobs = mutableMapOf<SessionId, Job>()
    private val lastPtySizes = mutableMapOf<SessionId, Pair<Int, Int>>()

    fun resize(id: SessionId, columns: Int, rows: Int) {
        val size = normalizePtySize(columns, rows)
        if (lastPtySizes[id] == size) return
        resizeJobs.remove(id)?.cancel()
        resizeJobs[id] = viewModelScope.launch {
            delay(PTY_RESIZE_DEBOUNCE_MS)
            lastPtySizes[id] = size
            manager.resize(id, size.first, size.second)
            resizeJobs.remove(id)
        }
    }
    fun reconnect(id: SessionId) = viewModelScope.launch { manager.reconnect(id) }
    fun disconnect(id: SessionId) = viewModelScope.launch { manager.disconnect(id) }
    fun cancelReconnect(id: SessionId) = viewModelScope.launch { manager.cancelReconnect(id) }
    fun close(id: SessionId, after: (() -> Unit)? = null) = viewModelScope.launch {
        resizeJobs.remove(id)?.cancel()
        lastPtySizes.remove(id)
        // Closing one channel must never cancel transfers: they resolve their own SSH session
        // and are decoupled from any single terminal or file UI lifetime.
        container.aiAgentManager.clear(id)
        manager.close(id)
        after?.invoke()
    }
    fun closeAll(after: (() -> Unit)? = null) = viewModelScope.launch {
        resizeJobs.values.forEach(Job::cancel)
        resizeJobs.clear()
        lastPtySizes.clear()
        container.transferManager.jobs.value.filter { it.status in setOf(TransferStatus.QUEUED, TransferStatus.RUNNING, TransferStatus.PAUSED, TransferStatus.WAITING_NETWORK, TransferStatus.WAITING_UNLOCK) }
            .forEach { container.transferManager.cancel(it.id) }
        manager.sessions.value.forEach { container.aiAgentManager.clear(it.id) }
        manager.closeAll()
        after?.invoke()
    }
    fun respondToHostKey(id: SessionId, accept: Boolean) = manager.respondToHostKey(id, accept)
    fun forgetChangedHostKey(id: SessionId) = viewModelScope.launch { manager.forgetChangedHostKey(id) }
    fun closeForHost(hostId: Long, after: () -> Unit) = viewModelScope.launch {
        manager.sessions.value.filter { it.profile.id == hostId }.forEach {
            container.aiAgentManager.clear(it.id)
            manager.close(it.id)
        }
        after()
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { SessionsViewModel(container) }
    }
}

internal const val PTY_RESIZE_DEBOUNCE_MS = 100L
internal fun normalizePtySize(columns: Int, rows: Int): Pair<Int, Int> =
    columns.coerceIn(2, 500) to rows.coerceIn(2, 300)

class SnippetsViewModel(private val container: AppContainer) : ViewModel() {
    val snippets = container.snippetRepository.snippets.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun save(snippet: CommandSnippet, onResult: (String?) -> Unit = {}) = viewModelScope.launch {
        val error = runCatching { container.snippetRepository.save(snippet) }.exceptionOrNull()?.message
        onResult(error)
    }

    fun delete(snippet: CommandSnippet) = viewModelScope.launch { container.snippetRepository.delete(snippet) }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { SnippetsViewModel(container) }
    }
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings = container.settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )
    val imageThemeState = container.imageThemeRepository.state

    init {
        viewModelScope.launch {
            val stored = container.settingsRepository.settings.first()
            container.imageThemeRepository.initialize(stored.imageThemeVariant, stored.imageOverlayStrength)
            if (stored.themeSource == ThemeSource.IMAGE && !imageThemeState.value.hasImage) {
                container.settingsRepository.setThemeSource(ThemeSource.PRESET)
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        container.settingsRepository.setThemeMode(mode)
    }

    fun setThemePreset(preset: ThemePreset) = viewModelScope.launch {
        container.settingsRepository.setThemePreset(preset)
        container.settingsRepository.setThemeSource(ThemeSource.PRESET)
    }

    fun setThemeSource(source: ThemeSource) = viewModelScope.launch {
        if (source != ThemeSource.IMAGE || imageThemeState.value.hasImage) {
            container.settingsRepository.setThemeSource(source)
        }
    }

    fun prepareImageTheme(uri: Uri, targetAspectRatio: Float) = viewModelScope.launch {
        container.imageThemeRepository.prepareCrop(uri, targetAspectRatio)
    }

    fun confirmImageCrop(focal: ImageFocalTransform) = viewModelScope.launch {
        container.imageThemeRepository.commitCrop(focal)?.let { entry ->
            applyImageEntry(entry)
            container.settingsRepository.setThemeSource(ThemeSource.IMAGE)
        }
    }

    fun cancelImageCrop() = container.imageThemeRepository.cancelCrop()

    fun selectImageTheme(entryId: String) = viewModelScope.launch {
        container.imageThemeRepository.select(entryId)?.let { entry ->
            applyImageEntry(entry)
            container.settingsRepository.setThemeSource(ThemeSource.IMAGE)
        }
    }

    fun deleteImageTheme(entryId: String) = viewModelScope.launch {
        val next = container.imageThemeRepository.delete(entryId)
        if (next == null) container.settingsRepository.setThemeSource(ThemeSource.PRESET)
        else {
            applyImageEntry(next)
            container.settingsRepository.setThemeSource(ThemeSource.IMAGE)
        }
    }

    fun setImageThemeVariant(variant: ImageThemeVariant) = viewModelScope.launch {
        val strength = defaultImageOverlayStrength(variant)
        container.settingsRepository.setImageThemeVariant(variant)
        container.settingsRepository.setImageOverlayStrength(strength)
        container.imageThemeRepository.updateActiveAppearance(variant, strength)
    }

    fun setImageOverlayStrength(strength: Float) = viewModelScope.launch {
        container.settingsRepository.setImageOverlayStrength(strength)
        container.imageThemeRepository.updateActiveAppearance(settings.value.imageThemeVariant, strength)
    }

    fun clearImageThemeError() = container.imageThemeRepository.clearError()

    private suspend fun applyImageEntry(entry: ImageThemeEntry) {
        container.settingsRepository.setImageThemeVariant(entry.variant)
        container.settingsRepository.setImageOverlayStrength(entry.overlayStrength)
    }

    fun setTerminalFontSize(size: Int) = viewModelScope.launch {
        container.settingsRepository.setTerminalFontSize(size)
    }

    fun setExtraKeys(keys: List<ExtraKeyId>) = viewModelScope.launch {
        container.settingsRepository.setExtraKeys(keys)
    }

    fun setAiBaseUrl(url: String) = viewModelScope.launch {
        container.settingsRepository.setAiBaseUrl(url)
    }

    fun setAiApiKey(key: String) = viewModelScope.launch {
        container.settingsRepository.setAiApiKey(key)
    }

    fun setAiModel(model: String) = viewModelScope.launch {
        container.settingsRepository.setAiModel(model)
    }

    fun setAiSendContext(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setAiSendContext(enabled)
    }

    fun setAiShowBubble(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setAiShowBubble(enabled)
    }

    fun setForwardReconnectAfterLock(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setForwardReconnectAfterLock(enabled)
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { SettingsViewModel(container) }
    }
}

data class EditorState(
    val id: Long = 0,
    val name: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val rememberCredential: Boolean = false,
    val password: String = "",
    val privateKeyName: String? = null,
    val autoReconnect: Boolean = false,
    val jumpHostId: Long? = null,
    val proxyType: ProxyType? = null,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val passphrase: String = "",
    val error: String? = null,
    val loaded: Boolean = false,
    val isDirty: Boolean = false,
)

data class GeneratedKeyState(
    val publicKey: String,
    val fingerprint: String,
)

internal fun EditorState.applyUserEdit(transform: (EditorState) -> EditorState): EditorState =
    transform(this).copy(error = null, isDirty = true)

class HostEditorViewModel(
    private val container: AppContainer,
    private val hostId: Long,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = mutableState.asStateFlow()
    val hosts: StateFlow<List<HostProfile>> = container.hostRepository.hosts.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    private val mutableGeneratedKey = MutableStateFlow<GeneratedKeyState?>(null)
    val generatedKey: StateFlow<GeneratedKeyState?> = mutableGeneratedKey.asStateFlow()
    val documentAccessEnabled: StateFlow<Boolean> = container.documentAccessManager.roots
        .map { roots -> roots.any { it.hostId == hostId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var privateKeyBytes: ByteArray? = null

    init {
        viewModelScope.launch {
            val host = if (hostId == 0L) null else container.hostRepository.getHost(hostId)
            mutableState.value = host?.let {
                EditorState(
                    id = it.id,
                    name = it.name,
                    hostname = it.hostname,
                    port = it.port.toString(),
                    username = it.username,
                    authType = it.authType,
                    rememberCredential = it.rememberCredential,
                    privateKeyName = it.privateKeyName,
                    autoReconnect = it.autoReconnect,
                    jumpHostId = it.jumpHostId,
                    proxyType = it.proxyType,
                    proxyHost = it.proxyHost.orEmpty(),
                    proxyPort = it.proxyPort?.toString().orEmpty(),
                    proxyUsername = it.proxyUsername.orEmpty(),
                    loaded = true,
                )
            } ?: EditorState(loaded = true)
        }
    }

    fun update(transform: (EditorState) -> EditorState) {
        mutableState.value = mutableState.value.applyUserEdit(transform)
    }

    fun setPrivateKey(name: String, bytes: ByteArray) {
        privateKeyBytes?.fill(0)
        privateKeyBytes = bytes
        update { it.copy(privateKeyName = name) }
    }

    fun generateKeyPair() = viewModelScope.launch(Dispatchers.Default) {
        val generated = com.yang136.sshhelper.security.KeyGenerator.generateEd25519()
        withContext(Dispatchers.Main) {
            privateKeyBytes?.fill(0)
            privateKeyBytes = generated.privateKey
            update {
                it.copy(
                    authType = AuthType.PRIVATE_KEY,
                    privateKeyName = "生成的 ed25519 密钥",
                    error = null,
                )
            }
            mutableGeneratedKey.value = GeneratedKeyState(generated.publicKey, generated.fingerprint)
        }
    }

    fun clearGeneratedKey() {
        mutableGeneratedKey.value = null
    }

    fun setDocumentAccess(enabled: Boolean) = viewModelScope.launch {
        runCatching {
            if (enabled) container.documentAccessManager.enable(hostId)
            else container.documentAccessManager.disable(hostId)
        }.onFailure { failure ->
            mutableState.value = mutableState.value.copy(
                error = if (enabled) {
                    "无法开启系统文件访问：${failure.message ?: "未知错误"}"
                } else {
                    "无法关闭系统文件访问：${failure.message ?: "未知错误"}"
                },
            )
        }
    }

    suspend fun save(): Long? {
        val value = mutableState.value
        val profile = HostProfile(
            id = value.id,
            name = value.name,
            hostname = value.hostname,
            port = value.port.toIntOrNull() ?: 0,
            username = value.username,
            authType = value.authType,
            rememberCredential = value.rememberCredential,
            privateKeyName = value.privateKeyName,
            autoReconnect = value.autoReconnect,
            jumpHostId = value.jumpHostId,
            proxyType = value.proxyType,
            proxyHost = value.proxyHost,
            proxyPort = value.proxyPort.toIntOrNull(),
            proxyUsername = value.proxyUsername,
        )
        profile.validationError()?.let {
            mutableState.value = value.copy(error = it)
            return null
        }
        validateJumpRoute(profile, hosts.value)?.let {
            mutableState.value = value.copy(error = it)
            return null
        }
        validateProxy(profile)?.let {
            mutableState.value = value.copy(error = it)
            return null
        }
        val credential = if (!value.rememberCredential) null else when (value.authType) {
            AuthType.PASSWORD -> if (value.password.isNotEmpty()) Credential.Password(value.password.toCharArray()) else null
            AuthType.PRIVATE_KEY -> privateKeyBytes?.let {
                Credential.PrivateKey(it, value.passphrase.takeIf(String::isNotEmpty)?.toCharArray(), value.privateKeyName)
            }
        }
        if (value.rememberCredential && value.id == 0L && credential == null) {
            mutableState.value = value.copy(error = if (value.authType == AuthType.PASSWORD) "请输入要保存的密码" else "请选择私钥文件")
            return null
        }
        return runCatching {
            container.hostRepository.save(profile, credential, value.proxyPassword.takeIf(String::isNotEmpty))
        }
            .mapCatching { savedId ->
                val revoked = container.documentAccessManager.refreshAffected(savedId)
                if (revoked.isNotEmpty()) {
                    error("主机已保存，但连接参数或凭据无法刷新，系统文件访问已撤销；请确认指纹和已保存凭据后重新开启")
                }
                savedId
            }
            .onFailure { mutableState.value = value.copy(error = "保存失败：${it.message ?: "未知错误"}") }
            .getOrNull()
    }

    override fun onCleared() {
        privateKeyBytes?.fill(0)
    }

    companion object {
        fun factory(container: AppContainer, hostId: Long) = simpleFactory { HostEditorViewModel(container, hostId) }
    }
}

private fun <T : ViewModel> simpleFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
