package com.jiuguan.ailink.core.reply

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 账号级的「回复行为」配置：分段、主动发起、限流。
 *
 * 为什么整体序列化成一段 JSON 存在偏好里，而不是给 accounts 加一堆列：
 * - 这几个能力各自都有 6~10 个参数，加列会让 accounts 表持续膨胀，每次调整都要写迁移；
 * - 存偏好可以做到**零 database 迁移**，回滚到旧版本 APK 不会触发
 *   `fallbackToDestructiveMigration` 把聊天记录清空（项目里确实配了这个兜底）；
 * - 项目已有「按实体 id 存附属配置」的先例（`bridgeTest`、`visionCache`）。
 *
 * 代价：配置不随数据库备份走。当前没有换机迁移功能，可接受。
 *
 * **默认值即旧行为**：所有新能力默认关闭，[MultiReplyConfig.segmentMaxChars] 为 0（不限），
 * 关闭分段时 `deliverReply` 走的是与改造前完全相同的单条发送路径。
 */
@Serializable
data class BehaviorConfig(
    val multi: MultiReplyConfig = MultiReplyConfig(),
    val proactive: ProactiveConfig = ProactiveConfig(),
    val throttle: ThrottleConfig = ThrottleConfig()
) {

    /** 钳制到安全区间：界面滑块、手改 JSON、旧版本残留值都可能越界。 */
    fun normalized(): BehaviorConfig = BehaviorConfig(
        multi = multi.normalized(),
        proactive = proactive.normalized(),
        throttle = throttle.normalized()
    )

    companion object {
        val DEFAULT = BehaviorConfig()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** 解析失败或为空一律退回默认（全关），绝不让坏配置影响既有回复链路。 */
        fun decode(raw: String): BehaviorConfig {
            if (raw.isBlank()) return DEFAULT
            return runCatching { json.decodeFromString(serializer(), raw) }
                .getOrElse { DEFAULT }
                .normalized()
        }

        fun encode(config: BehaviorConfig): String =
            json.encodeToString(serializer(), config.normalized())
    }
}

/**
 * 多段回复：把一次生成拆成多条消息连续发出。
 *
 * 关于字数：项目使用者反馈「长篇对话更有人情味」，所以
 * [segmentMaxChars] 默认为 0 = **不限制单段长度**，是否限长完全由使用者决定；
 * 真正防止刷屏的是 [budgetChars]（单位时间内累计发送字数）与 [totalMaxChars]。
 */
@Serializable
data class MultiReplyConfig(
    val enabled: Boolean = false,

    /** 一次回复最多拆成几条。设 1 等价于关闭分段（仍保留总量上限）。 */
    val maxSegments: Int = 3,

    /** 单条消息字数上限；**0 = 不限制**。 */
    val segmentMaxChars: Int = 0,

    /** 整轮回复的字数总上限（对应改造前硬编码的 600）；0 = 不限制。 */
    val totalMaxChars: Int = 600,

    /** 相邻两条之间的最小间隔，避免两条挤在一起像刷屏。 */
    val minGapMs: Int = 900,

    /** 相邻两条之间的最大间隔，防止长段落拖太久。 */
    val maxGapMs: Int = 4_000,

    /** 模拟打字速度：每字耗时（毫秒）。 */
    val msPerChar: Int = 200,

    /** 输出总量统计窗口（秒）。0 = 关闭窗口统计。 */
    val budgetWindowSec: Int = 60,

    /** 窗口内累计发送字数上限；**0 = 不限制**。 */
    val budgetChars: Int = 0
) {
    /** 分段是否真正生效：开关打开且允许多于一条。 */
    val splitting: Boolean get() = enabled && maxSegments > 1

    /** 窗口预算是否生效。 */
    val budgetActive: Boolean get() = budgetWindowSec > 0 && budgetChars > 0

    fun normalized(): MultiReplyConfig {
        val gap = maxGapMs.coerceAtLeast(minGapMs)
        return copy(
            maxSegments = maxSegments.coerceIn(1, MAX_SEGMENTS_LIMIT),
            segmentMaxChars = segmentMaxChars.coerceIn(0, MAX_CHARS_LIMIT),
            totalMaxChars = totalMaxChars.coerceIn(0, MAX_CHARS_LIMIT),
            minGapMs = minGapMs.coerceIn(0, MAX_GAP_MS),
            maxGapMs = gap.coerceIn(0, MAX_GAP_MS),
            msPerChar = msPerChar.coerceIn(0, 1_000),
            budgetWindowSec = budgetWindowSec.coerceIn(0, 3_600),
            budgetChars = budgetChars.coerceIn(0, 20_000)
        )
    }

    companion object {
        const val MAX_SEGMENTS_LIMIT = 6
        const val MAX_CHARS_LIMIT = 4_000
        const val MAX_GAP_MS = 10_000
    }
}

/**
 * 主动发起：不等对方说话就先开口。
 *
 * 两个触发源互相独立：
 * - **静默续话**（[idleMinutes]）：会话最后一条消息（不分方向）距今超过阈值就接着聊；
 * - **定时打招呼**（[scheduleTimes]）：每天固定的几个时间点开口。
 *
 * 都不要求「最后一条必须是对方说的」——项目使用者明确要这个自由度；
 * 防止自问自答靠的是：发完主动消息会刷新会话时间，`idleMinutes` 会重新计时，
 * 加上 [maxPerHour] / [maxPerDay] / [minIntervalPerConvHours] 三重配额。
 */
@Serializable
data class ProactiveConfig(
    val enabled: Boolean = false,

    /** 静默续话的阈值（分钟）；0 = 关闭静默续话。 */
    val idleMinutes: Int = 90,

    /** 定时打招呼的时间点，英文逗号分隔的 `HH:mm`，例如 `09:00,22:30`。空 = 关闭。 */
    val scheduleTimes: String = "",

    /** 定时打招呼的提示词；空则用内置默认（自然地问候，不套模板）。 */
    val schedulePrompt: String = "",

    /** 每账号每小时最多主动发几条。 */
    val maxPerHour: Int = 1,

    /** 每账号每天最多主动发几条。 */
    val maxPerDay: Int = 3,

    /** 同一会话两次主动发起的最小间隔（小时）。 */
    val minIntervalPerConvHours: Int = 12,

    /** 免打扰开始时刻（小时，含），跨零点用 end < start 表示。 */
    val quietStartHour: Int = 23,

    /** 免打扰结束时刻（小时，不含）。 */
    val quietEndHour: Int = 8,

    /** 是否允许在群聊里主动开口。默认否：群里主动说话风险最高。 */
    val allowInGroup: Boolean = false,

    /** 只对最近这些天有过往来的会话发起。 */
    val activeWithinDays: Int = 7
) {
    /** 解析后的时间点（分钟数，0..1439），去重排序。 */
    val scheduleMinuteOfDay: List<Int>
        get() = scheduleTimes.split(',', '，', ' ')
            .mapNotNull { raw ->
                val parts = raw.trim().split(':')
                if (parts.size != 2) return@mapNotNull null
                val hour = parts[0].toIntOrNull() ?: return@mapNotNull null
                val minute = parts[1].toIntOrNull() ?: return@mapNotNull null
                if (hour !in 0..23 || minute !in 0..59) null else hour * 60 + minute
            }
            .distinct()
            .sorted()

    fun normalized(): ProactiveConfig = copy(
        idleMinutes = idleMinutes.coerceIn(0, 7 * 24 * 60),
        maxPerHour = maxPerHour.coerceIn(0, 60),
        maxPerDay = maxPerDay.coerceIn(0, 500),
        minIntervalPerConvHours = minIntervalPerConvHours.coerceIn(0, 7 * 24),
        quietStartHour = quietStartHour.coerceIn(0, 23),
        quietEndHour = quietEndHour.coerceIn(0, 23),
        activeWithinDays = activeWithinDays.coerceIn(1, 90)
    )
}

/**
 * 会话级与账号级限流。
 *
 * **默认开启，这是三项新能力里唯一「默认不等于旧行为」的一项**——装上新版本后，
 * 使用者不点任何按钮，行为就已经从「每条消息都回一次」变成「冷却期内的消息合并回一次」。
 * 这个取舍是刻意的，且有明确理由：
 * - 它不改变「回什么」，只约束「多久回、回多少」，属于护栏而不是新话术，误判成本低；
 * - 若默认关闭，等于把「防刷屏 / 防风控」这个诉求本身藏进二级设置，多数使用者不会去打开，
 *   而刷屏与风控恰恰是本项目最需要规避的失败模式；
 * - 恢复路径很短：把「会话冷却」设为 0 即逐条回复，其余三项可分别调大或关闭。
 *
 * 因此**不要**因为「默认值应与旧行为一致」这条一般原则而把它改回 `false`；
 * `BehaviorConfigTest.防刷屏保护默认开启` 是锁这条决定的断言。
 */
@Serializable
data class ThrottleConfig(
    val enabled: Boolean = true,

    /** 同一会话两次自动回复之间的冷却（秒）。冷却期内到达的消息合并到下一次回复。 */
    val convCooldownSeconds: Int = 3,

    /** 每账号每小时发送上限，含被动回复与主动发起。0 = 不限。 */
    val accountMaxPerHour: Int = 60,

    /** 连续发送失败达到这个次数后，账号进入退避。 */
    val failBackoffThreshold: Int = 3,

    /** 退避时长（分钟）。期间不主动发起，被动回复只入库。 */
    val failBackoffMinutes: Int = 10,

    /** 文本指纹去重窗口（分钟）：同一会话相同内容不重复发。 */
    val dedupeWindowMinutes: Int = 10
) {
    fun normalized(): ThrottleConfig = copy(
        convCooldownSeconds = convCooldownSeconds.coerceIn(0, 300),
        accountMaxPerHour = accountMaxPerHour.coerceIn(0, 2_000),
        failBackoffThreshold = failBackoffThreshold.coerceIn(1, 20),
        failBackoffMinutes = failBackoffMinutes.coerceIn(1, 240),
        dedupeWindowMinutes = dedupeWindowMinutes.coerceIn(0, 240)
    )
}
