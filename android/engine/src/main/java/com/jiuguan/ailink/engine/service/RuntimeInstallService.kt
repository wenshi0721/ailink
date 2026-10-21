package com.jiuguan.ailink.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jiuguan.ailink.engine.R
import com.jiuguan.ailink.engine.container.LocalRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * QQ 运行时安装服务。
 *
 * 为什么要服务：安装要下载 350 MB 并解包 640 MB，耗时 5–10 分钟。
 * 放在页面协程里，用户一切走或熄屏就可能被杀；放到前台服务里则不受影响，
 * 重新打开 App 还能接上进度。
 */
class RuntimeInstallService : Service() {

    companion object {
        private const val CHANNEL_ID = "runtime_install"
        private const val NOTIFICATION_ID = 2001

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _lines = MutableStateFlow<List<String>>(emptyList())
        val lines: StateFlow<List<String>> = _lines.asStateFlow()

        private val _finished = MutableStateFlow<String?>(null)
        val finished: StateFlow<String?> = _finished.asStateFlow()

        fun start(context: Context) {
            if (_running.value) return
            val intent = Intent(context, RuntimeInstallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun resetIfIdle() {
            if (!_running.value) {
                _lines.value = emptyList()
                _finished.value = null
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (_running.value) return START_NOT_STICKY
        _running.value = true
        _finished.value = null
        _lines.value = emptyList()

        startForeground(NOTIFICATION_ID, buildNotification("正在准备…"))

        val runtime = LocalRuntime(applicationContext)
        job = scope.launch {
            val result = runtime.installQqRuntime { line ->
                _lines.value = (_lines.value + line).takeLast(500)
                val step = line.substringAfter(']').trim().takeIf { line.contains("步骤") }
                if (step != null) updateNotification(step)
            }
            _finished.value = result.fold(
                onSuccess = { code -> if (runtime.isQqRuntimeInstalled()) "安装完成" else "安装结束（退出码 $code），未完成" },
                onFailure = { error -> "安装失败：${error.message ?: "未知错误"}" }
            )
            _running.value = false
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "运行时安装",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "安装手机内置 QQ 运行时的进度" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("正在安装 QQ 运行时")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
