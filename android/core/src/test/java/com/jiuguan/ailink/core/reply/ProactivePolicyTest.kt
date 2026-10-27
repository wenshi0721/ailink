package com.jiuguan.ailink.core.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProactivePolicy] 的边界：这是「机器人主动开口」唯一的闸门，
 * 每一条筛掉的条件都对应一种真实的骚扰/风控风险，所以逐条锁住。
 */
class ProactivePolicyTest {

    private val now = 1_700_000_000_000L
    private val dayKey = "2026-10-27"

    private fun candidate(
        convId: String = "c1",
        isGroup: Boolean = false,
        silenceMinutes: Long = 100,
        lastProactiveAt: Long = 0L,
        twoWay: Boolean = true
    ) = ProactiveCandidate(
        convId = convId,
        isGroup = isGroup,
        lastMessageAt = now - silenceMinutes * 60_000L,
        lastProactiveAt = lastProactiveAt,
        twoWay = twoWay
    )

    private fun outcome(
        config: ProactiveConfig = ProactiveConfig(enabled = true),
        candidates: List<ProactiveCandidate> = listOf(candidate()),
        hour: Int = 10,
        minute: Int = 0,
        usedThisHour: Int = 0,
        usedToday: Int = 0,
        fired: Set<String> = emptySet(),
        day: String = dayKey
    ) = ProactivePolicy.decide(
        now = now,
        hourOfDay = hour,
        minuteOfDay = hour * 60 + minute,
        dayKey = day,
        config = config,
        candidates = candidates,
        usedThisHour = usedThisHour,
        usedToday = usedToday,
        firedSlots = fired
    )

    /** 只关心「发不发、发给谁」时用这个，跳过原因见 [skipReason]。 */
    private fun decide(
        config: ProactiveConfig = ProactiveConfig(enabled = true),
        candidates: List<ProactiveCandidate> = listOf(candidate()),
        hour: Int = 10,
        minute: Int = 0,
        usedThisHour: Int = 0,
        usedToday: Int = 0,
        fired: Set<String> = emptySet(),
        day: String = dayKey
    ): ProactiveDecision? = (
        outcome(config, candidates, hour, minute, usedThisHour, usedToday, fired, day)
            as? ProactiveOutcome.Fire
        )?.decision

    private fun skipReason(
        config: ProactiveConfig = ProactiveConfig(enabled = true),
        candidates: List<ProactiveCandidate> = listOf(candidate()),
        hour: Int = 10,
        minute: Int = 0,
        usedThisHour: Int = 0,
        usedToday: Int = 0
    ): String? = (
        outcome(config, candidates, hour, minute, usedThisHour, usedToday)
            as? ProactiveOutcome.Skip
        )?.reason

    // ---------------------------------------------------------------- 开关与时段

    @Test
    fun `开关关闭时永不发起`() {
        assertNull(decide(config = ProactiveConfig(enabled = false)))
    }

    @Test
    fun `免打扰时段覆盖跨零点`() {
        val config = ProactiveConfig(quietStartHour = 23, quietEndHour = 8)
        assertTrue(ProactivePolicy.isQuiet(23, config))
        assertTrue(ProactivePolicy.isQuiet(0, config))
        assertTrue(ProactivePolicy.isQuiet(7, config))
        assertTrue("8 点整应当结束免打扰", !ProactivePolicy.isQuiet(8, config))
        assertTrue(!ProactivePolicy.isQuiet(15, config))
    }

    @Test
    fun `免打扰时段在同一天内`() {
        val config = ProactiveConfig(quietStartHour = 13, quietEndHour = 15)
        assertTrue(ProactivePolicy.isQuiet(13, config))
        assertTrue(ProactivePolicy.isQuiet(14, config))
        assertTrue(!ProactivePolicy.isQuiet(15, config))
        assertTrue(!ProactivePolicy.isQuiet(12, config))
    }

    @Test
    fun `起止相同等于不设免打扰`() {
        val config = ProactiveConfig(quietStartHour = 0, quietEndHour = 0)
        assertTrue(!ProactivePolicy.isQuiet(0, config))
        assertTrue(!ProactivePolicy.isQuiet(12, config))
    }

    @Test
    fun `免打扰时段内不发起`() {
        val config = ProactiveConfig(enabled = true, quietStartHour = 23, quietEndHour = 8)
        assertNull(decide(config = config, hour = 23, minute = 30))
        assertNull(decide(config = config, hour = 3, minute = 0))
    }

    // ---------------------------------------------------------------- 配额

    @Test
    fun `小时配额用尽后不发起`() {
        val config = ProactiveConfig(enabled = true, maxPerHour = 1, maxPerDay = 3)
        assertNull(decide(config = config, usedThisHour = 1))
    }

    @Test
    fun `每日配额用尽后不发起`() {
        val config = ProactiveConfig(enabled = true, maxPerHour = 5, maxPerDay = 3)
        assertNull(decide(config = config, usedToday = 3))
    }

    @Test
    fun `配额设为 0 表示不限`() {
        val config = ProactiveConfig(enabled = true, maxPerHour = 0, maxPerDay = 0)
        val decision = decide(config = config, usedThisHour = 99, usedToday = 999)
        assertEquals(ProactiveKind.IDLE, decision?.kind)
    }

    // ---------------------------------------------------------------- 静默续话

    @Test
    fun `静默超过阈值时续话`() {
        val config = ProactiveConfig(enabled = true, idleMinutes = 90)
        val decision = decide(config = config, candidates = listOf(candidate(silenceMinutes = 100)))
        assertEquals(ProactiveKind.IDLE, decision?.kind)
        assertEquals("c1", decision?.convId)
        assertTrue("原因里应带静默时长：${decision?.reason}", decision!!.reason.contains("100"))
    }

    @Test
    fun `静默不足阈值时不发起`() {
        val config = ProactiveConfig(enabled = true, idleMinutes = 90)
        assertNull(decide(config = config, candidates = listOf(candidate(silenceMinutes = 30))))
    }

    @Test
    fun `久未往来的会话不打扰`() {
        val config = ProactiveConfig(enabled = true, idleMinutes = 90, activeWithinDays = 7)
        // 8 天没说话：翻出这种会话打招呼最像机器人
        assertNull(decide(config = config, candidates = listOf(candidate(silenceMinutes = 8 * 24 * 60))))
    }

    @Test
    fun `只挑最近还热络的那一个`() {
        val config = ProactiveConfig(enabled = true, idleMinutes = 90)
        val decision = decide(
            config = config,
            candidates = listOf(
                candidate(convId = "old", silenceMinutes = 5_000),
                candidate(convId = "recent", silenceMinutes = 120)
            )
        )
        assertEquals("recent", decision?.convId)
    }

    @Test
    fun `同一会话两次主动发起之间要有间隔`() {
        val config = ProactiveConfig(enabled = true, idleMinutes = 90, minIntervalPerConvHours = 12)
        val decision = decide(
            config = config,
            candidates = listOf(candidate(lastProactiveAt = now - 10 * 3_600_000L))
        )
        assertNull("10 小时前刚主动过，不到 12 小时不该再来一次", decision)
    }

    @Test
    fun `间隔已过可以再次主动`() {
        val config = ProactiveConfig(enabled = true, idleMinutes = 90, minIntervalPerConvHours = 12)
        val decision = decide(
            config = config,
            candidates = listOf(candidate(lastProactiveAt = now - 13 * 3_600_000L))
        )
        assertEquals(ProactiveKind.IDLE, decision?.kind)
    }

    // ---------------------------------------------------------------- 候选筛选

    @Test
    fun `没有双向历史的会话不发起`() {
        val config = ProactiveConfig(enabled = true, idleMinutes = 90)
        assertNull(decide(config = config, candidates = listOf(candidate(twoWay = false))))
    }

    @Test
    fun `群聊默认不主动`() {
        val config = ProactiveConfig(enabled = true, idleMinutes = 90)
        assertNull(decide(config = config, candidates = listOf(candidate(isGroup = true))))
    }

    @Test
    fun `打开开关后群聊也可以主动`() {
        val config = ProactiveConfig(enabled = true, idleMinutes = 90, allowInGroup = true)
        val decision = decide(config = config, candidates = listOf(candidate(isGroup = true)))
        assertEquals("c1", decision?.convId)
    }

    @Test
    fun `没有会话时候选为空`() {
        assertNull(decide(candidates = emptyList()))
    }

    // ---------------------------------------------------------------- 定时打招呼

    @Test
    fun `到达设定时间点触发定时打招呼`() {
        val config = ProactiveConfig(enabled = true, scheduleTimes = "09:00")
        val decision = decide(config = config, hour = 9, minute = 0)
        assertEquals(ProactiveKind.SCHEDULED, decision?.kind)
        assertEquals(ProactivePolicy.slotKey(dayKey, 9 * 60), decision?.slotKey)
    }

    @Test
    fun `定时点有容忍窗口`() {
        // idleMinutes = 0 关掉静默续话，只看定时这条路径
        val config = ProactiveConfig(enabled = true, scheduleTimes = "09:00", idleMinutes = 0)
        assertEquals(
            ProactiveKind.SCHEDULED,
            decide(config = config, hour = 9, minute = 1)?.kind
        )
        // 超出容忍窗口就不再算这个时间点
        assertNull(decide(config = config, hour = 9, minute = 5))
    }

    @Test
    fun `同一天同一个时间点只触发一次`() {
        val config = ProactiveConfig(enabled = true, scheduleTimes = "09:00")
        val key = ProactivePolicy.slotKey(dayKey, 9 * 60)
        val decision = decide(config = config, hour = 9, minute = 0, fired = setOf(key))
        // 已经打过招呼，退回静默续话逻辑（此时候选静默 100 分钟 > 90）
        assertEquals(ProactiveKind.IDLE, decision?.kind)
    }

    @Test
    fun `换一天同一个时间点可以再触发`() {
        val config = ProactiveConfig(enabled = true, scheduleTimes = "09:00")
        val yesterdayKey = ProactivePolicy.slotKey("2026-10-26", 9 * 60)
        val decision = decide(config = config, hour = 9, minute = 0, fired = setOf(yesterdayKey))
        assertEquals(ProactiveKind.SCHEDULED, decision?.kind)
    }

    @Test
    fun `定时优先于静默续话`() {
        val config = ProactiveConfig(enabled = true, scheduleTimes = "09:00", idleMinutes = 90)
        val decision = decide(config = config, hour = 9, minute = 0)
        assertEquals(ProactiveKind.SCHEDULED, decision?.kind)
    }

    @Test
    fun `时间点格式非法时忽略该项`() {
        val config = ProactiveConfig(enabled = true, scheduleTimes = "9点,25:00,09:xx", idleMinutes = 0)
        assertTrue(config.scheduleMinuteOfDay.isEmpty())
        assertNull(decide(config = config, hour = 9, minute = 0))
    }

    @Test
    fun `多个时间点全部生效`() {
        val config = ProactiveConfig(enabled = true, scheduleTimes = "09:00, 21:30")
        assertEquals(listOf(540, 1_290), config.scheduleMinuteOfDay)
        assertEquals(ProactiveKind.SCHEDULED, decide(config = config, hour = 21, minute = 30)?.kind)
    }

    // ---------------------------------------------------------------- 可诊断性

    @Test
    fun `不发起时给出可读原因`() {
        assertEquals("开关关闭", skipReason(config = ProactiveConfig(enabled = false)))

        val quiet = skipReason(
            config = ProactiveConfig(enabled = true, quietStartHour = 23, quietEndHour = 8),
            hour = 23
        )
        assertTrue("应说明是免打扰：$quiet", quiet!!.contains("免打扰"))

        val quota = skipReason(
            config = ProactiveConfig(enabled = true, maxPerHour = 1),
            usedThisHour = 1
        )
        assertTrue("应说明是配额：$quota", quota!!.contains("小时配额"))

        assertTrue(skipReason(candidates = emptyList())!!.contains("没有可用"))

        val noEligible = skipReason(candidates = listOf(candidate(twoWay = false)))
        assertTrue("应说明候选被筛掉：$noEligible", noEligible!!.contains("无双向历史"))
    }
}
