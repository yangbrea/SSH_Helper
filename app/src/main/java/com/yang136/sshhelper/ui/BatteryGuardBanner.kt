package com.yang136.sshhelper.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yang136.sshhelper.settings.batteryGuidanceText
import com.yang136.sshhelper.settings.detectOemFamily
import com.yang136.sshhelper.settings.isIgnoringBatteryOptimizations
import com.yang136.sshhelper.settings.launchBatterySettings

@Composable
private fun rememberBatteryOptimizationState(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ignoring = isIgnoringBatteryOptimizations(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return ignoring
}

/** Contextual hint shown only while Android still applies battery optimization. */
@Composable
fun BatteryGuardBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ignoring = rememberBatteryOptimizationState()
    var dismissed by remember { mutableStateOf(false) }
    if (ignoring || dismissed) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    "保持后台连接",
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                IconButton(onClick = { dismissed = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "关闭提示", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Text(
                "允许不受电池优化限制可降低 Doze/App Standby 对 SSH 的干扰，但不能保证厂商系统永不回收进程。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                batteryGuidanceText(detectOemFamily()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = .85f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launchBatterySettings(context, preferOem = false) }) { Text("系统电池设置") }
                OutlinedButton(onClick = { launchBatterySettings(context, preferOem = true) }) { Text("厂商设置") }
            }
        }
    }
}

/** Full status card used by Settings > Security. */
@Composable
fun BackgroundConnectionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ignoring = rememberBatteryOptimizationState()
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("后台连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("电池优化：${if (ignoring) "不受限制" else "受系统限制"}")
            Text("通知权限：${if (notificationGranted) "已允许" else "未允许"}")
            Text("ROM：${detectOemFamily().label}")
            Text(
                "前台服务仍会受系统策略约束；电池豁免只能降低干扰，不能保证厂商系统永不回收进程。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launchBatterySettings(context, preferOem = false) }) { Text("系统电池设置") }
                OutlinedButton(onClick = { launchBatterySettings(context, preferOem = true) }) { Text("厂商后台设置") }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
                OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("允许转发通知")
                }
                Text(
                    "拒绝通知权限后，前台服务仍受系统限制，转发状态和通知操作也可能不可见。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
