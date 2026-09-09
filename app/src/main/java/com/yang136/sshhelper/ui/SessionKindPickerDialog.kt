package com.yang136.sshhelper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yang136.sshhelper.ssh.SessionKind

/**
 * Shared “new session” picker used by the host workspace and terminal pages.
 */
@Composable
internal fun SessionKindPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (SessionKind) -> Unit,
) {
    var selected by remember { mutableStateOf(SessionKind.SSH) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "选择要创建的会话类型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SessionKindOption(
                    kind = SessionKind.SSH,
                    title = "普通 SSH 会话",
                    description = "直接连接，适合日常终端、文件系统与端口转发",
                    badge = "SSH",
                    selected = selected == SessionKind.SSH,
                    onClick = { selected = SessionKind.SSH },
                )
                SessionKindOption(
                    kind = SessionKind.TMUX,
                    title = "tmux 持久会话",
                    description = "支持分屏/多窗口，关闭 App 后远端会话保持",
                    badge = "tmux",
                    selected = selected == SessionKind.TMUX,
                    onClick = { selected = SessionKind.TMUX },
                )
                SessionKindOption(
                    kind = SessionKind.ZMX,
                    title = "ZMX 持久会话",
                    description = "轻量持久会话，保留原生终端滚动体验",
                    badge = "ZMX",
                    selected = selected == SessionKind.ZMX,
                    onClick = { selected = SessionKind.ZMX },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SessionKindOption(
    kind: SessionKind,
    title: String,
    description: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    val accent = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = container,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else accent,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
