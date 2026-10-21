package com.jiuguan.ailink.engine.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.jiuguan.ailink.engine.di.BotHost
import kotlinx.coroutines.launch

/**
 * 保活前台服务：让账号在应用退到后台后仍保持在线并自动回复。
 */
class BotService : LifecycleService() {

    private val manager get() = (application as BotHost).botManager

    private val keepAlive by lazy { KeepAlive(this, "bot") }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        startForegroundSafely()
        keepAlive.acquire()

        lifecycleScope.launch { manager.startAllEnabled() }
        lifecycleScope.launch {
            manager.states.collect { states -> NotificationHelper.notify(this@BotService, states) }
        }
    }

    private fun startForegroundSafely() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.build(this, emptyMap()),
                type
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        keepAlive.release()
        manager.stopAll()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, BotService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BotService::class.java)) }
        }
    }
}
