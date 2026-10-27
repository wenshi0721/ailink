package com.jiuguan.ailink.core.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段器的回归保护。
 *
 * 这些断言描述的是「什么样的输出会被切成几条」——改动 [SegmentSplitter] 之前先看这里，
 * 尤其是「关闭分段时必须原样返回一条」这条底线。
 */
class SegmentSplitterTest {

    private fun config(
        enabled: Boolean = true,
        maxSegments: Int = 3,
        segmentMaxChars: Int = 0,
        totalMaxChars: Int = 600
    ) = MultiReplyConfig(
        enabled = enabled,
        maxSegments = maxSegments,
        segmentMaxChars = segmentMaxChars,
        totalMaxChars = totalMaxChars
    )

    @Test
    fun `模型用分隔符分段时按段返回`() {
        val raw = "今天有点累\n###\n不过还行，下午摸鱼了一会儿"
        val segments = SegmentSplitter.split(raw, config())
        assertEquals(listOf("今天有点累", "不过还行，下午摸鱼了一会儿"), segments)
    }

    @Test
    fun `分隔符兼容三个减号`() {
        val raw = "在的\n---\n刚看到消息"
        assertEquals(listOf("在的", "刚看到消息"), SegmentSplitter.split(raw, config()))
    }

    @Test
    fun `模型没分段时按句末兜底切分`() {
        val raw = "我刚下班。今天开会开到六点半，人都木了。晚上想吃点好的。要不要一起？"
        val segments = SegmentSplitter.split(raw, config(maxSegments = 2))
        assertEquals(2, segments.size)
        assertEquals("我刚下班。今天开会开到六点半，人都木了。", segments[0])
        assertEquals("晚上想吃点好的。要不要一起？", segments[1])
    }

    @Test
    fun `关闭分段时原样返回一条且不做清洗`() {
        val raw = "**你好**，在吗？"
        val segments = SegmentSplitter.split(raw, config(enabled = false))
        assertEquals(listOf("**你好**，在吗？"), segments)
    }

    @Test
    fun `条数上限为 1 时不拆分`() {
        val raw = "第一句。第二句。第三句。"
        val segments = SegmentSplitter.split(raw, config(maxSegments = 1))
        assertEquals(1, segments.size)
        assertEquals(raw, segments[0])
    }

    @Test
    fun `单条字数不限时不截断长内容`() {
        val raw = "这是一段没有任何标点的很长很长的内容".repeat(5)
        val segments = SegmentSplitter.split(raw, config(segmentMaxChars = 0))
        assertEquals(raw.length, segments.joinToString("").length)
    }

    @Test
    fun `单条字数受限时每段不超过上限`() {
        val raw = "前半句比较长。后半句也很长很长很长很长。"
        val segments = SegmentSplitter.split(raw, config(maxSegments = 2, segmentMaxChars = 10))
        assertTrue("每段都不超过上限：$segments", segments.all { it.length <= 10 })
        assertTrue("应尽量在句号处收尾：$segments", segments.first().endsWith("。"))
    }

    @Test
    fun `整轮字数上限会裁掉后续内容`() {
        val raw = "第一段内容大概十五个字左右。\n###\n第二段内容也差不多长度。"
        val segments = SegmentSplitter.split(raw, config(totalMaxChars = 30))
        assertEquals(2, segments.size)
        assertEquals("第一段内容大概十五个字左右。", segments[0])
        assertTrue(segments.sumOf { it.length } <= 30)
    }

    @Test
    fun `清洗 Markdown 且保留正文`() {
        val raw = "**重点**\n- 第一条\n- 第二条"
        val segments = SegmentSplitter.split(raw, config())
        val text = segments.joinToString("\n")
        assertTrue(text.contains("重点"))
        assertTrue(text.contains("第一条"))
        assertTrue(!text.contains("**"))
    }

    @Test
    fun `兜底切分时过短碎片并入相邻段`() {
        val raw = "嗯。今天下午开会开到很晚，回来只想躺着。"
        val segments = SegmentSplitter.split(raw, config(maxSegments = 2))
        assertEquals(1, segments.size)
        assertTrue(segments[0].startsWith("嗯。"))
    }

    @Test
    fun `模型显式分段时不合并语气词碎片`() {
        val raw = "嗯\n###\n今天下午开会开到很晚，回来只想躺着"
        val segments = SegmentSplitter.split(raw, config(maxSegments = 3))
        assertEquals(listOf("嗯", "今天下午开会开到很晚，回来只想躺着"), segments)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(SegmentSplitter.split("   \n  ", config()).isEmpty())
    }

    /**
     * 底线承诺：默认配置（分段关闭）下，输出必须与改造前的 `trim().take(600)` 逐字一致。
     * 这条断言挂了，说明「关掉开关就能回到旧行为」不再成立。
     */
    @Test
    fun `关闭分段等价于旧的 trim 加 take`() {
        val raw = "  " + "字".repeat(1_000) + "  "
        val segments = SegmentSplitter.split(raw, MultiReplyConfig())
        assertEquals(listOf(raw.trim().take(600)), segments)
    }

    @Test
    fun `连续省略号只算一个断点`() {
        val segments = SegmentSplitter.split("我……其实就是有点累。", config(maxSegments = 2))
        assertTrue(segments.none { it == "……" || it == "…" })
    }

    /**
     * 来源标记是日志排障的依据：「模型」说明分隔符指令被遵守，「句末兜底」说明模型没听话，
     * 此时分段可能切断语义，需要调提示词而不是调分段器。
     */
    @Test
    fun `分段来源可区分模型与本地兜底`() {
        val byModel = SegmentSplitter.splitWith("第一句\n###\n第二句", config())
        assertEquals(SegmentSplitter.Source.MODEL, byModel.source)

        // 句末兜底：两句都够长，不会被碎片合并吃掉
        val bySentence = SegmentSplitter.splitWith("第一句话说得比较长一些。第二句话也说得比较长一些。", config())
        assertEquals(SegmentSplitter.Source.SENTENCE, bySentence.source)
        assertEquals(2, bySentence.segments.size)

        // 一句话到底：既没分隔符也切不开，只能整段发
        val verbatim = SegmentSplitter.splitWith("就这一句话没有标点", config())
        assertEquals(SegmentSplitter.Source.VERBATIM, verbatim.source)
        assertEquals(1, verbatim.segments.size)

        // 两句都太短，被碎片合并吃成一条 —— 结果就是「整段」，来源必须如实反映
        val merged = SegmentSplitter.splitWith("嗯。好的。", config())
        assertEquals(SegmentSplitter.Source.VERBATIM, merged.source)
        assertEquals(1, merged.segments.size)

        // 关闭分段时来源恒为单条
        val single = SegmentSplitter.splitWith("第一句。第二句。", MultiReplyConfig())
        assertEquals(SegmentSplitter.Source.SINGLE, single.source)
    }

    @Test
    fun `来源标记不影响切分结果`() {
        val raw = "第一句说完了。第二句也说完了。第三句。"
        val viaSplit = SegmentSplitter.split(raw, config())
        val viaSplitWith = SegmentSplitter.splitWith(raw, config()).segments
        assertEquals(viaSplit, viaSplitWith)
    }
}
