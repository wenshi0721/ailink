package com.jiuguan.ailink.engine.container

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * 容器内 DNS 的动态写入。
 *
 * 背景：rootfs 里的 `/etc/resolv.conf` 原先在安装时写死成 223.5.5.5 / 1.1.1.1 / 8.8.8.8。
 * 一旦从 WiFi 切到移动数据（或反过来），运营商对这些公共 DNS 的 UDP 53 经常被劫持或丢包，
 * 容器里的 Linux QQ 就会陷入"域名解析不了 → 连不上腾讯 → 内核推 status=20"的境地，
 * 表现为"切个网络就掉线"。这里改成优先使用 Android 当前网络的系统 DNS，
 * 公共 DNS 只作兜底。
 */
object NetworkDns {

    /** 兜底用的公共 DNS：只在系统 DNS 拿不到时补位。 */
    private val FALLBACK = listOf("223.5.5.5", "119.29.29.29", "1.1.1.1")

    /** 读取当前活动网络的 DNS 地址；无权限/无网络时返回空列表。 */
    fun currentDns(context: Context): List<String> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        val properties = manager.getLinkProperties(network) ?: return emptyList()
        return properties.dnsServers
            .mapNotNull { it.hostAddress }
            .filter { it.isNotBlank() }
    }

    /**
     * 把当前网络可用的 DNS 写进容器的 resolv.conf。
     *
     * @return 实际写入的服务器列表（用于日志）；空列表表示既没拿到系统 DNS 也没写成功。
     */
    fun writeResolvConf(context: Context, rootfs: File): List<String> {
        val servers = (currentDns(context) + FALLBACK).distinct()
        if (servers.isEmpty()) return emptyList()
        val ok = runCatching {
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/resolv.conf").writeText(
                servers.joinToString("\n") { "nameserver $it" } + "\n"
            )
        }.isSuccess
        return if (ok) servers else emptyList()
    }
}
