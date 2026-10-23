package com.jiuguan.ailink.data.ai

import com.jiuguan.ailink.model.VisionSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模型名快判的回归保护。
 *
 * 这里只断言「高置信度」的判定：名单返回 null 表示交给运行时探测，
 * 加新模型时优先加进 VISION_PATTERNS，而不是放宽判断条件。
 */
class VisionCatalogTest {

    @Test
    fun `多模态模型直接判定支持`() {
        listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "qwen-vl-max",
            "qwen2.5-vl-7b-instruct",
            "glm-4v-plus",
            "claude-sonnet-4",
            "gemini-1.5-pro",
            "kimi-vl-a3b-instruct"
        ).forEach { model ->
            assertEquals(model, VisionSupport.SUPPORTED, VisionCatalog.quickJudge(model))
        }
    }

    @Test
    fun `纯文本模型直接判定不支持`() {
        listOf(
            "deepseek-chat",
            "deepseek-reasoner",
            "moonshot-v1-8k",
            "hunyuan-lite"
        ).forEach { model ->
            assertEquals(model, VisionSupport.UNSUPPORTED, VisionCatalog.quickJudge(model))
        }
    }

    @Test
    fun `未知模型交给探测`() {
        listOf("qwen-plus", "gpt-3.5-turbo", "hunyuan-turbos-latest", "my-gateway-model")
            .forEach { model -> assertNull(model, VisionCatalog.quickJudge(model)) }
        assertNull(VisionCatalog.quickJudge(""))
        assertNull(VisionCatalog.quickJudge("   "))
    }

    @Test
    fun `o 系列按前缀判定且大小写不敏感`() {
        assertEquals(VisionSupport.SUPPORTED, VisionCatalog.quickJudge("o3-mini"))
        assertEquals(VisionSupport.SUPPORTED, VisionCatalog.quickJudge("O4-MINI"))
        // 不能因为含 "o3" 两个字就误判
        assertNull(VisionCatalog.quickJudge("co3-custom"))
    }

    @Test
    fun `同一厂商的纯文本与多模态模型互不干扰`() {
        assertEquals(VisionSupport.UNSUPPORTED, VisionCatalog.quickJudge("deepseek-chat"))
        assertEquals(VisionSupport.SUPPORTED, VisionCatalog.quickJudge("deepseek-vl2"))
        assertEquals(VisionSupport.UNSUPPORTED, VisionCatalog.quickJudge("moonshot-v1-32k"))
        assertEquals(VisionSupport.SUPPORTED, VisionCatalog.quickJudge("moonshot-v1-vision-preview"))
    }
}
