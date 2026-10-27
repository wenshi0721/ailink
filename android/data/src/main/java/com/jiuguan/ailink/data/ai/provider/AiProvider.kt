package com.jiuguan.ailink.data.ai.provider

import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.model.AiProtocol
import kotlinx.serialization.json.JsonObject
import okhttp3.Request

/** 解析后的模型回复。 */
data class AiReply(
    val text: String,
    val finishReason: String = "",
    val completionTokens: Int = 0,
    val hasReasoning: Boolean = false
)

/**
 * AI 协议适配接口。
 *
 * 业务层只认 [AiProvider]，不认具体厂商或地址：
 * - 新增协议（如 Anthropic Messages、Gemini）时实现本接口并注册到 AiProviderRegistry；
 * - 同一协议下的不同厂商（DeepSeek / 通义 / 自建网关）复用实现，仅配置不同。
 *
 * 实现约定：不缓存、不发请求，只负责「拼请求 / 解析响应」，
 * 网络、超时、重试统一由 AiClient 处理，保证行为一致。
 */
interface AiProvider {

    val protocol: AiProtocol

    /** 完整的请求地址，由配置中的 Base URL + 接口路径推导。 */
    fun endpoint(profile: AiProfileEntity): String

    /** 组装一次聊天补全请求（含 URL、认证头、自定义头、请求体）。 */
    fun buildRequest(profile: AiProfileEntity, payload: JsonObject): Request

    /** 聊天补全的请求体。 */
    fun buildChatPayload(
        profile: AiProfileEntity,
        turns: List<com.jiuguan.ailink.data.ai.ChatTurn>,
        imageDataUrls: List<String>,
        maxTokens: Int
    ): JsonObject

    /** 识图能力探测用的请求体。 */
    fun buildVisionProbePayload(profile: AiProfileEntity): JsonObject

    fun parseReply(body: String): AiReply

    /** 从错误响应里提取服务端 message，供界面展示。 */
    fun parseServerError(body: String): String
}
