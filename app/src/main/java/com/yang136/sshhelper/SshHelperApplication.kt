package com.yang136.sshhelper

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.yang136.sshhelper.data.AppDatabase
import com.yang136.sshhelper.data.HostRepository
import com.yang136.sshhelper.data.SnippetRepository
import com.yang136.sshhelper.security.AndroidCredentialVault
import com.yang136.sshhelper.settings.DataStoreSettingsRepository
import com.yang136.sshhelper.ssh.DefaultSessionManager
import com.yang136.sshhelper.sftp.DefaultTransferManager
import com.yang136.sshhelper.ssh.DefaultForwardManager
import com.yang136.sshhelper.sftp.SftpRepository
import androidx.work.Configuration

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

class AppContainer(application: Application) {
    val database = AppDatabase.create(application)
    val credentialVault = AndroidCredentialVault(application, database)
    val hostRepository = HostRepository(database, credentialVault)
    val snippetRepository = SnippetRepository(database)
    val settingsRepository = DataStoreSettingsRepository(application)
    val sessionManager = DefaultSessionManager(application, hostRepository, database.knownHostDao(), credentialVault = credentialVault)
    val transferManager = DefaultTransferManager(application, database, hostRepository, sessionManager, credentialVault)
    val forwardManager = DefaultForwardManager(application, database, hostRepository, sessionManager, credentialVault)
    val sftpRepository = SftpRepository(application, database)
}
