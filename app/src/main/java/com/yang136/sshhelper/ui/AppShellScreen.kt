package com.yang136.sshhelper.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yang136.sshhelper.ui.adaptive.SshLayoutMode
import com.yang136.sshhelper.ui.adaptive.layoutMode

@Composable
fun AppShellScreen(
    initialDestination: AppDestination = AppDestination.HOSTS,
    hosts: @Composable (Modifier, (AppDestination) -> Unit) -> Unit,
    activity: @Composable (Modifier, (AppDestination) -> Unit) -> Unit,
    settings: @Composable (Modifier, (Boolean) -> Unit, (AppDestination) -> Unit) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf(initialDestination.id) }
    var detailVisible by rememberSaveable { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()
    val selected = AppDestination.fromId(selectedId)
    val navigate: (AppDestination) -> Unit = { destination ->
        selectedId = destination.id
        detailVisible = false
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (layoutMode() == SshLayoutMode.LANDSCAPE) {
            Row(Modifier.fillMaxSize()) {
                if (!detailVisible) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = imageAwareContainerColor(MaterialTheme.colorScheme.surfaceContainer, .9f),
                        contentColor = imageAwareContentColor(),
                    ) {
                        AppDestination.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = selected == destination,
                                onClick = { navigate(destination) },
                                icon = { Icon(destination.icon(), null) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    AppShellContent(selected, Modifier, stateHolder, navigate, { detailVisible = it }, hosts, activity, settings)
                }
            }
        } else {
            Scaffold(
                containerColor = imageAwareScaffoldColor(),
                contentColor = imageAwareContentColor(),
                bottomBar = {
                    if (!detailVisible) NavigationBar(
                        containerColor = imageAwareContainerColor(MaterialTheme.colorScheme.surfaceContainer, .9f),
                        contentColor = imageAwareContentColor(),
                    ) {
                        AppDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = selected == destination,
                                onClick = { navigate(destination) },
                                icon = { Icon(destination.icon(), null) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    AppShellContent(selected, Modifier, stateHolder, navigate, { detailVisible = it }, hosts, activity, settings)
                }
            }
        }
    }
}

@Composable
private fun AppShellContent(
    selected: AppDestination,
    screenModifier: Modifier,
    stateHolder: SaveableStateHolder,
    navigate: (AppDestination) -> Unit,
    onDetailVisibleChange: (Boolean) -> Unit,
    hosts: @Composable (Modifier, (AppDestination) -> Unit) -> Unit,
    activity: @Composable (Modifier, (AppDestination) -> Unit) -> Unit,
    settings: @Composable (Modifier, (Boolean) -> Unit, (AppDestination) -> Unit) -> Unit,
) {
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) {
                AnimatedContentTransitionScope.SlideDirection.Left
            } else {
                AnimatedContentTransitionScope.SlideDirection.Right
            }
            (slideIntoContainer(direction, tween(BOTTOM_NAV_SLIDE_MILLIS)) +
                fadeIn(tween(QUICK_FADE_MILLIS))) togetherWith
                (slideOutOfContainer(direction, tween(BOTTOM_NAV_SLIDE_MILLIS)) +
                    fadeOut(tween(QUICK_FADE_MILLIS))) using
                SizeTransform(clip = true)
        },
        label = "bottom-navigation-content",
    ) { destination ->
        stateHolder.SaveableStateProvider(destination.id) {
            when (destination) {
                AppDestination.HOSTS -> hosts(screenModifier, navigate)
                AppDestination.ACTIVITY -> activity(screenModifier, navigate)
                AppDestination.SETTINGS -> settings(screenModifier, onDetailVisibleChange, navigate)
            }
        }
    }
}

private const val BOTTOM_NAV_SLIDE_MILLIS = 190
private const val QUICK_FADE_MILLIS = 80

private fun AppDestination.icon() = when (this) {
    AppDestination.HOSTS -> Icons.Default.Computer
    AppDestination.ACTIVITY -> Icons.Default.Timeline
    AppDestination.SETTINGS -> Icons.Default.Settings
}
