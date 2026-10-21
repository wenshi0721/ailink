package com.jiuguan.ailink.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.MessageEntity
import com.jiuguan.ailink.model.Direction
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.util.TimeFmt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val DAY_MS = 86_400_000L

/** 统计周期。全部基于本地 messages 表聚合，不上传任何数据。 */
enum class StatsPeriod(val label: String) {
    TODAY("今日"),
    D7("近 7 日"),
    D30("近 30 日");

    val bucketCount: Int
        get() = when (this) {
            TODAY -> 24
            D7 -> 7
            D30 -> 30
        }

    val byHour: Boolean get() = this == TODAY

    fun since(now: Long = System.currentTimeMillis()): Long = when (this) {
        TODAY -> TimeFmt.startOfDay(now)
        D7 -> TimeFmt.startOfDay(now) - 6 * DAY_MS
        D30 -> TimeFmt.startOfDay(now) - 29 * DAY_MS
    }
}

data class TrendPoint(val label: String, val received: Int, val replied: Int)

data class AccountShare(
    val name: String,
    val platform: Platform,
    val count: Int,
    val fraction: Float
)

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.TODAY,
    val loading: Boolean = false,
    val received: Int = 0,
    val replied: Int = 0,
    val failed: Int = 0,
    val avgResponseMs: Long = 0L,
    val trend: List<TrendPoint> = emptyList(),
    val shares: List<AccountShare> = emptyList()
) {
    /** 发送成功率 = 成功条数 / 全部发出条数；无数据时为 null（界面显示 —）。 */
    val successRate: Int?
        get() = if (replied <= 0) null else ((replied - failed).coerceAtLeast(0) * 100f / replied).roundToInt()

    val hasData: Boolean get() = received + replied > 0

    val peak: Int get() = trend.maxOfOrNull { maxOf(it.received, it.replied) } ?: 0
}

/**
 * 统计页数据源。
 *
 * 口径说明（避免误导）：
 * - 收到 / 已回复：messages 表中非系统提示的 IN / OUT 条数。
 * - 成功率为「发出消息成功占比」，失败来自 status = FAILED，不含 AI 调用失败（那类只写系统提示）。
 * - 平均首响：同一会话内，一条 IN 之后 10 分钟内第一条 OUT 的间隔均值。
 */
class StatsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.messageRepository

    private val period = MutableStateFlow(StatsPeriod.TODAY)
    private val loading = MutableStateFlow(false)

    private val accounts = container.accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val messages = period
        .flatMapLatest { repository.observeSince(it.since()) }
        .onEach { loading.value = false }

    val state: StateFlow<StatsUiState> =
        combine(period, messages, accounts, loading) { p, list, accs, busy ->
            aggregate(p, list, accs).copy(loading = busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setPeriod(next: StatsPeriod) {
        if (period.value == next) return
        loading.value = true
        period.value = next
        // 数据库查询通常在 100ms 内返回；给一个最短反馈时长，避免闪烁
        viewModelScope.launch {
            delay(240)
            loading.value = false
        }
    }

    fun refresh() {
        loading.value = true
        viewModelScope.launch {
            delay(240)
            loading.value = false
        }
    }

    private fun aggregate(
        period: StatsPeriod,
        messages: List<MessageEntity>,
        accounts: List<AccountEntity>
    ): StatsUiState {
        val now = System.currentTimeMillis()
        val real = messages.filter { !it.isSystem }
        val incoming = real.count { Direction.of(it.direction) == Direction.IN }
        val outgoing = real.count { it.isOutgoing }
        val failed = real.count { it.isOutgoing && it.status == "FAILED" }

        return StatsUiState(
            period = period,
            received = incoming,
            replied = outgoing,
            failed = failed,
            avgResponseMs = averageFirstReply(real),
            trend = buildTrend(period, real, now),
            shares = buildShares(real, accounts)
        )
    }

    private fun buildTrend(
        period: StatsPeriod,
        messages: List<MessageEntity>,
        now: Long
    ): List<TrendPoint> {
        val inCounts = IntArray(period.bucketCount)
        val outCounts = IntArray(period.bucketCount)
        val todayStart = TimeFmt.startOfDay(now)

        messages.forEach { message ->
            val index = if (period.byHour) {
                hourOf(message.createdAt)
            } else {
                val daysAgo = ((todayStart - TimeFmt.startOfDay(message.createdAt)) / DAY_MS).toInt()
                period.bucketCount - 1 - daysAgo
            }
            if (index !in inCounts.indices) return@forEach
            if (message.isOutgoing) outCounts[index]++ else inCounts[index]++
        }

        return (0 until period.bucketCount).map { index ->
            TrendPoint(
                label = labelOf(period, index, now),
                received = inCounts[index],
                replied = outCounts[index]
            )
        }
    }

    private fun labelOf(period: StatsPeriod, index: Int, now: Long): String = when (period) {
        StatsPeriod.TODAY -> if (index % 3 == 0) "$index" else ""
        StatsPeriod.D7 -> {
            val ts = TimeFmt.startOfDay(now) - (6 - index) * DAY_MS
            SimpleDateFormat("E", Locale.CHINA).format(Date(ts))
        }
        StatsPeriod.D30 -> {
            if (index % 5 == 0 || index == 29) {
                val ts = TimeFmt.startOfDay(now) - (29 - index) * DAY_MS
                SimpleDateFormat("M/d", Locale.getDefault()).format(Date(ts))
            } else ""
        }
    }

    private fun averageFirstReply(messages: List<MessageEntity>): Long {
        val samples = ArrayList<Long>(64)
        messages.groupBy { it.convId }.values.forEach { timeline ->
            var lastIn = 0L
            timeline.sortedBy { it.createdAt }.forEach { message ->
                if (message.isOutgoing) {
                    if (lastIn > 0L) {
                        val delta = message.createdAt - lastIn
                        if (delta in 0..600_000L) {
                            samples.add(delta)
                            lastIn = 0L
                        }
                    }
                } else {
                    lastIn = message.createdAt
                }
            }
        }
        if (samples.isEmpty()) return 0L
        return samples.average().toLong()
    }

    private fun buildShares(
        messages: List<MessageEntity>,
        accounts: List<AccountEntity>
    ): List<AccountShare> {
        val counts = messages.groupingBy { it.accountId }.eachCount()
        val total = counts.values.sum()
        if (total == 0) return emptyList()

        return counts.entries
            .sortedByDescending { it.value }
            .take(4)
            .map { (accountId, count) ->
                val account = accounts.firstOrNull { it.id == accountId }
                AccountShare(
                    name = account?.displayName?.ifBlank { "未命名账号" } ?: "已删除账号",
                    platform = account?.platformType ?: Platform.QQ,
                    count = count,
                    fraction = count.toFloat() / total
                )
            }
    }

    private fun hourOf(ts: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return c.get(Calendar.HOUR_OF_DAY)
    }
}
