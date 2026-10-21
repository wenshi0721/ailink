package com.jiuguan.ailink.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    @Test
    fun `initials 取中文字符首字`() {
        assertEquals("张", TextUtils.initials("张三"))
    }

    @Test
    fun `initials 取英文首字母并大写`() {
        assertEquals("A", TextUtils.initials("alice"))
    }

    @Test
    fun `initials 空串返回问号`() {
        assertEquals("?", TextUtils.initials(""))
    }

    @Test
    fun `preview 压缩换行并限制长度`() {
        assertEquals("你好 世界", TextUtils.preview("你好\n世界"))
        assertEquals("${"ab".repeat(20)}…", TextUtils.preview("ab".repeat(30), 40))
    }

    @Test
    fun `parseKeywords 支持多种分隔符并忽略空段`() {
        assertEquals(listOf("a", "b", "c"), TextUtils.parseKeywords("a, b，c;; "))
        assertTrue(TextUtils.parseKeywords(" , ，").isEmpty())
    }

    @Test
    fun `maskToken 短令牌全遮蔽`() {
        assertEquals("••••", TextUtils.maskToken("1234"))
    }

    @Test
    fun `maskToken 长令牌保留首尾`() {
        val masked = TextUtils.maskToken("abcdefghijklmnop")
        assertEquals("abcd••••op", masked)
        assertFalse(masked.contains("efgh"))
    }

    @Test
    fun `isValidWsUrl 仅接受 ws 与 wss`() {
        assertTrue(TextUtils.isValidWsUrl("ws://192.168.1.10:3001"))
        assertTrue(TextUtils.isValidWsUrl("wss://example.com:443"))
        assertFalse(TextUtils.isValidWsUrl("http://192.168.1.10:3001"))
        assertFalse(TextUtils.isValidWsUrl("ws://"))
    }
}
