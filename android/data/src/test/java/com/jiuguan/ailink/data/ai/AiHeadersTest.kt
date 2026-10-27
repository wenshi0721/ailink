package com.jiuguan.ailink.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHeadersTest {

    @Test
    fun `解析标准的名称与值`() {
        val parsed = AiHeaders.parse(
            """
            X-Org-Id: team-1
            X-Api-Version:2024-01

            # 注释行会被忽略
            """.trimIndent()
        )
        assertTrue(parsed.errors.toString(), parsed.valid)
        assertEquals(2, parsed.headers.size)
        assertEquals("X-Org-Id" to "team-1", parsed.headers[0])
        assertEquals("X-Api-Version" to "2024-01", parsed.headers[1])
    }

    @Test
    fun `缺少冒号时给出行号`() {
        val parsed = AiHeaders.parse("X-Token abc")
        assertTrue(!parsed.valid)
        assertTrue(parsed.errors.first().contains("第 1 行"))
        assertTrue(parsed.errors.first().contains("名称: 值"))
    }

    @Test
    fun `拒绝客户端保留头`() {
        val parsed = AiHeaders.parse("Content-Length: 10")
        assertTrue(!parsed.valid)
        assertTrue(parsed.errors.first().contains("客户端管理"))
    }

    @Test
    fun `值为空视为错误`() {
        val parsed = AiHeaders.parse("X-Empty:")
        assertTrue(!parsed.valid)
        assertTrue(parsed.errors.first().contains("值为空"))
    }

    @Test
    fun `空文本合法且没有头`() {
        val parsed = AiHeaders.parse("   ")
        assertTrue(parsed.valid)
        assertEquals(0, parsed.headers.size)
    }
}
