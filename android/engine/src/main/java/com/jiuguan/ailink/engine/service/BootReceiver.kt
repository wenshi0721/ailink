package com.jiuguan.ailink.engine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jiuguan.ailink.core.AppPrefs

/**
 * 开机后若用户此前开启了接入服务，则自动恢复。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (!AppPrefs(context).engineRunning.value) return
        BotService.start(context)
    }
}
