package com.jiuguan.ailink.core.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 发送节奏与输出账本的回归保护。 */
class ReplyPacingTest {

    private val config = MultiReplyConfig(
        minGapMs = 900,
        maxGapMs = 4_000,
        msPerChar = 200
    )

    @Test
    fun `短句的间隔被下限兜住`() {
        val gap = ReplyPacing.gapMs("嗯", config, Random(7))
        assertEquals(900L, gap)
    }

    @Test
    fun `长段落的间隔被上限兜住`() {
        val gap = ReplyPacing.gapMs("字".repeat(80), config, Random(7))
        assertEquals(4_000L, gap)
    }

    @Test
    fun `间隔始终落在配置区间内`() {
        val random = Random(11)
        repeat(200) {
            val text = "字".repeat(random.nextInt(1, 30))
            val gap = ReplyPacing.gapMs(text, config, random)
            assertTrue("gap=$gap 越界", gap in 900L..4_000L)
        }
    }

    @Test
    fun `间隔为零时立即发送`() {
        val zero = config.copy(minGapMs = 0, maxGapMs = 0)
        assertEquals(0L, ReplyPacing.gapMs("随便一句话", zero, Random(1)))
    }
}

class OutputLedgerTest {

    private val windowMs = 60_000L

    @Test
    fun `容量为零表示不限制`() {
        val ledger = OutputLedger()
        assertEquals(OutputLedger.UNLIMITED, ledger.remaining(1_000L, windowMs, capacity = 0))
    }

    @Test
    fun `记录后剩余额度减少`() {
        val ledger = OutputLedger()
        ledger.record(1_000L, chars = 100, windowMs = windowMs)
        assertEquals(100, ledger.remaining(2_000L, windowMs, capacity = 200))
        ledger.record(2_000L, chars = 100, windowMs = windowMs)
        assertEquals(0, ledger.remaining(3_000L, windowMs, capacity = 200))
    }

    @Test
    fun `超出容量的请求返回零而不是负数`() {
        val ledger = OutputLedger()
        ledger.record(1_000L, chars = 500, windowMs = windowMs)
        assertEquals(0, ledger.remaining(2_000L, windowMs, capacity = 200))
    }

    @Test
    fun `窗口之外的记录会被清理`() {
        val ledger = OutputLedger()
        ledger.record(1_000L, chars = 200, windowMs = windowMs)
        // 61 秒后，那 200 字已经滑出窗口
        assertEquals(200, ledger.remaining(62_000L, windowMs, capacity = 200))
    }

    @Test
    fun `重置后额度恢复`() {
        val ledger = OutputLedger()
        ledger.record(1_000L, chars = 200, windowMs = windowMs)
        ledger.reset()
        assertEquals(200, ledger.remaining(1_500L, windowMs, capacity = 200))
    }
}
