package com.jiuguan.ailink.data.gateway.onebot

import com.jiuguan.ailink.data.local.MessageImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用消息（reply 段）与图片引用持久化的回归保护。
 *
 * 这两块过去都是空白：`parseMessage` 的 `when(type)` 里根本没有 `reply` 分支，
 * 图片引用也从不落库（历史里只剩 `[图片]` 四个字）。改这两处之前先看这里的断言。
 */
class QuoteAndImageRefTest {

    private val sid = 10086L

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    /** parseMessage 接收的是 message 字段本身，不是整个事件对象。 */
    private fun message(text: String): kotlinx.serialization.json.JsonElement =
        json(text)["message"] as kotlinx.serialization.json.JsonElement

    // ---------- reply 段（消息段数组） ----------

    @Test
    fun `数组格式解析引用段`() {
        val parsed = parseMessage(
            message(
                """
                {"message":[
                  {"type":"reply","data":{"id":"-1234567890"}},
                  {"type":"text","data":{"text":"这图里是谁"}}
                ]}
                """
            ),
            sid,
            raw = ""
        )

        assertEquals("-1234567890", parsed.replyToId)
        assertEquals("这图里是谁", parsed.text)
    }

    @Test
    fun `引用段不影响后续文本与图片解析`() {
        val parsed = parseMessage(
            message(
                """
                {"message":[
                  {"type":"reply","data":{"id":"-42"}},
                  {"type":"image","data":{"url":"https://x/y.jpg","file":"y.jpg"}}
                ]}
                """
            ),
            sid,
            raw = ""
        )

        assertEquals("-42", parsed.replyToId)
        assertEquals("[图片]", parsed.text)
        assertEquals(1, parsed.images.size)
    }

    @Test
    fun `没有引用段时 replyToId 为空`() {
        val parsed = parseMessage(
            message("""{"message":[{"type":"text","data":{"text":"普通消息"}}]}"""),
            sid,
            raw = ""
        )

        assertEquals("", parsed.replyToId)
    }

    // ---------- reply 段（CQ 码字符串） ----------

    @Test
    fun `CQ 字符串解析引用段`() {
        val parsed = parseMessage(
            message("""{"message":"[CQ:reply,id=-987654]这图里是谁"}"""),
            sid,
            raw = ""
        )

        assertEquals("-987654", parsed.replyToId)
        assertEquals("这图里是谁", parsed.text)
    }

    @Test
    fun `CQ 字符串里引用与图片共存`() {
        val parsed = parseMessage(
            message("""{"message":"[CQ:reply,id=-1][CQ:image,file=a.jpg,url=https://x/a.jpg]"}"""),
            sid,
            raw = ""
        )

        assertEquals("-1", parsed.replyToId)
        assertEquals(1, parsed.images.size)
    }

    // ---------- 图片引用落库 ----------

    @Test
    fun `图片引用编解码往返`() {
        val list = listOf(
            MessageImage(url = "https://gchat.qpic.cn/a?rkey=1", file = "a.jpg", cache = "abc.jpg"),
            MessageImage(url = "https://gchat.qpic.cn/b", file = "b.jpg")
        )

        val raw = MessageImage.encodeList(list)

        assertTrue(raw.startsWith("["))
        assertEquals(list, MessageImage.decodeList(raw))
    }

    @Test
    fun `空列表编码为空串`() {
        assertEquals("", MessageImage.encodeList(emptyList()))
        assertTrue(MessageImage.decodeList("").isEmpty())
    }

    @Test
    fun `脏数据解码不抛异常`() {
        assertTrue(MessageImage.decodeList("{not json").isEmpty())
        assertTrue(MessageImage.decodeList("""[{"unknown":1}]""").isNotEmpty())
    }
}
