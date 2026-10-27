package com.jiuguan.ailink.core.reply

/** 一个可以被主动搭话的候选会话。 */
data class ProactiveCandidate(
    val convId: String,
    val isGroup: Boolean,
    /** 会话最后一条消息（不分方向）的时间。 */
    val lastMessageAt: Long,
    /** 该会话上次主动发起的时间；0 表示从未。 */
    val lastProactiveAt: Long,
    /** 是否存在双向历史（双方都说过话）。 */
    val twoWay: Boolean
)

enum class ProactiveKind(val label: String) {
    /** 静默太久，接着上次的话题续一句。 */
    IDLE("静默续话"),

    /** 到了设定的时间点，主动打个招呼。 */
    SCHEDULED("定时打招呼")
}

data class ProactiveDecision(
    val convId: String,
    val kind: ProactiveKind,
    /** 写进日志的触发原因，便于事后判断"为什么它突然说话了"。 */
    val reason: String,
    /** 定时触发对应的槽位键，用来保证同一天同一个时间点只触发一次。 */
    val slotKey: String = ""
)

/**
 * 决策结果。
 *
 * 不只返回「发不发」，还带上不发的理由：主动发起是"没动静"最难排查的能力，
 * 事后看日志必须能回答「它为什么一整天都没说话」。
 */
sealed interface ProactiveOutcome {
    data class Fire(val decision: ProactiveDecision) : ProactiveOutcome

    data class Skip(val reason: String) : ProactiveOutcome
}

/**
 * 主动发起的决策逻辑。
 *
 * 三条不可退让的安全边界，写在最前面：
 * 1. **只找有双向历史的会话**。给从没回过话的人发消息，是最典型也最容易被判定为
 *    营销骚扰的行为，风控代价远高于收益；项目使用者要求放宽的是"最后一条是不是对方说的"，
 *    不是"能不能给陌生人发"。
 * 2. **群聊默认禁止**，需要单独打开。群里主动说话被举报的概率最高。
 * 3. **免打扰时段、每小时/每天配额、会话最小间隔**三重限制同时生效，任一不满足就不发。
 *
 * 时间由调用方拆成 [hourOfDay] / [minuteOfDay] / [dayKey] 传进来，
 * 这样这里不依赖任何时区实现，可以直接单测。
 */
object ProactivePolicy {

    /** 定时点允许的迟到容忍：一分钟一次的轮询偶尔会被系统调度推迟。 */
    const val SCHEDULE_TOLERANCE_MINUTES = 2

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    fun isQuiet(hourOfDay: Int, config: ProactiveConfig): Boolean {
        val start = config.quietStartHour
        val end = config.quietEndHour
        if (start == end) return false
        // 跨零点：23 → 8 表示 23:00-23:59 与 00:00-07:59 都算免打扰
        return if (start < end) hourOfDay in start until end
        else hourOfDay >= start || hourOfDay < end
    }

    /**
     * 决定这一轮要不要主动发、发给谁。
     *
     * @param usedThisHour / @param usedToday 从库里统计出的已发条数（跨进程重启有效）
     * @param firedSlots 今天已经触发过的定时槽位
     */
    fun decide(
        now: Long,
        hourOfDay: Int,
        minuteOfDay: Int,
        dayKey: String,
        config: ProactiveConfig,
        candidates: List<ProactiveCandidate>,
        usedThisHour: Int,
        usedToday: Int,
        firedSlots: Set<String>
    ): ProactiveOutcome {
        if (!config.enabled) return ProactiveOutcome.Skip("开关关闭")
        if (isQuiet(hourOfDay, config)) {
            return ProactiveOutcome.Skip(
                "免打扰时段 ${config.quietStartHour}-${config.quietEndHour} 点"
            )
        }
        if (!withinHourlyQuota(usedThisHour, config)) {
            return ProactiveOutcome.Skip("小时配额已满 $usedThisHour/${config.maxPerHour}")
        }
        if (!withinDailyQuota(usedToday, config)) {
            return ProactiveOutcome.Skip("每天配额已满 $usedToday/${config.maxPerDay}")
        }
        if (candidates.isEmpty()) return ProactiveOutcome.Skip("没有可用的会话")

        val eligible = candidates.filter { isEligible(it, now, config) }
        if (eligible.isEmpty()) {
            val groupSkipped = candidates.count { it.isGroup && !config.allowInGroup }
            val oneWaySkipped = candidates.count { !it.twoWay }
            return ProactiveOutcome.Skip(
                "无可发起的会话（候选 ${candidates.size}：群聊跳过 $groupSkipped，" +
                    "无双向历史 $oneWaySkipped，其余受静默范围/间隔限制）"
            )
        }

        // 定时打招呼优先：这是使用者明确指定的动作，比"聊完了续一句"更确定
        val schedule = config.scheduleMinuteOfDay.firstOrNull { minute ->
            minuteOfDay >= minute && minuteOfDay < minute + SCHEDULE_TOLERANCE_MINUTES
        }
        if (schedule != null) {
            val key = slotKey(dayKey, schedule)
            if (key !in firedSlots) {
                val target = eligible.maxByOrNull { it.lastMessageAt }
                    ?: return ProactiveOutcome.Skip("没有可用会话")
                return ProactiveOutcome.Fire(
                    ProactiveDecision(
                        convId = target.convId,
                        kind = ProactiveKind.SCHEDULED,
                        reason = "定时 ${formatMinute(schedule)} 触发",
                        slotKey = key
                    )
                )
            }
        }

        if (config.idleMinutes <= 0) return ProactiveOutcome.Skip("静默续话已关闭")

        val idle = eligible
            .filter { now - it.lastMessageAt >= config.idleMinutes * 60_000L }
            // 挑最近还热络的那个：跟三天没说话的人搭话，比跟一小时前聊过的更违和
            .maxByOrNull { it.lastMessageAt }
            ?: return ProactiveOutcome.Skip("静默未达 ${config.idleMinutes} 分钟")

        return ProactiveOutcome.Fire(
            ProactiveDecision(
                convId = idle.convId,
                kind = ProactiveKind.IDLE,
                reason = "静默 ${(now - idle.lastMessageAt) / 60_000L} 分钟"
            )
        )
    }

    /** 单会话是否满足发起条件（不含配额与时段）。 */
    fun isEligible(candidate: ProactiveCandidate, now: Long, config: ProactiveConfig): Boolean {
        if (!candidate.twoWay) return false
        if (candidate.isGroup && !config.allowInGroup) return false

        val silence = now - candidate.lastMessageAt
        if (candidate.lastMessageAt <= 0L || silence < 0L) return false
        // 太久没联系的人不打扰：翻出半年前的会话打招呼，最像机器人
        if (silence > config.activeWithinDays * DAY_MS) return false

        if (candidate.lastProactiveAt > 0L &&
            now - candidate.lastProactiveAt < config.minIntervalPerConvHours * HOUR_MS
        ) {
            return false
        }
        return true
    }

    fun withinHourlyQuota(usedThisHour: Int, config: ProactiveConfig): Boolean =
        config.maxPerHour <= 0 || usedThisHour < config.maxPerHour

    fun withinDailyQuota(usedToday: Int, config: ProactiveConfig): Boolean =
        config.maxPerDay <= 0 || usedToday < config.maxPerDay

    fun slotKey(dayKey: String, minuteOfDay: Int): String = "$dayKey#$minuteOfDay"

    private fun formatMinute(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}
