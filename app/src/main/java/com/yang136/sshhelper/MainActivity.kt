package com.yang136.sshhelper

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yang136.sshhelper.ui.HostEditorScreen
import com.yang136.sshhelper.ui.HostsScreen
import com.yang136.sshhelper.ui.SettingsScreen
import com.yang136.sshhelper.ui.SettingsViewModel
import com.yang136.sshhelper.ui.SessionsViewModel
import com.yang136.sshhelper.ui.SnippetsViewModel
import com.yang136.sshhelper.ui.HostsViewModel
import com.yang136.sshhelper.ui.SnippetsScreen
import com.yang136.sshhelper.ui.TerminalScreen
import com.yang136.sshhelper.ui.SftpScreen
import com.yang136.sshhelper.ui.SftpViewModel
import com.yang136.sshhelper.ui.ForwardScreen
import com.yang136.sshhelper.security.VaultAction
import com.yang136.sshhelper.security.VaultResult
import com.yang136.sshhelper.security.VaultState
import com.yang136.sshhelper.ssh.SessionFeature
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ui.theme.LocalTerminalPalette
import com.yang136.sshhelper.ui.theme.SshHelperTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private lateinit var biometricPrompt: BiometricPrompt
    private var pendingVaultAction: VaultAction? = null
    private var pendingVaultSuccess: (() -> Unit)? = null
    private var vaultMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val action = pendingVaultAction ?: return
                    val onSuccess = pendingVaultSuccess
                    pendingVaultAction = null
                    pendingVaultSuccess = null
                    lifecycleScope.launch {
                        handleVaultResult(container.credentialVault.completeAuthentication(action), onSuccess)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    pendingVaultAction = null
                    pendingVaultSuccess = null
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) vaultMessage = errString.toString()
                }
            },
        )
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory((application as SshHelperApplication).container),
            )
            val sessionsViewModel: SessionsViewModel = viewModel(
                factory = SessionsViewModel.factory((application as SshHelperApplication).container),
            )
            val snippetsViewModel: SnippetsViewModel = viewModel(
                factory = SnippetsViewModel.factory((application as SshHelperApplication).container),
            )
            val hostsViewModel: HostsViewModel = viewModel(
                factory = HostsViewModel.factory((application as SshHelperApplication).container),
            )
            val settings = settingsViewModel.settings.collectAsStateWithLifecycle().value
            val sessions = sessionsViewModel.sessions.collectAsStateWithLifecycle().value
            val snippets = snippetsViewModel.snippets.collectAsStateWithLifecycle().value
            val hosts = hostsViewModel.hosts.collectAsStateWithLifecycle().value
            val vaultState = container.credentialVault.state.collectAsStateWithLifecycle().value
            SshHelperTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val terminalPalette = LocalTerminalPalette.current
                    NavHost(navController = navController, startDestination = "hosts") {
                        composable("hosts") {
                            HostsScreen(
                                onAdd = { navController.navigate("edit/0") },
                                onEdit = { navController.navigate("edit/${it.id}") },
                                onConnect = { host ->
                                    sessionsViewModel.create(host, SessionFeature.SHELL)?.let { id ->
                                        navController.navigate("terminal/${id.value}")
                                        true
                                    } ?: false
                                },
                                onFiles = { host ->
                                    sessionsViewModel.create(host, SessionFeature.SFTP)?.let { id ->
                                        navController.navigate("files/${id.value}")
                                        true
                                    } ?: false
                                },
                                onForwards = { hostId -> navController.navigate("forwards/$hostId") },
                                sessions = sessions,
                                onCloseHostSessions = sessionsViewModel::closeForHost,
                                onSettings = { navController.navigate("settings") },
                                onSnippets = { navController.navigate("snippets") },
                                vaultState = vaultState,
                                onVaultClick = {
                                    when (vaultState) {
                                        VaultState.Disabled, is VaultState.Unavailable -> navController.navigate("settings")
                                        VaultState.Locked -> requestVault(request = { container.credentialVault.unlock() })
                                        is VaultState.Unlocked -> container.credentialVault.lock()
                                    }
                                },
                                onExit = { sessionsViewModel.closeAll(::finish) },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                settings = settings,
                                onThemeModeChange = settingsViewModel::setThemeMode,
                                onThemePresetChange = settingsViewModel::setThemePreset,
                                onFontSizeChange = settingsViewModel::setTerminalFontSize,
                                onExtraKeysChange = settingsViewModel::setExtraKeys,
                                onAiBaseUrlChange = settingsViewModel::setAiBaseUrl,
                                onAiApiKeyChange = settingsViewModel::setAiApiKey,
                                onAiModelChange = settingsViewModel::setAiModel,
                                onAiSendContextChange = settingsViewModel::setAiSendContext,
                                onAiShowBubbleChange = settingsViewModel::setAiShowBubble,
                                vaultState = vaultState,
                                canAuthenticate = container.credentialVault.canAuthenticate(),
                                onEnableVault = { requestVault(request = { container.credentialVault.enable() }) },
                                onUnlockVault = { requestVault(request = { container.credentialVault.unlock() }) },
                                onDisableVault = { requestVault(request = { container.credentialVault.disable() }) },
                                onLockVault = container.credentialVault::lock,
                                onClearUnavailableVault = { requestVault(request = { container.credentialVault.clearUnavailableCredentials() }) },
                                onBack = navController::popBackStack,
                            )
                        }
                        composable("snippets") {
                            SnippetsScreen(
                                snippets = snippets,
                                hosts = hosts,
                                onSave = snippetsViewModel::save,
                                onDelete = snippetsViewModel::delete,
                                onBack = navController::popBackStack,
                            )
                        }
                        composable(
                            route = "edit/{hostId}",
                            arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
                        ) {
                            HostEditorScreen(
                                hostId = it.arguments?.getLong("hostId") ?: 0,
                                onUnlockVault = { after -> requestVault({ container.credentialVault.unlock() }, after) },
                                onBack = navController::popBackStack,
                            )
                        }
                        composable(
                            route = "terminal/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                        ) { entry ->
                            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                            TerminalScreen(
                                initialSessionId = sessionId,
                                sessionsViewModel = sessionsViewModel,
                                snippets = snippets,
                                settings = settings,
                                terminalPalette = terminalPalette,
                                onFontSizeChange = settingsViewModel::setTerminalFontSize,
                                onManageSnippets = { navController.navigate("snippets") },
                                onOpenForwards = { hostId -> navController.navigate("forwards/$hostId") },
                                onOpenSettings = { navController.navigate("settings") },
                                onUnlockVault = { requestVault(request = { container.credentialVault.unlock() }) },
                                onBack = navController::popBackStack,
                            )
                        }
                        composable(
                            route = "files/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                        ) { entry ->
                            val id = SessionId(entry.arguments?.getString("sessionId").orEmpty())
                            val sftpViewModel: SftpViewModel = viewModel(
                                key = "sftp-${id.value}",
                                factory = SftpViewModel.factory(container, id),
                            )
                            SftpScreen(
                                viewModel = sftpViewModel,
                                onBack = {
                                    sessionsViewModel.close(id)
                                    navController.popBackStack()
                                },
                                onUnlockVault = { requestVault(request = { container.credentialVault.unlock() }) },
                            )
                        }
                        composable(
                            route = "forwards/{hostId}",
                            arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
                        ) { entry ->
                            ForwardScreen(
                                hostId = entry.arguments?.getLong("hostId") ?: 0L,
                                onBack = navController::popBackStack,
                            )
                        }
                    }
                    vaultMessage?.let { message ->
                        AlertDialog(
                            onDismissRequest = { vaultMessage = null },
                            title = { Text("保险库") },
                            text = { Text(message) },
                            confirmButton = { TextButton(onClick = { vaultMessage = null }) { Text("知道了") } },
                        )
                    }
                }
            }
        }
    }

    private val container: AppContainer
        get() = (application as SshHelperApplication).container

    private fun requestVault(request: suspend () -> VaultResult, onSuccess: (() -> Unit)? = null) {
        lifecycleScope.launch { handleVaultResult(request(), onSuccess) }
    }

    private fun handleVaultResult(result: VaultResult, onSuccess: (() -> Unit)? = null) {
        when (result) {
            VaultResult.Success -> onSuccess?.invoke()
            is VaultResult.Failure -> vaultMessage = result.message
            is VaultResult.AuthenticationRequired -> {
                pendingVaultAction = result.action
                pendingVaultSuccess = onSuccess
                biometricPrompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle("解锁 SSH Helper 凭据")
                        .setSubtitle("请使用强生物识别或系统锁屏凭据")
                        .apply {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                setAllowedAuthenticators(
                                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                setDeviceCredentialAllowed(true)
                            }
                        }
                        .build(),
                )
            }
        }
    }
}
