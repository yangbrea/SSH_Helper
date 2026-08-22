package com.yang136.sshhelper.forward

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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

/**
 * Foreground service (specialUse) that keeps SSH port-forwarding tunnels alive while at least
 * one rule is running, starting, or reconnecting. START_NOT_STICKY: if the system kills the
 * process, tunnels are not silently restored and no authentication is triggered.
 */
class ForwardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as SshHelperApplication).container
        val manager = container.forwardManager
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(applicationContext, manager.rules.value, manager.states.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        stateJob = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1202
        private const val CHANNEL_ID = "ssh_helper_forward"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ForwardService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForwardService::class.java))
        }

        private fun buildNotification(
            context: Context,
            rules: List<com.yang136.sshhelper.ssh.PortForwardRule>,
            states: Map<Long, ForwardState>,
        ): Notification {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "SSH 端口转发", NotificationManager.IMPORTANCE_LOW),
                )
            }
            val active = rules.filter { states[it.id]?.isActive() == true }
            val hosts = active.map { it.hostId }.distinct().size
            val ports = active.mapNotNull { (states[it.id] as? ForwardState.Running)?.actualPort }.joinToString("、")
            val text = buildString {
                append("$hosts 台主机 · ${active.size} 条转发")
                if (ports.isNotEmpty()) append(" · 端口 $ports")
            }
            val openIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stopAllIntent = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, ForwardActionReceiver::class.java).setAction(ForwardActionReceiver.ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("SSH Helper 端口转发")
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(0, "全部停止", stopAllIntent)
                .build()
        }
    }
}

class ForwardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_ALL) {
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                (context.applicationContext as SshHelperApplication).container.forwardManager.stopAll()
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP_ALL = "com.yang136.sshhelper.STOP_FORWARDS"
    }
}
