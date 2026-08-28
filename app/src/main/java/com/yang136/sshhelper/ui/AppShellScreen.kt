package com.yang136.sshhelper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

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
    Scaffold(
        bottomBar = {
            if (!detailVisible) NavigationBar {
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
        val modifier = Modifier.padding(padding)
        Box {
            stateHolder.SaveableStateProvider(selected.id) {
                when (selected) {
                    AppDestination.HOSTS -> hosts(modifier, navigate)
                    AppDestination.ACTIVITY -> activity(modifier, navigate)
                    AppDestination.SETTINGS -> settings(modifier, { detailVisible = it }, navigate)
                }
            }
        }
    }
}

private fun AppDestination.icon() = when (this) {
    AppDestination.HOSTS -> Icons.Default.Computer
    AppDestination.ACTIVITY -> Icons.Default.Timeline
    AppDestination.SETTINGS -> Icons.Default.Settings
}
