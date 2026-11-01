package com.jiuguan.ailink.engine.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * 监听系统网络的变化（切换 WiFi/移动数据、断网、弱网）。
 *
 * 为什么需要：容器里的 QQ 走 Android 的网络栈，网络一换，它的长连接就断了，
 * 而"断了之后多久能自己连回来"完全取决于内核。我们的自愈窗口是 90 秒，
 * 如果能在"网络刚恢复"这一刻就介入，可以把空窗从分钟级压到十几秒。
 *
 * [onChanged] 在系统回调线程触发，调用方需要自己切到后台线程做 IO。
 */
class NetworkWatcher(
    context: Context,
    private val onChanged: (available: Boolean, validated: Boolean, cellular: Boolean) -> Unit
) {

    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            report(network)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            report(network, caps)
        }

        override fun onLost(network: Network) {
            onChanged(false, false, false)
        }
    }

    private fun report(network: Network, caps: NetworkCapabilities? = null) {
        val capabilities = caps ?: runCatching { manager?.getNetworkCapabilities(network) }.getOrNull()
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        onChanged(true, validated, cellular)
    }

    fun start() {
        if (registered) return
        val cm = manager ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, callback)
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
    }
}
