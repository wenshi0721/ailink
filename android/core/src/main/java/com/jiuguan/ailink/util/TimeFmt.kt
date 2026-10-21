package com.jiuguan.ailink.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFmt {

    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val monthDay = SimpleDateFormat("MM-dd", Locale.getDefault())
    private val fullDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val monthDayClock = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun clock(ts: Long): String = clock.format(Date(ts))

    /** 会话列表使用的相对时间。 */
    fun relative(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return ""
        val diff = now - ts
        if (diff < 60_000L) return "刚刚"
        if (diff < 3_600_000L) return "${diff / 60_000L} 分钟前"

        val todayStart = startOfDay(now)
        return when {
            ts >= todayStart -> clock(ts)
            ts >= todayStart - 86_400_000L -> "昨天 ${clock(ts)}"
            ts >= startOfYear(now) -> monthDay.format(Date(ts))
            else -> fullDate.format(Date(ts))
        }
    }

    /** 聊天详情页时间分隔标签。 */
    fun chatStamp(ts: Long, now: Long = System.currentTimeMillis()): String {
        val todayStart = startOfDay(now)
        return when {
            ts >= todayStart -> clock(ts)
            ts >= todayStart - 86_400_000L -> "昨天 ${clock(ts)}"
            ts >= startOfYear(now) -> monthDayClock.format(Date(ts))
            else -> fullDate.format(Date(ts))
        }
    }

    fun duration(ms: Long): String {
        if (ms <= 0L) return "0 分"
        if (ms < 60_000L) return "不到 1 分钟"
        val totalMinutes = ms / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours} 小时 ${minutes} 分" else "${minutes} 分"
    }

    fun startOfDay(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun startOfYear(now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
