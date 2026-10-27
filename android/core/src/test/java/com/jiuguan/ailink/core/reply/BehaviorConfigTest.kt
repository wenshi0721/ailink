package com.jiuguan.ailink.core.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为配置的解析容错。
 *
 * 这段 JSON 存在 SharedPreferences 里，可能被旧版本残留、手改、半截写入弄脏。
 * 任何解析失败都必须退回「全关」的默认值，绝不能因为配置坏了就把回复链路弄瘫。
 */
class BehaviorConfigTest {

    @Test
    fun `空串解析为全默认`() {
        val config = BehaviorConfig.decode("")
        assertEquals(BehaviorConfig.DEFAULT, config)
        assertFalse(config.multi.enabled)
        assertFalse(config.proactive.enabled)
    }

    @Test
    fun `坏 JSON 解析为全默认`() {
        assertEquals(BehaviorConfig.DEFAULT, BehaviorConfig.decode("{这不是 JSON"))
        assertEquals(BehaviorConfig.DEFAULT, BehaviorConfig.decode("[1,2,3]"))
    }

    @Test
    fun `编码解码往返一致`() {
        val original = BehaviorConfig(
            multi = MultiReplyConfig(enabled = true, maxSegments = 4, budgetChars = 900),
            proactive = ProactiveConfig(enabled = true, scheduleTimes = "09:00,22:30"),
            throttle = ThrottleConfig(convCooldownSeconds = 5)
        )
        assertEquals(original, BehaviorConfig.decode(BehaviorConfig.encode(original)))
    }

    @Test
    fun `越界值在解析时被钳制`() {
        val config = BehaviorConfig.decode(
            """{"multi":{"enabled":true,"maxSegments":99,"minGapMs":5000,"maxGapMs":100}}"""
        )
        assertEquals(MultiReplyConfig.MAX_SEGMENTS_LIMIT, config.multi.maxSegments)
        // 上限被拉到不小于下限，避免 coerceIn 抛异常
        assertTrue(config.multi.maxGapMs >= config.multi.minGapMs)
    }

    @Test
    fun `未知字段被忽略`() {
        val config = BehaviorConfig.decode("""{"multi":{"enabled":true,"futureOption":42}}""")
        assertTrue(config.multi.enabled)
    }

    @Test
    fun `默认配置下分段不生效`() {
        assertFalse(BehaviorConfig.DEFAULT.multi.splitting)
        assertFalse(BehaviorConfig.DEFAULT.multi.budgetActive)
        // 默认值必须与改造前硬编码的 600 一致
        assertEquals(600, BehaviorConfig.DEFAULT.multi.totalMaxChars)
    }

    @Test
    fun `条数上限为 1 时不算分段`() {
        val config = MultiReplyConfig(enabled = true, maxSegments = 1)
        assertFalse(config.splitting)
    }

    /**
     * 这是一处**有意偏离旧行为**的默认值，不要因为「默认值应与旧行为一致」而改回 false。
     *
     * 三项新能力里只有它默认开启：多条回复与主动发起默认关闭（升级后完全无感），而防刷屏护栏
     * 若默认关闭，等于把「防刷屏 / 防风控」这个诉求本身藏进二级设置，绝大多数使用者不会去打开。
     * 代价是升级后「连发三条只回一次」，由日志 `[throttle]` 解释。
     */
    @Test
    fun `防刷屏保护默认开启`() {
        assertTrue(BehaviorConfig.DEFAULT.throttle.enabled)
        // 旧账号没有 behavior_ 键，读到空串必须落到「开启」，否则升级后护栏等于没有
        assertTrue(BehaviorConfig.decode("").throttle.enabled)

        val throttle = BehaviorConfig.DEFAULT.throttle
        assertEquals(3, throttle.convCooldownSeconds)
        assertEquals(60, throttle.accountMaxPerHour)
        assertEquals(3, throttle.failBackoffThreshold)
        assertEquals(10, throttle.failBackoffMinutes)
        assertEquals(10, throttle.dedupeWindowMinutes)
    }

    @Test
    fun `定时时间点解析并过滤非法值`() {
        val proactive = ProactiveConfig(scheduleTimes = "09:00, 22:30,25:00,abc,09:00")
        assertEquals(listOf(9 * 60, 22 * 60 + 30), proactive.scheduleMinuteOfDay)
    }

    @Test
    fun `空定时列表表示不定时`() {
        assertTrue(ProactiveConfig().scheduleMinuteOfDay.isEmpty())
    }
}
