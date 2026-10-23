package com.jiuguan.ailink.data.gateway.onebot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断线补拉的转换逻辑回归保护。
 *
 * 这里只测纯函数 [OneBotGateway.toIncoming]，不碰网络：补拉最容易出错的地方不是
 * HTTP/WS 调用，而是「私聊历史里 user_id 是发送者」这件事——机器人自己发的那条
 * user_id 就是自己，若照实时推送的写法从消息里推 peerKey，会把会话错认成"自己"。
 */
class CatchUpTest {

    private val gateway = OneBotGateway(
        url = "ws://127.0.0.1:3001",
        token = "test",
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `私聊历史 对方的消息 peer 用请求参数而不是发送者`() {
        // 请求参数 user_id = 20000（会话对象），消息发送者也是 20000
        val msg = json(
            """
            {"self_id":10086,"time":1730000000,"message_type":"private",
             "user_id":20000,"message_id":"m1",
             "sender":{"nickname":"对方"},
             "message":[{"type":"text","data":{"text":"在吗"}}]}
            """
        )

        val incoming = gateway.toIncoming(msg, isGroup = false, peerUin = "20000")

        assertNotNull(incoming)
        assertEquals("p:20000", incoming!!.peerKey)
        assertEquals("在吗", incoming.text)
        assertEquals("m1", incoming.platformMsgId)
        assertEquals(1_730_000_000_000L, incoming.timestamp)
    }

    @Test
    fun `私聊历史 自己发的消息被丢弃`() {
        // 机器人自己发的消息：user_id == self_id，不能当成对方发来的
        val msg = json(
            """
            {"self_id":10086,"time":1730000000,"user_id":10086,"message_id":"m2",
             "sender":{"nickname":"我"},
             "message":[{"type":"text","data":{"text":"好的"}}]}
            """
        )

        assertNull(gateway.toIncoming(msg, isGroup = false, peerUin = "20000"))
    }

    @Test
    fun `私聊历史 自己发消息时 peer 仍然指向会话对象`() {
        // 这条覆盖的是"请求参数决定 peer"：即使消息来自自己，也不该算出 p:10086
        val msg = json(
            """
            {"self_id":10086,"time":1730000000,"user_id":20000,"message_id":"m3",
             "sender":{"nickname":"对方"},
             "message":[{"type":"text","data":{"text":"嗯"}}]}
            """
        )

        val incoming = gateway.toIncoming(msg, isGroup = false, peerUin = "20000")
        assertEquals("p:20000", incoming?.peerKey)
    }

    @Test
    fun `群历史 peer 用群号`() {
        val msg = json(
            """
            {"self_id":10086,"time":1730000000,"group_id":55555,"user_id":20000,
             "message_id":"g1","sender":{"card":"群名片","nickname":"昵称"},
             "message":[{"type":"at","data":{"qq":"10086"}},
                        {"type":"text","data":{"text":" 看图"}},
                        {"type":"image","data":{"url":"https://x/y.jpg","file":"y.jpg"}}]}
            """
        )

        val incoming = gateway.toIncoming(msg, isGroup = true, peerUin = "55555")

        assertNotNull(incoming)
        assertEquals("g:55555", incoming!!.peerKey)
        assertEquals(1, incoming.images.size)
        assertEquals("https://x/y.jpg", incoming.images.first().url)
        assertTrue("群里 @ 自己应被识别", incoming.atMe)
    }

    @Test
    fun `纯图片历史消息保留图片段`() {
        val msg = json(
            """
            {"self_id":10086,"time":1730000000,"user_id":20000,"message_id":"m4",
             "sender":{"nickname":"对方"},
             "message":[{"type":"image","data":{"url":"https://x/z.jpg","file":"z.jpg"}}]}
            """
        )

        val incoming = gateway.toIncoming(msg, isGroup = false, peerUin = "20000")
        assertEquals("[图片]", incoming?.text)
        assertEquals(1, incoming?.images?.size)
    }
}
