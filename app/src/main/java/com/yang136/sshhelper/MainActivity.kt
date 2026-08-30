package com.yang136.sshhelper

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yang136.sshhelper.ui.HostEditorScreen
import com.yang136.sshhelper.ui.HostEditorSeed
import com.yang136.sshhelper.ui.HostsScreen
import com.yang136.sshhelper.ui.LanDiscoveryScreen
import com.yang136.sshhelper.ui.NetworkDiagnosticsScreen
import com.yang136.sshhelper.ui.NETWORK_DIAGNOSTICS_ROUTE_PATTERN
import com.yang136.sshhelper.ui.networkDiagnosticsRoute
import com.yang136.sshhelper.ui.HostWorkspaceScreen
import com.yang136.sshhelper.ui.ActivityScreen
import com.yang136.sshhelper.ui.AppImageBackground
import com.yang136.sshhelper.ui.AppDestination
import com.yang136.sshhelper.ui.imageAwareContentColor
import com.yang136.sshhelper.ui.AppShellScreen
import com.yang136.sshhelper.ui.ImageCropScreen
import com.yang136.sshhelper.ui.SettingsScreen
import com.yang136.sshhelper.ui.SettingsDestination
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
import com.yang136.sshhelper.settings.ImageThemeVariant
import com.yang136.sshhelper.settings.ThemeSource
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
            val imageTheme = settingsViewModel.imageThemeState.collectAsStateWithLifecycle().value
            val imageActive = settings.themeSource == ThemeSource.IMAGE && imageTheme.hasImage
            SshHelperTheme(settings, imageTheme.palette.takeIf { imageActive }) {
                val cropDraft = imageTheme.cropDraft
                if (cropDraft != null) {
                    ImageCropScreen(
                        draft = cropDraft,
                        saving = imageTheme.isImporting,
                        onCancel = settingsViewModel::cancelImageCrop,
                        onConfirm = settingsViewModel::confirmImageCrop,
                    )
                } else {
                    val activeEntry = imageTheme.activeEntry
                    AppImageBackground(
                        bitmap = imageTheme.bitmap.takeIf { imageActive },
                        overlayStrength = settings.imageOverlayStrength,
                        lightTheme = settings.imageThemeVariant == ImageThemeVariant.BRIGHT,
                        focusX = activeEntry?.focusX ?: .5f,
                        focusY = activeEntry?.focusY ?: .5f,
                        zoom = activeEntry?.zoom ?: 1f,
                    ) {
                        Surface(
                            Modifier.fillMaxSize(),
                            color = if (imageActive) Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,
                            contentColor = imageAwareContentColor(),
                        ) {
                    val navController = rememberNavController()
                    val initialDestination = if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) AppDestination.SETTINGS else AppDestination.HOSTS
                    val initialSettingsDestination = SettingsDestination.fromId(intent?.getStringExtra(EXTRA_SETTINGS_SECTION))
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        intent?.removeExtra(EXTRA_OPEN_SETTINGS)
                        intent?.removeExtra(EXTRA_SETTINGS_SECTION)
                    }
                    val terminalPalette = LocalTerminalPalette.current
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        enterTransition = { quickFadeInTransition() },
                        exitTransition = { quickFadeOutTransition() },
                        popEnterTransition = { quickFadeInTransition() },
                        popExitTransition = { quickFadeOutTransition() },
                    ) {
                        composable("home") {
                            AppShellScreen(
                                initialDestination = initialDestination,
                                hosts = { modifier, _ -> HostsScreen(
                                onAdd = { navController.navigate("edit/0") },
                                onDiscover = { navController.navigate("discover") },
                                onDiagnostics = { hostId -> navController.navigate(networkDiagnosticsRoute(hostId)) },
                                onEdit = { navController.navigate("edit/${it.id}") },
                                onOpenHost = { navController.navigate("host/${it.id}") },
                                onConnect = { host ->
                                    sessionsViewModel.create(host, SessionFeature.SHELL)?.let { id ->
                                        navController.navigate("terminal/${host.id}/${id.value}")
                                        true
                                    } ?: false
                                },
                                onForwards = { hostId -> navController.navigate("forwards/$hostId") },
                                onTerminal = { profile ->
                                    sessionsViewModel.openFor(profile, SessionFeature.SHELL)?.let { id ->
                                        navController.navigate("terminal/${profile.id}/${id.value}")
                                        true
                                    } ?: false
                                },
                                onFiles = { profile ->
                                    sessionsViewModel.openFor(profile, SessionFeature.SFTP)?.let { id ->
                                        navController.navigate("files/${id.value}")
                                        true
                                    } ?: false
                                },
                                onNewSession = { profile ->
                                    sessionsViewModel.create(profile, SessionFeature.SHELL)?.let { id ->
                                        navController.navigate("terminal/${profile.id}/${id.value}")
                                        true
                                    } ?: false
                                },
                                sessions = sessions,
                                onOpenSession = { id ->
                                    sessions.firstOrNull { it.id == id }?.let { session ->
                                        if (SessionFeature.SFTP in session.features) {
                                            navController.navigate("files/${id.value}")
                                        } else {
                                            navController.navigate("terminal/${session.profile.id}/${id.value}")
                                        }
                                    }
                                },
                                onCloseSession = sessionsViewModel::close,
                                onCloseHostSessions = sessionsViewModel::closeForHost,
                                onSnippets = { navController.navigate("snippets") },
                                vaultState = vaultState,
                                onVaultClick = {
                                    when (vaultState) {
                                        VaultState.Disabled, is VaultState.Unavailable -> navController.navigate("settings?section=security")
                                        VaultState.Locked -> requestVault(request = { container.credentialVault.unlock() })
                                        is VaultState.Unlocked -> container.credentialVault.lock()
                                    }
                                },
                                onExit = { sessionsViewModel.closeAll(::finish) },
                                modifier = modifier,
                            ) },
                                activity = { modifier, navigate -> ActivityScreen(
                                    hosts = hosts,
                                    sessions = sessions,
                                    onOpenSession = { id ->
                                        sessions.firstOrNull { it.id == id }?.let { session ->
                                            if (SessionFeature.SFTP in session.features) navController.navigate("files/${id.value}")
                                            else navController.navigate("terminal/${session.profile.id}/${id.value}")
                                        }
                                    },
                                    onCloseSession = sessionsViewModel::close,
                                    onOpenHost = { navController.navigate("host/$it") },
                                    onOpenForwards = { navController.navigate("forwards/$it") },
                                    onOpenDocuments = { navigate(AppDestination.SETTINGS) },
                                    onBack = { navigate(AppDestination.HOSTS) },
                                    modifier = modifier,
                                ) },
                                settings = { modifier, onDetailChanged, navigate -> SettingsScreen(
                                    settings = settings,
                                    imageThemeState = imageTheme,
                                    onThemeModeChange = settingsViewModel::setThemeMode,
                                    onThemePresetChange = settingsViewModel::setThemePreset,
                                    onThemeSourceChange = settingsViewModel::setThemeSource,
                                    onImportImageTheme = settingsViewModel::prepareImageTheme,
                                    onImageVariantChange = settingsViewModel::setImageThemeVariant,
                                    onImageOverlayChange = settingsViewModel::setImageOverlayStrength,
                                    onSelectImageTheme = settingsViewModel::selectImageTheme,
                                    onDeleteImageTheme = settingsViewModel::deleteImageTheme,
                                    onClearImageThemeError = settingsViewModel::clearImageThemeError,
                                    onFontSizeChange = settingsViewModel::setTerminalFontSize,
                                    onExtraKeysChange = settingsViewModel::setExtraKeys,
                                    onAiBaseUrlChange = settingsViewModel::setAiBaseUrl,
                                    onAiApiKeyChange = settingsViewModel::setAiApiKey,
                                    onAiModelChange = settingsViewModel::setAiModel,
                                    onAiSendContextChange = settingsViewModel::setAiSendContext,
                                    onAiShowBubbleChange = settingsViewModel::setAiShowBubble,
                                    onForwardReconnectAfterLockChange = settingsViewModel::setForwardReconnectAfterLock,
                                    vaultState = vaultState,
                                    canAuthenticate = container.credentialVault.canAuthenticate(),
                                    onEnableVault = { requestVault(request = { container.credentialVault.enable() }) },
                                    onUnlockVault = { requestVault(request = { container.credentialVault.unlock() }) },
                                    onDisableVault = { requestVault(request = { container.credentialVault.disable() }) },
                                    onLockVault = container.credentialVault::lock,
                                    onClearUnavailableVault = { requestVault(request = { container.credentialVault.clearUnavailableCredentials() }) },
                                    onBack = { navigate(AppDestination.HOSTS) },
                                    initialDestination = initialSettingsDestination,
                                    modifier = modifier,
                                    showRootBack = false,
                                    onDetailChanged = onDetailChanged,
                                ) },
                            )
                        }
                        composable(
                            route = "settings?section={section}",
                            arguments = listOf(
                                navArgument("section") { type = NavType.StringType; defaultValue = "" },
                            ),
                            enterTransition = { topBarEnterTransition() },
                            exitTransition = { quickFadeOutTransition() },
                            popEnterTransition = { quickFadeInTransition() },
                            popExitTransition = { topBarExitTransition() },
                        ) { entry ->
                            val settingsSection = SettingsDestination.fromId(
                                entry.arguments?.getString("section")?.takeIf { it.isNotBlank() },
                            )
                            SettingsScreen(
                                initialDestination = settingsSection,
                                settings = settings,
                                imageThemeState = imageTheme,
                                onThemeModeChange = settingsViewModel::setThemeMode,
                                onThemePresetChange = settingsViewModel::setThemePreset,
                                onThemeSourceChange = settingsViewModel::setThemeSource,
                                onImportImageTheme = settingsViewModel::prepareImageTheme,
                                onImageVariantChange = settingsViewModel::setImageThemeVariant,
                                onImageOverlayChange = settingsViewModel::setImageOverlayStrength,
                                onSelectImageTheme = settingsViewModel::selectImageTheme,
                                onDeleteImageTheme = settingsViewModel::deleteImageTheme,
                                onClearImageThemeError = settingsViewModel::clearImageThemeError,
                                onFontSizeChange = settingsViewModel::setTerminalFontSize,
                                onExtraKeysChange = settingsViewModel::setExtraKeys,
                                onAiBaseUrlChange = settingsViewModel::setAiBaseUrl,
                                onAiApiKeyChange = settingsViewModel::setAiApiKey,
                                onAiModelChange = settingsViewModel::setAiModel,
                                onAiSendContextChange = settingsViewModel::setAiSendContext,
                                onAiShowBubbleChange = settingsViewModel::setAiShowBubble,
                                onForwardReconnectAfterLockChange = settingsViewModel::setForwardReconnectAfterLock,
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
                        composable(
                            route = "snippets",
                            enterTransition = { topBarEnterTransition() },
                            exitTransition = { quickFadeOutTransition() },
                            popEnterTransition = { quickFadeInTransition() },
                            popExitTransition = { topBarExitTransition() },
                        ) {
                            SnippetsScreen(
                                snippets = snippets,
                                hosts = hosts,
                                onSave = snippetsViewModel::save,
                                onDelete = snippetsViewModel::delete,
                                onBack = navController::popBackStack,
                            )
                        }
                        composable(
                            route = "discover",
                            enterTransition = { topBarEnterTransition() },
                            exitTransition = { quickFadeOutTransition() },
                            popEnterTransition = { quickFadeInTransition() },
                            popExitTransition = { topBarExitTransition() },
                        ) {
                            LanDiscoveryScreen(
                                hosts = hosts,
                                onSelect = { name, address, port, existingHostId ->
                                    if (existingHostId != null) {
                                        navController.navigate("edit/$existingHostId")
                                    } else {
                                        navController.navigate(
                                            "edit/0?seedName=${Uri.encode(name)}&seedHost=${Uri.encode(address)}&seedPort=$port",
                                        )
                                    }
                                },
                                onBack = navController::popBackStack,
                            )
                        }
                        composable(
                            route = NETWORK_DIAGNOSTICS_ROUTE_PATTERN,
                            arguments = listOf(navArgument("hostId") { type = NavType.LongType; defaultValue = 0L }),
                            enterTransition = { topBarEnterTransition() },
                            exitTransition = { quickFadeOutTransition() },
                            popEnterTransition = { quickFadeInTransition() },
                            popExitTransition = { topBarExitTransition() },
                        ) { entry ->
                            NetworkDiagnosticsScreen(
                                hostId = entry.arguments?.getLong("hostId") ?: 0L,
                                onBack = navController::popBackStack,
                            )
                        }
                        composable(
                            route = "host/{hostId}",
                            arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
                        ) { entry ->
                            val hostId = entry.arguments?.getLong("hostId") ?: 0L
                            hosts.firstOrNull { it.id == hostId }?.let { host ->
                                HostWorkspaceScreen(
                                    host = host,
                                    sessions = sessions,
                                    onTerminal = { profile ->
                                        sessionsViewModel.openFor(profile, SessionFeature.SHELL)?.let { id ->
                                            navController.navigate("terminal/${profile.id}/${id.value}")
                                            true
                                        } ?: false
                                    },
                                    onFiles = { profile ->
                                        sessionsViewModel.openFor(profile, SessionFeature.SFTP)?.let { id ->
                                            navController.navigate("files/${id.value}")
                                            true
                                        } ?: false
                                    },
                                    onNewSession = { profile ->
                                        sessionsViewModel.create(profile, SessionFeature.SHELL)?.let { id ->
                                            navController.navigate("terminal/${profile.id}/${id.value}")
                                            true
                                        } ?: false
                                    },
                                    onForwards = { navController.navigate("forwards/$it") },
                                    onDiagnostics = { navController.navigate(networkDiagnosticsRoute(it)) },
                                    onEdit = { navController.navigate("edit/${it.id}") },
                                    onOpenSession = { id ->
                                        sessions.firstOrNull { it.id == id }?.let { session ->
                                            if (SessionFeature.SFTP in session.features) navController.navigate("files/${id.value}")
                                            else navController.navigate("terminal/${session.profile.id}/${id.value}")
                                        }
                                    },
                                    onCloseSession = sessionsViewModel::close,
                                    onBack = navController::popBackStack,
                                )
                            }
                        }
                        composable(
                            route = "edit/{hostId}?seedName={seedName}&seedHost={seedHost}&seedPort={seedPort}",
                            arguments = listOf(
                                navArgument("hostId") { type = NavType.LongType },
                                navArgument("seedName") { type = NavType.StringType; defaultValue = "" },
                                navArgument("seedHost") { type = NavType.StringType; defaultValue = "" },
                                navArgument("seedPort") { type = NavType.IntType; defaultValue = 22 },
                            ),
                        ) { entry ->
                            val seedHost = entry.arguments?.getString("seedHost").orEmpty()
                            HostEditorScreen(
                                hostId = entry.arguments?.getLong("hostId") ?: 0,
                                seed = seedHost.takeIf(String::isNotBlank)?.let {
                                    HostEditorSeed(
                                        name = entry.arguments?.getString("seedName").orEmpty(),
                                        hostname = it,
                                        port = entry.arguments?.getInt("seedPort") ?: 22,
                                    )
                                },
                                onUnlockVault = { after -> requestVault({ container.credentialVault.unlock() }, after) },
                                onBack = navController::popBackStack,
                            )
                        }
                        composable(
                            route = "terminal/{hostId}/{sessionId}",
                            arguments = listOf(
                                navArgument("hostId") { type = NavType.LongType },
                                navArgument("sessionId") { type = NavType.StringType },
                            ),
                        ) { entry ->
                            TerminalScreen(
                                initialSessionId = entry.arguments?.getString("sessionId").orEmpty(),
                                hostId = entry.arguments?.getLong("hostId") ?: 0L,
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
                                onBack = navController::popBackStack,
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

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.yang136.sshhelper.OPEN_SETTINGS"
        const val EXTRA_SETTINGS_SECTION = "com.yang136.sshhelper.SETTINGS_SECTION"
    }
}

private const val QUICK_NAV_FADE_MILLIS = 120
private const val TOP_BAR_SLIDE_MILLIS = 220

private fun AnimatedContentTransitionScope<NavBackStackEntry>.quickFadeInTransition(): EnterTransition =
    fadeIn(tween(QUICK_NAV_FADE_MILLIS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.quickFadeOutTransition(): ExitTransition =
    fadeOut(tween(QUICK_NAV_FADE_MILLIS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.topBarEnterTransition(): EnterTransition =
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Down,
        animationSpec = tween(TOP_BAR_SLIDE_MILLIS),
    ) + fadeIn(tween(QUICK_NAV_FADE_MILLIS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.topBarExitTransition(): ExitTransition =
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Up,
        animationSpec = tween(TOP_BAR_SLIDE_MILLIS),
    ) + fadeOut(tween(QUICK_NAV_FADE_MILLIS))
