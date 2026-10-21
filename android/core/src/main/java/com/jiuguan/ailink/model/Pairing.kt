package com.jiuguan.ailink.model

import android.net.Uri

/**
 * 一键配对信息。目的是把「填 IP + 端口 + 令牌 + 平台 + 备注」四五个字段
 * 压缩成一条可扫码/可粘贴的字符串，降低接入门槛。
 *
 * 标准格式：
 *   ailink://pair?platform=qq&host=192.168.1.10&port=3001&token=xxx&name=工作QQ
 *
 * 兼容格式（直接粘贴地址，令牌留空待填）：
 *   ws://192.168.1.10:3001
 */
data class PairingInfo(
    val platform: Platform,
    val name: String,
    val host: String,
    val port: Int,
    val token: String
) {
    val endpoint: String get() = "ws://$host:$port"

    fun toUri(): String {
        val tokenPart = if (token.isBlank()) "" else "&token=${Uri.encode(token)}"
        val namePart = if (name.isBlank()) "" else "&name=${Uri.encode(name)}"
        return "ailink://pair?platform=${platform.name.lowercase()}" +
            "&host=${Uri.encode(host)}&port=$port$tokenPart$namePart"
    }
}

object PairingParser {

    fun parse(raw: String): PairingInfo? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null

        return when (uri.scheme?.lowercase()) {
            "ailink" -> parsePairUri(uri)
            "ws", "wss" -> parseEndpoint(uri)
            else -> null
        }
    }

    private fun parsePairUri(uri: Uri): PairingInfo? {
        val platform = when (uri.getQueryParameter("platform")?.lowercase()) {
            "wechat", "wx", "weixin" -> Platform.WECHAT
            else -> Platform.QQ
        }
        val host = uri.getQueryParameter("host").orEmpty().trim()
        val port = uri.getQueryParameter("port")?.trim()?.toIntOrNull() ?: DEFAULT_PORT
        if (host.isBlank() || port !in 1..65535) return null

        return PairingInfo(
            platform = platform,
            name = uri.getQueryParameter("name").orEmpty().ifBlank { defaultName(platform) },
            host = host,
            port = port,
            token = uri.getQueryParameter("token").orEmpty()
        )
    }

    private fun parseEndpoint(uri: Uri): PairingInfo? {
        val host = uri.host.orEmpty().ifBlank { return null }
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        // 地址里没带令牌：留空，由用户在表单里补
        return PairingInfo(
            platform = Platform.QQ,
            name = "",
            host = host,
            port = port,
            token = ""
        )
    }

    private fun defaultName(platform: Platform): String =
        if (platform == Platform.WECHAT) "微信账号" else "QQ 账号"

    private const val DEFAULT_PORT = 3001
}
