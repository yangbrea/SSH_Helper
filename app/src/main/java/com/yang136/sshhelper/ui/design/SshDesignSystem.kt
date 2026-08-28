package com.yang136.sshhelper.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Immutable
data class SshSpacing(
    val xxs: androidx.compose.ui.unit.Dp = 4.dp,
    val xs: androidx.compose.ui.unit.Dp = 8.dp,
    val sm: androidx.compose.ui.unit.Dp = 12.dp,
    val md: androidx.compose.ui.unit.Dp = 16.dp,
    val lg: androidx.compose.ui.unit.Dp = 24.dp,
    val xl: androidx.compose.ui.unit.Dp = 32.dp,
)

@Immutable
data class SshMotion(val fast: Int = 150, val standard: Int = 220, val emphasized: Int = 250)

enum class SshStatusTone(val label: String, val iconKey: String, val priority: Int) {
    CONNECTED("在线", "check_circle", 0),
    CONNECTING("连接中", "sync", 2),
    WAITING("等待", "schedule", 3),
    WARNING("警告", "warning", 4),
    ERROR("失败", "error", 5),
    OFFLINE("离线", "offline", 1),
}

@Immutable
data class SshStatusColors(
    val connected: Color,
    val connecting: Color,
    val waiting: Color,
    val warning: Color,
    val error: Color,
    val offline: Color,
) {
    fun color(tone: SshStatusTone): Color = when (tone) {
        SshStatusTone.CONNECTED -> connected
        SshStatusTone.CONNECTING -> connecting
        SshStatusTone.WAITING -> waiting
        SshStatusTone.WARNING -> warning
        SshStatusTone.ERROR -> error
        SshStatusTone.OFFLINE -> offline
    }
}

val LocalSshSpacing = staticCompositionLocalOf(::SshSpacing)
val LocalSshMotion = staticCompositionLocalOf(::SshMotion)
val LocalSshStatusColors = staticCompositionLocalOf {
    SshStatusColors(Color(0xFF2E7D5B), Color(0xFF1976A3), Color(0xFF7A6A2D), Color(0xFFB26A00), Color(0xFFBA1A1A), Color(0xFF687076))
}

object SshTheme {
    val spacing: SshSpacing @Composable get() = LocalSshSpacing.current
    val motion: SshMotion @Composable get() = LocalSshMotion.current
    val status: SshStatusColors @Composable get() = LocalSshStatusColors.current
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable (() -> Unit) = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

@Composable
fun SshStatusBadge(label: String, tone: SshStatusTone, modifier: Modifier = Modifier) {
    val color = SshTheme.status.color(tone)
    Text(
        label,
        modifier.background(color.copy(alpha = .14f), MaterialTheme.shapes.small).padding(horizontal = 9.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}

@Composable
fun SshSectionHeader(title: String, modifier: Modifier = Modifier, summary: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        summary?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SshEmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SshInlineBanner(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    tone: SshStatusTone = SshStatusTone.WARNING,
    action: (@Composable () -> Unit)? = null,
) {
    val color = SshTheme.status.color(tone)
    Row(
        modifier.fillMaxWidth().background(color.copy(alpha = .10f), MaterialTheme.shapes.medium).padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Info, null, tint = color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = color)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}

@Composable
fun PreferenceAction(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke() ?: Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PreferenceSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange, enabled = enabled)
    }
}

@Composable
fun PreferenceGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
fun SshHostCard(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(Modifier.fillMaxWidth().padding(16.dp)) { content() } }
}

@Composable
fun SshSessionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SshActionTile(icon: ImageVector, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun PreferenceChoice(title: String, summary: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PreferenceAction(
        icon = if (selected) Icons.Default.Info else Icons.Default.Info,
        title = title,
        summary = summary,
        onClick = onClick,
        modifier = modifier,
        trailing = { SshStatusBadge(if (selected) "已选择" else "选择", if (selected) SshStatusTone.CONNECTED else SshStatusTone.OFFLINE) },
    )
}

@Composable
fun PreferenceSlider(
    title: String,
    summary: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        SshSectionHeader(title, summary = summary)
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
fun PreferenceWarning(title: String, summary: String, action: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    SshInlineBanner(title, summary, modifier, SshStatusTone.WARNING, action)
}
