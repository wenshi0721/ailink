package com.jiuguan.ailink.engine.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jiuguan.ailink.engine.R
import com.jiuguan.ailink.model.ConnStatus

object NotificationHelper {

    const val CHANNEL_ID = "engine_status"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_engine_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_engine_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, states: Map<String, RuntimeState>): Notification {
        val online = states.values.count { it.isOnline }
        val errors = states.values.count { it.status == ConnStatus.ERROR }

        val title = when {
            states.isEmpty() -> "接入服务已就绪"
            online > 0 -> "$online 个账号在线"
            else -> "正在连接账号…"
        }
        val summary = when {
            errors > 0 -> "$errors 个账号连接异常，点击查看"
            states.isEmpty() -> "等待账号启动"
            else -> "自动回复运行中"
        }

        // 不直接引用 ui 层的 MainActivity：通过 PackageManager 取启动 Activity，
        // 效果等价（点击通知打开 App 主界面），同时保证 engine 不反向依赖 ui。
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun notify(context: Context, states: Map<String, RuntimeState>) {
        // Android 13+ 通知权限为运行时权限：未授予时跳过（前台服务仍正常工作，只是无通知）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, build(context, states))
        }
    }
}
