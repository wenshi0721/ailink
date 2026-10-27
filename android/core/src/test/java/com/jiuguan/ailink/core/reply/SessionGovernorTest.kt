package com.jiuguan.ailink.core.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SessionGovernor] 的行为边界：冷却合并、去重、配额、失败退避。 */
class SessionGovernorTest {

    private var now = 1_700_000_000_000L

    private fun governor(config: ThrottleConfig = ThrottleConfig()) =
        SessionGovernor(config, clock = { now })

    // ---------------------------------------------------------------- 冷却

    @Test
    fun `回复成功后进入冷却，随时间递减到 0`() {
        val g = governor(ThrottleConfig(convCooldownSeconds = 3))
        val seq = g.noteIncoming("c1")
        assertTrue(g.claimTurn("c1", seq).granted)
        g.finishTurn("c1", sentAt = now)

        assertEquals(3_000L, g.cooldownRemainingMs("c1"))
        now += 1_000L
        assertEquals(2_000L, g.cooldownRemainingMs("c1"))
        now += 2_000L
        assertEquals(0L, g.cooldownRemainingMs("c1"))
    }

    @Test
    fun `一条都没发出去时不产生冷却`() {
        val g = governor(ThrottleConfig(convCooldownSeconds = 3))
        val seq = g.noteIncoming("c1")
        g.claimTurn("c1", seq)
        g.finishTurn("c1", sentAt = 0L)
        assertEquals(0L, g.cooldownRemainingMs("c1"))
    }

    // ---------------------------------------------------------------- 合并

    @Test
    fun `冷却期内连来三条，只有最新那条发起回复`() {
        val g = governor(ThrottleConfig(convCooldownSeconds = 3))
        val first = g.noteIncoming("c1")
        val second = g.noteIncoming("c1")
        val third = g.noteIncoming("c1")

        // 前两条在拿到会话锁后发现"已经有人更新"，让位
        assertFalse(g.claimTurn("c1", first).granted)
        assertFalse(g.claimTurn("c1", second).granted)

        val claim = g.claimTurn("c1", third)
        assertTrue(claim.granted)
        assertEquals(2, claim.merged)
        assertFalse(claim.starved)
    }

    @Test
    fun `一直有人说话时超过 60 秒强制回复一次`() {
        val g = governor(ThrottleConfig(convCooldownSeconds = 3))
        val first = g.noteIncoming("c1")
        now += SessionGovernor.MERGE_DEADLINE_MS + 1
        g.noteIncoming("c1")

        val claim = g.claimTurn("c1", first)
        assertTrue("不能因为对方一直说话就永远不回", claim.granted)
        assertTrue(claim.starved)
    }

    @Test
    fun `回复完成后新的消息可以正常发起下一轮`() {
        val g = governor(ThrottleConfig(convCooldownSeconds = 3))
        val first = g.noteIncoming("c1")
        g.claimTurn("c1", first)
        g.finishTurn("c1", sentAt = now)

        now += 3_000L // 冷却已过
        val second = g.noteIncoming("c1")
        val claim = g.claimTurn("c1", second)
        assertTrue(claim.granted)
        assertEquals(0, claim.merged)
    }

    @Test
    fun `不同会话互不影响`() {
        val g = governor(ThrottleConfig(convCooldownSeconds = 30))
        val a = g.noteIncoming("cA")
        g.claimTurn("cA", a)
        g.finishTurn("cA", sentAt = now)

        assertEquals(30_000L, g.cooldownRemainingMs("cA"))
        assertEquals(0L, g.cooldownRemainingMs("cB"))

        val b = g.noteIncoming("cB")
        assertTrue(g.claimTurn("cB", b).granted)
    }

    // ---------------------------------------------------------------- 去重

    @Test
    fun `同一句话在窗口内不重复发送`() {
        val g = governor(ThrottleConfig(dedupeWindowMinutes = 10))
        g.rememberSent("c1", "今天天气不错，一起出去走走？")
        assertTrue(g.isDuplicate("c1", "今天天气不错，一起出去走走？"))
    }

    @Test
    fun `标点与空格差异不影响去重`() {
        val g = governor(ThrottleConfig(dedupeWindowMinutes = 10))
        g.rememberSent("c1", "今天天气不错，一起出去走走？")
        assertTrue(g.isDuplicate("c1", "今天天气不错 一起出去走走"))
    }

    @Test
    fun `超过窗口后不再判重`() {
        val g = governor(ThrottleConfig(dedupeWindowMinutes = 10))
        g.rememberSent("c1", "今天天气不错，一起出去走走？")
        now += 10 * 60_000L + 1
        assertFalse(g.isDuplicate("c1", "今天天气不错，一起出去走走？"))
    }

    @Test
    fun `去重不跨会话`() {
        val g = governor(ThrottleConfig(dedupeWindowMinutes = 10))
        g.rememberSent("c1", "今天天气不错，一起出去走走？")
        assertFalse(g.isDuplicate("c2", "今天天气不错，一起出去走走？"))
    }

    @Test
    fun `短句重复不算复读`() {
        val g = governor(ThrottleConfig(dedupeWindowMinutes = 10))
        g.rememberSent("c1", "嗯嗯")
        assertFalse("「嗯嗯」这种回应重复是自然的", g.isDuplicate("c1", "嗯嗯"))
    }

    @Test
    fun `去重窗口设为 0 时关闭`() {
        val g = governor(ThrottleConfig(dedupeWindowMinutes = 0))
        g.rememberSent("c1", "今天天气不错，一起出去走走？")
        assertFalse(g.isDuplicate("c1", "今天天气不错，一起出去走走？"))
    }

    // ---------------------------------------------------------------- 失败退避

    @Test
    fun `连续失败到阈值后进入退避，到期自动解除`() {
        val g = governor(ThrottleConfig(failBackoffThreshold = 3, failBackoffMinutes = 10))
        repeat(2) { g.recordSendResult(false) }
        assertEquals("未到阈值不应退避", 0L, g.mutedRemainingMs())

        g.recordSendResult(false)
        assertEquals(10 * 60_000L, g.mutedRemainingMs())

        now += 10 * 60_000L
        assertEquals(0L, g.mutedRemainingMs())
    }

    @Test
    fun `失败一次成功后计数清零`() {
        val g = governor(ThrottleConfig(failBackoffThreshold = 3))
        g.recordSendResult(false)
        g.recordSendResult(false)
        g.recordSendResult(true)
        g.recordSendResult(false)
        assertEquals("成功一次应清零，否则两次失败就退避了", 0L, g.mutedRemainingMs())
    }

    // ---------------------------------------------------------------- 配额

    @Test
    fun `小时配额上限生效`() {
        val g = governor(ThrottleConfig(accountMaxPerHour = 60))
        assertTrue(g.withinHourlyQuota(59))
        assertFalse(g.withinHourlyQuota(60))
        assertFalse(g.withinHourlyQuota(61))
    }

    @Test
    fun `配额设为 0 表示不限`() {
        val g = governor(ThrottleConfig(accountMaxPerHour = 0))
        assertTrue(g.withinHourlyQuota(100_000))
    }

    // ---------------------------------------------------------------- 关闭开关

    @Test
    fun `关闭限流后一切回到不干预状态`() {
        val g = governor(ThrottleConfig(enabled = false, convCooldownSeconds = 30))

        val first = g.noteIncoming("c1")
        g.claimTurn("c1", first)
        g.finishTurn("c1", sentAt = now)
        assertEquals("关闭后不应有冷却", 0L, g.cooldownRemainingMs("c1"))

        // 即便不是最新那条也照常回复，等同于改造前「每条都回」
        val stale = g.noteIncoming("c1")
        g.noteIncoming("c1")
        assertTrue(g.claimTurn("c1", stale).granted)

        repeat(5) { g.recordSendResult(false) }
        assertEquals(0L, g.mutedRemainingMs())
        assertTrue(g.withinHourlyQuota(10_000))
        assertFalse(g.isDuplicate("c1", "随便一句足够长的话"))
    }

    @Test
    fun `会话被遗忘后状态归零`() {
        val g = governor()
        val seq = g.noteIncoming("c1")
        g.claimTurn("c1", seq)
        g.finishTurn("c1", sentAt = now)
        g.forget("c1")
        assertEquals(0L, g.cooldownRemainingMs("c1"))
        assertEquals(ConvPhase.IDLE, g.phaseOf("c1"))
    }
}
