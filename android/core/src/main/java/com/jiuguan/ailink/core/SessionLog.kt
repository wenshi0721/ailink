package com.jiuguan.ailink.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话级诊断日志（内存环形缓冲）。
 *
 * 为什么需要它：容器日志只能看到 QQ/NapCat 一侧，**AiLink 自己的发送失败、连接断开、
 * AI 调用错误没有任何落点**，排查时只能看到"没发出去"却不知道为什么。
 *
 * 这里记录关键动作与失败原因，导出日志时作为独立段落附在后面。
 * 只在内存中保留最近 [MAX_LINES] 行，不写磁盘，避免频繁 IO。
 */
object SessionLog {

    private const val MAX_LINES = 2000

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "[${formatter.format(Date())}][$tag] $message"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
    }

    @Synchronized
    fun dump(): String = buffer.joinToString("\n")

    @Synchronized
    fun clear() = buffer.clear()
}
