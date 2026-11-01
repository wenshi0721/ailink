package com.jiuguan.ailink.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话级诊断日志（内存环形缓冲 + 可选落盘）。
 *
 * 为什么需要它：容器日志只能看到 QQ/NapCat 一侧，**AiLink 自己的发送失败、连接断开、
 * AI 调用错误没有任何落点**，排查时只能看到"没发出去"却不知道为什么。
 *
 * 为什么还要落盘：内存缓冲在进程重启时直接清零，而"导出日志"这个动作往往发生在
 * 重启之后——用户复现完问题、杀掉重开、再导出，拿到的只剩重启后的几十行，
 * 关键现场（收发了哪条消息、图片为什么没进模型）全部丢失。
 * [attach] 之后每写一行就同步追加到文件，导出内容因此不再受进程生命周期影响。
 */
object SessionLog {

    private const val MAX_LINES = 2000

    /** 落盘文件的滚动上限：超过后保留后半段，避免无限增长。 */
    private const val MAX_FILE_BYTES = 512L * 1024L

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 落盘目标；由 Application 在 onCreate 时注入。 */
    private var sink: File? = null

    /** 写盘失败（目录只读、空间不足）后不再重试，避免每行都抛一次异常。 */
    private var sinkBroken = false

    /** 注入落盘文件。传 null 可关闭落盘（仅内存）。 */
    @Synchronized
    fun attach(file: File?) {
        sink = file
        sinkBroken = false
    }

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "[${formatter.format(Date())}][$tag] $message"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        appendToFile(line)
    }

    /** 导出用内容：优先读落盘文件（含重启前的历史），文件不可用时退回内存缓冲。 */
    @Synchronized
    fun dump(): String {
        val file = sink
        if (file != null && file.exists() && file.length() > 0L) {
            val text = runCatching { file.readText() }.getOrNull().orEmpty()
            if (text.isNotBlank()) return text
        }
        return buffer.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        val file = sink ?: return
        runCatching { file.writeText("") }.onFailure { sinkBroken = true }
    }

    /** 当前缓冲行数（廉价，供界面显示摘要，不去读整个日志文件）。 */
    @Synchronized
    fun lineCount(): Int = buffer.size

    /**
     * 只取最后 [lines] 行，供界面预览。
     *
     * 界面每帧都可能重绘，不能为此把几百 KB 的日志整份读进内存——这里从文件尾部
     * 读一段（最多 16 KB）再切行，成本与日志总量无关。
     */
    @Synchronized
    fun tail(lines: Int): String {
        if (lines <= 0) return ""
        val file = sink
        if (file != null && file.exists() && file.length() > 0L) {
            val readBytes = minOf(file.length(), 16L * 1024L).toInt()
            val text = runCatching {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - readBytes)
                    val bytes = ByteArray(readBytes)
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                }
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                // 首行可能是被截断的半行，丢掉
                return text.lineSequence()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .toList()
                    .takeLast(lines)
                    .joinToString("\n")
            }
        }
        return buffer.toList().filter { it.isNotBlank() }.takeLast(lines).joinToString("\n")
    }

    private fun appendToFile(line: String) {
        val file = sink ?: return
        if (sinkBroken) return
        runCatching {
            if (file.length() > MAX_FILE_BYTES) roll(file)
            file.appendText(line + "\n")
        }.onFailure { sinkBroken = true }
    }

    /** 截断到后一半，并从下一行开头接上，避免留下半行。 */
    private fun roll(file: File) {
        val text = file.readText()
        val keep = text.takeLast((MAX_FILE_BYTES / 2).toInt())
        val fromLineStart = keep.substringAfter('\n', keep)
        file.writeText(fromLineStart)
    }
}
