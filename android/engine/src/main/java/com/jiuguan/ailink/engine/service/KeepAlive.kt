package com.jiuguan.ailink.engine.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * 息屏保活。
 *
 * 安卓息屏后进入 Doze：CPU 休眠、Wi-Fi 降低功耗，容器里的 QQ/NapCat 线程随之停摆，
 * WebSocket 心跳无人应答，表现为"消息发不出、收不到"。
 *
 * 前台服务只保证进程不被杀，**不保证 CPU 不休眠**，所以必须显式持有：
 *  - PARTIAL_WAKE_LOCK：息屏时保持 CPU 运行（不点亮屏幕）
 *  - WifiLock：避免息屏后 Wi-Fi 进入省电模式导致长连接断开
 *
 * 两个锁都只是"保持运行"，耗电影响主要体现在深度休眠被打破，属于常驻服务的必要代价。
 */
class KeepAlive(context: Context, private val tag: String) {

    private val appContext = context.applicationContext

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        if (wakeLock == null) {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ailink:$tag").apply {
                setReferenceCounted(false)
                runCatching { acquire() }.onSuccess {
                    com.jiuguan.ailink.core.SessionLog.add("keepalive", "$tag 已获取 PARTIAL_WAKE_LOCK")
                }
            }
        }
        if (wifiLock == null) {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "ailink:$tag").apply {
                setReferenceCounted(false)
                runCatching { acquire() }.onSuccess {
                    com.jiuguan.ailink.core.SessionLog.add("keepalive", "$tag 已获取 WifiLock")
                }
            }
        }
    }

    fun release() {
        com.jiuguan.ailink.core.SessionLog.add("keepalive", "$tag 释放保活锁")
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }
}
