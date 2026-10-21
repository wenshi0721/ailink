package com.jiuguan.ailink.data.gateway.onebot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OneBot v11 消息解析的回归保护。
 *
 * 覆盖两种真实桥接端可能给出的 `message` 形态（消息段数组 / CQ 码字符串），
 * 以及 `raw_message` 兜底路径。改 [flattenMessage] 之前先看这里的断言。
 */
class OneBotParsingTest {

    private val sid = 10086L

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    // ---------- 消息段数组 ----------

    @Test
    fun `数组格式 拼接文本段`() {
        val root = json(
            """
            {"message":[
              {"type":"text","data":{"text":"你好，"}},
              {"type":"text","data":{"text":"在吗"}}
            ]}
            """.trimIndent()
        )
        val (text, atMe) = flattenMessage(root["message"], sid, "")
        assertEquals("你好，在吗", text)
        assertFalse(atMe)
    }

    @Test
    fun `数组格式 at 自己时 atMe 为真`() {
        val root = json(
            """
            {"message":[
              {"type":"at","data":{"qq":"10086"}},
              {"type":"text","data":{"text":" 帮我看下"}}
            ]}
            """.trimIndent()
        )
        val (text, atMe) = flattenMessage(root["message"], sid, "")
        assertEquals("帮我看下", text)
        assertTrue(atMe)
    }

    @Test
    fun `数组格式 at 别人时 atMe 为假`() {
        val root = json(
            """
            {"message":[
              {"type":"at","data":{"qq":"20000"}},
              {"type":"text","data":{"text":" 你说"}}
            ]}
            """.trimIndent()
        )
        val (text, atMe) = flattenMessage(root["message"], sid, "")
        assertEquals("你说", text)
        assertFalse(atMe)
    }

    @Test
    fun `数组格式 富媒体统一降级为占位符`() {
        val root = json(
            """
            {"message":[
              {"type":"text","data":{"text":"看"}},
              {"type":"image","data":{"file":"a.png"}},
              {"type":"record","data":{"file":"b.amr"}},
              {"type":"video","data":{"file":"c.mp4"}},
              {"type":"file","data":{"file":"d.pdf"}},
              {"type":"face","data":{"id":"1"}}
            ]}
            """.trimIndent()
        )
        val (text, _) = flattenMessage(root["message"], sid, "")
        assertEquals("看[图片][语音][视频][文件][表情]", text)
    }

    // ---------- CQ 码字符串 ----------

    @Test
    fun `CQ码 剥掉标记并识别 at 自己`() {
        val root = json("""{"message":"[CQ:at,qq=10086] 今天天气如何"}""")
        val (text, atMe) = flattenMessage(root["message"], sid, "")
        assertEquals("今天天气如何", text)
        assertTrue(atMe)
    }

    @Test
    fun `CQ码 图片标记被移除且不产生 atMe`() {
        val root = json("""{"message":"[CQ:image,file=x.png]这是截图"}""")
        val (text, atMe) = flattenMessage(root["message"], sid, "")
        assertEquals("这是截图", text)
        assertFalse(atMe)
    }

    // ---------- 兜底与边界 ----------

    @Test
    fun `解析不出内容时回退到 raw_message`() {
        // message 为空数组 → 走 raw 兜底
        val root = json("""{"message":[],"raw_message":"[CQ:at,qq=10086] 收到"}""")
        val (text, atMe) = flattenMessage(root["message"], sid, root.jStr("raw_message"))
        assertEquals("收到", text)
        assertTrue(atMe)
    }

    @Test
    fun `message 缺失且 raw 为空时返回空文本`() {
        val root = json("""{"post_type":"message"}""")
        val (text, atMe) = flattenMessage(root["message"], sid, "")
        assertEquals("", text)
        assertFalse(atMe)
    }

    @Test
    fun `sid 为 0 时不会把任何 at 误判为自己`() {
        val root = json("""{"message":[{"type":"at","data":{"qq":"10086"}}]}""")
        val (_, atMe) = flattenMessage(root["message"], 0L, "")
        assertFalse(atMe)
    }

    @Test
    fun `jStr 与 jLong 对缺失与异形字段保持健壮`() {
        val root = json("""{"message_id":"9876543210","group_id":123,"sender":{}}""")
        assertEquals("9876543210", root.jStr("message_id"))
        assertEquals(9876543210L, root.jLong("message_id")) // OneBot 大整数常以字符串下发
        assertEquals(123L, root.jLong("group_id"))
        assertEquals("", root.jStr("not_exist"))
        assertEquals(0L, root.jLong("not_exist"))
    }
}
