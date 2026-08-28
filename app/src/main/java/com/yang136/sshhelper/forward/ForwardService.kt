package com.yang136.sshhelper.forward

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.yang136.sshhelper.MainActivity
import com.yang136.sshhelper.R
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.ssh.ForwardState
import com.yang136.sshhelper.ssh.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal enum class ForwardServiceStartMode { REGULAR, FOREGROUND }

internal fun forwardServiceStartMode(appInForeground: Boolean): ForwardServiceStartMode =
    if (appInForeground) ForwardServiceStartMode.REGULAR else ForwardServiceStartMode.FOREGROUND

/**
 * Foreground service (specialUse) that keeps SSH port-forwarding tunnels alive while at least
 * one rule is running, starting, or reconnecting. START_STICKY: if the system kills the
 * process, tunnels are not silently restored and no authentication is triggered.
 */
class ForwardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Some OEMs (observed on vivo Android 15) enforce a much shorter foreground-service
        // promotion window than AOSP. Promote in onCreate rather than waiting for
        // onStartCommand so a busy first Compose frame cannot trigger a process-killing timeout.
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须先于任何可能耗时的初始化调用 startForeground()：系统要求在
        // startForegroundService() 返回后 5 秒内完成，否则抛
        // ForegroundServiceDidNotStartInTimeException（进程崩溃）。
        promoteToForeground()
        val container = (application as SshHelperApplication).container
        val manager = container.forwardManager
        // 用真实状态刷新通知内容。
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(applicationContext, manager.rules.value, manager.states.value))
        // Keep the CPU awake while the tunnel service runs: without a partial wakelock the
        // screen-off CPU suspend stalls JSch keepalive and socket reads, and the connection
        // dies from the network side. Released in onDestroy, so it lives exactly as long
        // as the service.
        acquireWakeLock()
        // Keep the notification in sync with rule states for the lifetime of the service.
        if (stateJob == null) {
            stateJob = scope.launch {
                manager.states.collect { states ->
                    val container = (application as SshHelperApplication).container
                    val manager = container.forwardManager
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(applicationContext, manager.rules.value, states))
                }
            }
        }
        // START_STICKY 仅帮助系统在回收进程后重建本服务；隧道本身由 Application 单例
        // 持有，进程死亡后全部内存状态丢失，恢复"转发意图"由 ForwardManager 读取
        // 持久化的 desired_running 完成（保险库锁定时只显示"等待解锁"）。
        return START_STICKY
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildMinimalNotification(applicationContext),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        releaseWakeLock()
        stateJob?.cancel()
        stateJob = null
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // 隧道服务生命周期内的常驻 WakeLock 是设计意图（随 onDestroy 释放），
        // 不设超时；Lint 的 WakelockTimeout 警告在此场景不适用。
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SSH Helper:端口转发").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val NOTIFICATION_ID = 1202
        private const val CHANNEL_ID = "ssh_helper_forward"

        fun start(context: Context) {
            val intent = Intent(context, ForwardService::class.java)
            val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
            if (forwardServiceStartMode(appInForeground) == ForwardServiceStartMode.REGULAR) {
                // While an Activity is visible, a normal service start is allowed. The service
                // promotes itself in onCreate(), avoiding aggressive OEM interpretations of the
                // startForegroundService promotion deadline (notably vivo Android 15).
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForwardService::class.java))
        }

        private fun buildNotification(
            context: Context,
            rules: List<com.yang136.sshhelper.ssh.PortForwardRule>,
            states: Map<Long, ForwardState>,
        ): Notification {
            ensureChannel(context)
            val active = rules.filter { states[it.id]?.isActive() == true }
            val hosts = active.map { it.hostId }.distinct().size
            val ports = active.mapNotNull { (states[it.id] as? ForwardState.Running)?.actualPort }.joinToString("、")
            val text = buildString {
                append("$hosts 台主机 · ${active.size} 条转发")
                if (ports.isNotEmpty()) append(" · 端口 $ports")
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("SSH Helper 端口转发")
                .setContentText(text)
                .setContentIntent(openIntent(context))
                .setOngoing(true)
                .addAction(0, "保活设置", batterySettingsPendingIntent(context))
                .addAction(0, "全部停止", stopAllIntent(context))
                .build()
        }

        // 不依赖容器初始化的最小通知，用于先满足 5 秒 startForeground 时限。
        private fun buildMinimalNotification(context: Context): Notification {
            ensureChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("SSH Helper 端口转发")
                .setContentText("正在启动…")
                .setContentIntent(openIntent(context))
                .setOngoing(true)
                .build()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "SSH 端口转发", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun stopAllIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, ForwardActionReceiver::class.java).setAction(ForwardActionReceiver.ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun batterySettingsPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, ForwardActionReceiver::class.java).setAction(ForwardActionReceiver.ACTION_OPEN_BATTERY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class ForwardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_ALL -> {
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        (context.applicationContext as SshHelperApplication).container.forwardManager.stopAll()
                    } catch (error: Throwable) {
                        // 停止失败也不能让异常进入默认未捕获处理器（协程未捕获会终止进程）。
                    } finally {
                        // 无论成功与否都必须结束广播：否则系统超时后判定接收器无响应。
                        pendingResult.finish()
                    }
                }
            }

            ACTION_OPEN_BATTERY_SETTINGS -> {
                // 后台掉线多因系统暂停了无可见窗口应用的网络；引导用户豁免电池优化。
                // 通知点击属于用户交互，后台启动 Activity 在此场景被系统允许。
                com.yang136.sshhelper.settings.launchBatterySettings(context, preferOem = false)
            }
        }
    }

    companion object {
        const val ACTION_STOP_ALL = "com.yang136.sshhelper.STOP_FORWARDS"
        const val ACTION_OPEN_BATTERY_SETTINGS = "com.yang136.sshhelper.OPEN_BATTERY_SETTINGS"
    }
}
