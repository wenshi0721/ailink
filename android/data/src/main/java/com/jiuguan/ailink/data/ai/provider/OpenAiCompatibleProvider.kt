package com.jiuguan.ailink.data.ai.provider

import com.jiuguan.ailink.data.ai.AiEndpoint
import com.jiuguan.ailink.data.ai.AiHeaders
import com.jiuguan.ailink.data.ai.ChatTurn
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.model.AiAuthStyle
import com.jiuguan.ailink.model.AiProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Chat Completions 兼容实现。
 *
 * 覆盖 OpenAI / DeepSeek / 通义 / Moonshot / 智谱 / 豆包 / 硅基流动 / Ollama / One-API
 * 等所有使用同一请求体的服务。协议差异（路径、认证头、自定义头）全部来自配置，
 * 实现里没有任何厂商地址或密钥。
 */
class OpenAiCompatibleProvider : AiProvider {

    override val protocol: AiProtocol = AiProtocol.OPENAI_COMPAT

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override fun endpoint(profile: AiProfileEntity): String =
        AiEndpoint.resolve(profile.baseUrl, profile.chatPath)

    override fun buildRequest(profile: AiProfileEntity, payload: JsonObject): Request {
        val builder = Request.Builder().url(endpoint(profile))

        val key = profile.apiKey.trim()
        when (profile.auth) {
            AiAuthStyle.BEARER -> if (key.isNotEmpty()) {
                builder.header("Authorization", "Bearer $key")
            }
            AiAuthStyle.X_API_KEY -> if (key.isNotEmpty()) {
                builder.header("x-api-key", key)
            }
            AiAuthStyle.NONE -> Unit
        }

        // 自定义头放在认证之后：允许覆盖 Authorization（例如自建网关用自定义鉴权）。
        AiHeaders.parse(profile.extraHeaders).headers.forEach { (name, value) ->
            builder.header(name, value)
        }

        return builder
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    override fun buildChatPayload(
        profile: AiProfileEntity,
        turns: List<ChatTurn>,
        imageDataUrls: List<String>,
        maxTokens: Int
    ): JsonObject = buildJsonObject {
        put("model", profile.model.trim())
        put("temperature", profile.temperature)
        put("max_tokens", maxTokens)
        put("stream", false)
        put("messages", messagesJson(turns, imageDataUrls))
    }

    override fun buildVisionProbePayload(profile: AiProfileEntity): JsonObject = buildJsonObject {
        put("model", profile.model.trim())
        // 给足额度：思考型模型会把 8 个 token 花在 reasoning 上，导致空内容误判。
        put("max_tokens", 64)
        put("stream", false)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", VISION_PROBE_PROMPT)
                    })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", VISION_PROBE_IMAGE) })
                    })
                })
            })
        })
    }

    /**
     * 组装 messages：某一轮带图时，该轮 `content` 用内容块数组，其余轮保持纯字符串。
     * 图片只追加到最后一条 user 消息，避免历史轮重复上传 base64。
     */
    private fun messagesJson(turns: List<ChatTurn>, extraImages: List<String>) = buildJsonArray {
        turns.forEachIndexed { index, turn ->
            val images = if (index == turns.lastIndex) turn.images + extraImages else turn.images
            val attachImages = images.isNotEmpty() && turn.role == "user"
            add(buildJsonObject {
                put("role", turn.role)
                if (attachImages) {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", turn.content)
                        })
                        images.forEach { url ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", url) })
                            })
                        }
                    })
                } else {
                    put("content", turn.content)
                }
            })
        }
    }

    override fun parseReply(body: String): AiReply {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return AiReply("")
        val choices = root["choices"]?.jsonArray ?: return AiReply("")
        val first = choices.firstOrNull()?.jsonObject ?: return AiReply("")
        val message = first["message"]?.jsonObject ?: return AiReply("")
        val text = contentOf(message["content"])
        val finishReason = runCatching {
            first["finish_reason"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
        val hasReasoning = runCatching {
            message["reasoning_content"]?.jsonPrimitive?.content?.isNotBlank() == true
        }.getOrDefault(false)
        val completionTokens = runCatching {
            root["usage"]?.jsonObject?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 0
        return AiReply(text, finishReason, completionTokens, hasReasoning)
    }

    override fun parseServerError(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return body.trim().take(200)
        val message = root["error"]?.let { err ->
            runCatching {
                val obj = err.jsonObject
                obj["message"]?.jsonPrimitive?.content ?: obj["code"]?.jsonPrimitive?.content
            }.getOrNull()
        } ?: runCatching { root["message"]?.jsonPrimitive?.content }.getOrNull()
        return message?.take(200).orEmpty()
    }

    private fun contentOf(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> runCatching { content.content }.getOrDefault("")
        is JsonArray -> content.mapNotNull { element ->
            val obj = element as? JsonObject
            val type = runCatching { obj?.get("type")?.jsonPrimitive?.content }.getOrNull()
            if (type == "text") {
                runCatching { obj?.get("text")?.jsonPrimitive?.content }.getOrNull()
            } else {
                null
            }
        }.joinToString("")
        else -> ""
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val VISION_PROBE_PROMPT = "这张图是什么颜色？只回复颜色名。"
        const val VISION_PROBE_IMAGE =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKklEQVR42mO4o6FBU8QwasGoBaMWjFowasGoBaMWjFowasGoBaMWDBULAIahsD0l4r5QAAAAAElFTkSuQmCC"
    }
}
