package com.jiuguan.ailink.core.reply

import java.util.concurrent.ConcurrentHashMap

/** 会话所处阶段，只用于日志与排查，不对外暴露。 */
enum class ConvPhase(val label: String) {
    IDLE("空闲"),
    ACTIVE("回复中"),
    COOLING("冷却中"),
    MUTED("退避中")
}

/** [SessionGovernor.claimTurn] 的结论。 */
data class ClaimResult(
    val granted: Boolean,
    /** 被本轮一并回复的其它消息条数（冷却期内到达的）。 */
    val merged: Int = 0,
    /** 因为持续有新消息、始终等不到安静窗口而强制回复一次。 */
    val starved: Boolean = false
)

/**
 * 会话状态与限流。
 *
 * 三个职责，按「会话 → 账号」收口：
 *
 * 1. **冷却合并**：刚回过一句话，紧接着又来了几条，不该每条都单独回一遍。
 *    冷却期内到达的消息不丢、不丢弃式跳过，而是让后到的那条统一回复——
 *    因为 `buildTurns` 每次都是从库里拉最近 N 条，后来者天然看得到前面所有排队的话。
 * 2. **账号配额**：每小时发送条数上限。这个数字由调用方从数据库统计后传进来，
 *    所以进程重启不会把配额清零（否则「上限」形同虚设）。
 * 3. **失败退避**：连续发送失败到阈值就停手一段时间。连续失败通常意味着
 *    连接已断或账号已被风控，继续重试只会加重伤害。这是对原有
 *    `sendText` 返回 false 的**增强**，不替换它的错误处理。
 *
 * 除配额数字外，其余状态都在内存里——它们描述的是"刚刚发生了什么"，
 * 进程重启后清零是期望行为。
 */
class SessionGovernor(
    private val config: ThrottleConfig = ThrottleConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private class Fingerprint(val at: Long, val hash: Long)

    private class ConvSlot {
        /** 收到消息的单调序号，用来判断"我这条是不是最新的"。 */
        var seq: Long = 0L

        /** 上一次成功发出的时间，冷却从这里开始算。 */
        var lastReplyAt: Long = 0L

        /** 正在生成/发送中。 */
        var active: Boolean = false

        /** 自上次回复以来累积的未回复消息数。 */
        var pending: Int = 0

        /** 最早一条待回复消息的到达时间，用于合并超时兜底。 */
        var pendingSince: Long = 0L

        val recent = ArrayDeque<Fingerprint>()
    }

    private val convs = ConcurrentHashMap<String, ConvSlot>()

    private var consecutiveFailures = 0

    private var mutedUntil = 0L

    private fun slotOf(convId: String): ConvSlot = convs.getOrPut(convId) { ConvSlot() }

    // ---------------------------------------------------------------- 会话级

    /** 登记一条待回复消息，返回它的排队序号。必须在拿会话锁之前调用。 */
    fun noteIncoming(convId: String): Long {
        val slot = slotOf(convId)
        return synchronized(slot) {
            slot.seq += 1
            slot.pending += 1
            if (slot.pendingSince == 0L) slot.pendingSince = clock()
            slot.seq
        }
    }

    /** 还要等多久才能回下一条；0 表示可以立刻回。 */
    fun cooldownRemainingMs(convId: String): Long {
        if (!config.enabled) return 0L
        val cooldown = config.convCooldownSeconds * 1_000L
        if (cooldown <= 0L) return 0L
        val slot = convs[convId] ?: return 0L
        return synchronized(slot) {
            if (slot.lastReplyAt <= 0L) 0L
            else (cooldown - (clock() - slot.lastReplyAt)).coerceAtLeast(0L)
        }
    }

    /**
     * 冷却等待结束后，判断这一条是否该由它发起回复。
     *
     * 没被批准不代表消息被丢弃——它已经入库，被批准的那一条会把上下文一起带上。
     */
    fun claimTurn(convId: String, seq: Long): ClaimResult {
        val slot = slotOf(convId)
        return synchronized(slot) {
            val now = clock()
            val isLatest = slot.seq == seq
            // 一直有人在说话时不能无限期让位，否则活跃群里永远不会回
            val starved = config.enabled && slot.pendingSince > 0L &&
                now - slot.pendingSince >= MERGE_DEADLINE_MS
            if (!config.enabled || isLatest || starved) {
                val merged = (slot.pending - 1).coerceAtLeast(0)
                slot.pending = 0
                slot.pendingSince = 0L
                slot.active = true
                ClaimResult(granted = true, merged = merged, starved = starved && !isLatest)
            } else {
                ClaimResult(granted = false)
            }
        }
    }

    /** 一轮回复结束。[sentAt] 为最后一条成功发出的时间，0 表示一条都没发出去。 */
    fun finishTurn(convId: String, sentAt: Long) {
        val slot = convs[convId] ?: return
        synchronized(slot) {
            slot.active = false
            if (sentAt > 0L) slot.lastReplyAt = sentAt
        }
    }

    /** 当前阶段，用于日志。 */
    fun phaseOf(convId: String): ConvPhase {
        if (mutedRemainingMs() > 0L) return ConvPhase.MUTED
        val slot = convs[convId] ?: return ConvPhase.IDLE
        return synchronized(slot) {
            when {
                slot.active -> ConvPhase.ACTIVE
                cooldownRemainingMs(convId) > 0L -> ConvPhase.COOLING
                else -> ConvPhase.IDLE
            }
        }
    }

    /** 会话被清空/删除时丢掉它的状态。 */
    fun forget(convId: String) {
        convs.remove(convId)
    }

    // ---------------------------------------------------------------- 内容去重

    /**
     * 这段文字最近是否已经在这个会话里发过。
     *
     * 只对「有意义长度」的文本生效：`嗯`、`好` 这类重复是自然表达，
     * 压掉反而不像人（[MIN_DEDUPE_CHARS]）。
     */
    fun isDuplicate(convId: String, text: String): Boolean {
        if (!config.enabled) return false
        val windowMs = config.dedupeWindowMinutes * 60_000L
        if (windowMs <= 0L) return false

        val fingerprint = fingerprintOf(text) ?: return false
        val slot = convs[convId] ?: return false
        return synchronized(slot) {
            pruneFingerprints(slot, clock() - windowMs)
            slot.recent.any { it.hash == fingerprint }
        }
    }

    /** 记录一条已成功发出的内容，供 [isDuplicate] 判定。 */
    fun rememberSent(convId: String, text: String) {
        if (!config.enabled) return
        val windowMs = config.dedupeWindowMinutes * 60_000L
        if (windowMs <= 0L) return
        val fingerprint = fingerprintOf(text) ?: return

        val slot = slotOf(convId)
        synchronized(slot) {
            val now = clock()
            pruneFingerprints(slot, now - windowMs)
            slot.recent.addLast(Fingerprint(now, fingerprint))
        }
    }

    private fun pruneFingerprints(slot: ConvSlot, cutoff: Long) {
        while (slot.recent.isNotEmpty() && slot.recent.first().at < cutoff) {
            slot.recent.removeFirst()
        }
    }

    /**
     * 归一化后取指纹：忽略标点、空格与大小写的差异。
     * 太短（少于 [MIN_DEDUPE_CHARS] 个有效字符）返回 null，表示不参与去重。
     */
    private fun fingerprintOf(text: String): Long? {
        var hash = 1_125_899_906_842_597L
        var count = 0
        for (ch in text) {
            if (!ch.isLetterOrDigit()) continue
            hash = 31 * hash + ch.lowercaseChar().code
            count++
        }
        if (count < MIN_DEDUPE_CHARS) return null
        return hash * 31 + count
    }

    // ---------------------------------------------------------------- 账号级

    /** 退避剩余时长；0 表示正常。 */
    fun mutedRemainingMs(): Long {
        if (!config.enabled) return 0L
        return synchronized(this) { (mutedUntil - clock()).coerceAtLeast(0L) }
    }

    /** 记录一次发送结果，连续失败到阈值就退避。 */
    fun recordSendResult(success: Boolean) {
        if (!config.enabled) return
        synchronized(this) {
            if (success) {
                consecutiveFailures = 0
                mutedUntil = 0L
                return
            }
            consecutiveFailures += 1
            if (consecutiveFailures >= config.failBackoffThreshold) {
                // 计数归零：退避结束后的第一把就再失败，才算新一轮的连续失败
                consecutiveFailures = 0
                mutedUntil = clock() + config.failBackoffMinutes * 60_000L
            }
        }
    }

    /**
     * 小时配额是否还有余量。
     *
     * @param sentInLastHour 调用方从库里统计的近一小时成功发送条数（跨重启有效）
     */
    fun withinHourlyQuota(sentInLastHour: Int): Boolean {
        if (!config.enabled) return true
        val limit = config.accountMaxPerHour
        return limit <= 0 || sentInLastHour < limit
    }

    val hourlyQuota: Int get() = if (config.enabled) config.accountMaxPerHour else 0

    val enabled: Boolean get() = config.enabled

    companion object {
        /** 有效字符少于这个数不参与去重（"嗯""好""哈哈"重复是自然的）。 */
        const val MIN_DEDUPE_CHARS = 4

        /** 一直有新消息时，最多让位这么久就强制回一次，避免活跃会话被饿死。 */
        const val MERGE_DEADLINE_MS = 60_000L
    }
}
