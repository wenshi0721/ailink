package com.jiuguan.ailink.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 一条消息附带的图片引用，持久化在 `messages.imagesJson`。
 *
 * 为什么必须落库：图片原先只活在 `IncomingMessage` 里，落库后只剩 `[图片]` 四个字，
 * 于是下一轮追问、别人再问、以及引用回复都拿不到图——模型只能回"我看不到"。
 */
@Serializable
data class MessageImage(
    /** 桥接端下发的原始地址；可能带 rkey/鉴权参数，会过期。 */
    val url: String = "",
    /** 桥接端内部文件名，用于 `get_image` 换取一个新地址。 */
    val file: String = "",
    /** 本地缓存文件名（相对 filesDir/imgcache）；空表示没缓存到本地。 */
    val cache: String = ""
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val listSerializer = ListSerializer(MessageImage.serializer())

        fun encodeList(list: List<MessageImage>): String =
            if (list.isEmpty()) "" else json.encodeToString(listSerializer, list)

        fun decodeList(raw: String): List<MessageImage> {
            if (raw.isBlank()) return emptyList()
            return runCatching { json.decodeFromString(listSerializer, raw) }
                .getOrDefault(emptyList())
        }
    }
}
