package com.yang136.sshhelper

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.yang136.sshhelper.data.AppDatabase
import com.yang136.sshhelper.data.HostRepository
import com.yang136.sshhelper.data.SnippetRepository
import com.yang136.sshhelper.data.ConfigTransferManager
import com.yang136.sshhelper.ai.AiAgentManager
import com.yang136.sshhelper.ai.OkHttpAiClient
import com.yang136.sshhelper.ai.TerminalCommandRunner
import com.yang136.sshhelper.security.AndroidCredentialVault
import com.yang136.sshhelper.settings.DataStoreSettingsRepository
import com.yang136.sshhelper.ssh.DefaultSessionManager
import com.yang136.sshhelper.sftp.DefaultTransferManager
import com.yang136.sshhelper.ssh.DefaultForwardManager
import com.yang136.sshhelper.sftp.SftpRepository
import androidx.work.Configuration
import com.yang136.sshhelper.documents.DocumentAccessManager
import com.yang136.sshhelper.documents.SshDocumentsBackend
import com.yang136.sshhelper.preview.PreviewCache
import com.yang136.sshhelper.theme.ImageThemeRepository
import com.yang136.sshhelper.discovery.AndroidArpTableReader
import com.yang136.sshhelper.discovery.AndroidMdnsDiscovery
import com.yang136.sshhelper.discovery.AndroidNetworkEnvironment
import com.yang136.sshhelper.discovery.AndroidTcpServiceProbe
import com.yang136.sshhelper.diagnostics.AndroidDiagnosticBackend
import com.yang136.sshhelper.diagnostics.DefaultNetworkDiagnosticsEngine
import com.yang136.sshhelper.discovery.AndroidSsdpDiscovery
import com.yang136.sshhelper.discovery.AndroidDeviceDescriptionRepository
import com.yang136.sshhelper.discovery.AssetMacVendorResolver
import com.yang136.sshhelper.discovery.DefaultLanDiscoveryEngine
import com.yang136.sshhelper.diagnosticlog.DiagnosticLogRepository
import com.yang136.sshhelper.scanner.AndroidPortScanBackend
import com.yang136.sshhelper.scanner.DefaultPortScanner

class SshHelperApplication : Application(), DefaultLifecycleObserver, Configuration.Provider {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) = container.credentialVault.onAppForegrounded()
    override fun onStop(owner: LifecycleOwner) = container.credentialVault.onAppBackgrounded()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(13_000, 14_000).build()
}

class AppContainer(val application: Application) {
    val database = AppDatabase.create(application)
    val credentialVault = AndroidCredentialVault(application, database)
    val diagnosticLogRepository = DiagnosticLogRepository(database.diagnosticLogDao())
    val hostRepository = HostRepository(database, credentialVault)
    val configTransferManager = ConfigTransferManager(database)
    val documentAccessManager = DocumentAccessManager(application, database, hostRepository)
    val documentsBackend = SshDocumentsBackend(application, database, documentAccessManager, diagnosticLogRepository)
    val snippetRepository = SnippetRepository(database)
    val settingsRepository = DataStoreSettingsRepository(application)
    val imageThemeRepository = ImageThemeRepository(application)
    val sessionManager = DefaultSessionManager(
        application,
        hostRepository,
        database.knownHostDao(),
        diagnosticSink = diagnosticLogRepository,
        credentialVault = credentialVault,
        settings = settingsRepository,
    )
    val aiClient = OkHttpAiClient()
    val terminalCommandRunner = TerminalCommandRunner(sessionManager)
    val aiAgentManager = AiAgentManager(
        sessions = sessionManager.sessions,
        client = aiClient,
        commandRunner = terminalCommandRunner,
        recentOutput = sessionManager::recentOutput,
    )
    val transferManager = DefaultTransferManager(application, database, hostRepository, sessionManager, credentialVault)
    val forwardManager = DefaultForwardManager(application, database, hostRepository, sessionManager, credentialVault)
    val previewCache = PreviewCache(application)
    val networkEnvironment = AndroidNetworkEnvironment(application)
    val deviceDescriptionRepository = AndroidDeviceDescriptionRepository(networkEnvironment)
    val lanDiscoveryEngine = DefaultLanDiscoveryEngine(
        networkEnvironment = networkEnvironment,
        tcpProbe = AndroidTcpServiceProbe(networkEnvironment),
        mdnsDiscovery = AndroidMdnsDiscovery(application, networkEnvironment),
        ssdpDiscovery = AndroidSsdpDiscovery(networkEnvironment),
        arpTableReader = AndroidArpTableReader(),
        macVendorResolver = AssetMacVendorResolver(application),
    )
    val networkDiagnosticsEngine = DefaultNetworkDiagnosticsEngine(AndroidDiagnosticBackend(application))
    val portScanner = DefaultPortScanner(AndroidPortScanBackend(application), diagnosticLogRepository)

    init {
        // 凭据租约按"活动转发"判定：会话管理器查询转发管理器当前活跃转发的会话。
        sessionManager.forwardActivityProvider = { forwardManager.activeForwardSessionIds() }
    }
    val sftpRepository = SftpRepository(application, database)
}
