package com.jiuguan.ailink.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址拼接是历史遗留问题的高发区：旧实现把 /v1 补在所有地址后面，
 * 导致智谱、方舟这类带版本段的服务拼出 `/api/paas/v4/v1/chat/completions`。
 */
class AiEndpointTest {

    @Test
    fun `标准 v1 地址补全 chat completions`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiEndpoint.resolve("https://api.deepseek.com/v1")
        )
    }

    @Test
    fun `末尾斜杠不影响拼接`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiEndpoint.resolve(" https://api.deepseek.com/v1/ ")
        )
    }

    @Test
    fun `已带完整路径的地址原样使用`() {
        val url = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        assertEquals(url, AiEndpoint.resolve(url))
    }

    @Test
    fun `带版本段但未带路径时只补 chat completions`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            AiEndpoint.resolve("https://open.bigmodel.cn/api/paas/v4")
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            AiEndpoint.resolve("https://ark.cn-beijing.volces.com/api/v3")
        )
    }

    @Test
    fun `没有版本段的地址按 OpenAI 习惯补 v1`() {
        assertEquals(
            "https://gateway.example.com/v1/chat/completions",
            AiEndpoint.resolve("https://gateway.example.com")
        )
    }

    @Test
    fun `自定义路径完全接管拼接`() {
        assertEquals(
            "https://gateway.example.com/custom/chat",
            AiEndpoint.resolve("https://gateway.example.com", "/custom/chat")
        )
        assertEquals(
            "https://gateway.example.com/prefix/relay",
            AiEndpoint.resolve("https://gateway.example.com/prefix", "relay")
        )
    }

    @Test
    fun `自定义路径可以是完整 URL`() {
        assertEquals(
            "https://other.example.com/v1/chat/completions",
            AiEndpoint.resolve("https://gateway.example.com", "https://other.example.com/v1/chat/completions")
        )
    }

    @Test
    fun `空地址返回空串`() {
        assertEquals("", AiEndpoint.resolve("  "))
    }

    @Test
    fun `Base URL 校验给出可操作原因`() {
        assertNull(AiEndpoint.validateBaseUrl("https://api.deepseek.com/v1"))
        assertNull(AiEndpoint.validateBaseUrl("http://192.168.1.10:11434/v1"))
        assertTrue(AiEndpoint.validateBaseUrl("")!!.contains("请填写"))
        assertTrue(AiEndpoint.validateBaseUrl("api.deepseek.com")!!.contains("http://"))
        assertTrue(AiEndpoint.validateBaseUrl("https://")!!.contains("域名"))
        assertTrue(AiEndpoint.validateBaseUrl("https://a b.com")!!.contains("空格"))
    }

    @Test
    fun `接口路径校验`() {
        assertNull(AiEndpoint.validateChatPath(""))
        assertNull(AiEndpoint.validateChatPath("/chat/completions"))
        assertNull(AiEndpoint.validateChatPath("https://x.y/v1/chat/completions"))
        assertTrue(AiEndpoint.validateChatPath("chat/completions")!!.contains("/"))
    }
}
