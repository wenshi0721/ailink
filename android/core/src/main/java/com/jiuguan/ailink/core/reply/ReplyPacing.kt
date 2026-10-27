package com.jiuguan.ailink.core.reply

import kotlin.random.Random

/**
 * 多段发送的节奏计算。
 *
 * 不用固定间隔：固定 1.5 秒两条，比一口气发完更像机器。
 * 这里按「这条要打多少字」估算，再叠 ±30% 抖动，最后夹在配置的上下限内。
 */
object ReplyPacing {

    /** 抖动幅度：0.7 ~ 1.3 倍。 */
    private const val JITTER_MIN = 0.7
    private const val JITTER_SPAN = 0.6

    /**
     * 当前这条发完后，等下一条要等多久。
     *
     * @param segment 刚发出的这一条（按它的字数估算"打字"用时）
     */
    fun gapMs(segment: String, config: MultiReplyConfig, random: Random = Random.Default): Long {
        val min = config.minGapMs.coerceAtLeast(0).toLong()
        val max = config.maxGapMs.coerceAtLeast(config.minGapMs).toLong()
        if (max <= 0L) return 0L

        val typing = segment.length.toLong() * config.msPerChar.coerceAtLeast(0)
        val jittered = (typing * (JITTER_MIN + random.nextDouble() * JITTER_SPAN)).toLong()
        return jittered.coerceIn(min, max)
    }
}

/**
 * 单位时间内的输出账本（每个会话一份）。
 *
 * 用来回答一个问题：「这个会话最近一分钟已经说了多少字，还能再说多少」。
 * 配置实时生效——调用方把当前的窗口与容量传进来，改配置不需要重建对象。
 *
 * 不落盘：它描述的是"刚刚发生了什么"，进程重启后清零是期望行为。
 */
class OutputLedger {

    private data class Sent(val at: Long, val chars: Int)

    private val events = ArrayDeque<Sent>()

    /**
     * 还能再发多少字。
     *
     * @return 负数表示不限（容量或窗口为 0）；0 表示本轮预算已耗尽。
     */
    @Synchronized
    fun remaining(now: Long, windowMs: Long, capacity: Int): Int {
        if (windowMs <= 0L || capacity <= 0) return UNLIMITED
        prune(now, windowMs)
        val used = events.sumOf { it.chars }
        return (capacity - used).coerceAtLeast(0)
    }

    @Synchronized
    fun record(now: Long, chars: Int, windowMs: Long) {
        if (chars <= 0) return
        if (windowMs > 0L) prune(now, windowMs)
        events.addLast(Sent(now, chars))
    }

    /** 清空（会话结束或被用户删除时调用）。 */
    @Synchronized
    fun reset() = events.clear()

    private fun prune(now: Long, windowMs: Long) {
        val cutoff = now - windowMs
        while (events.isNotEmpty() && events.first().at < cutoff) {
            events.removeFirst()
        }
    }

    companion object {
        const val UNLIMITED = -1
    }
}
