package com.jiuguan.ailink.data.ai

import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.model.VisionSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 一轮对话。[images] 是该轮附带的图片 data URL。
 *
 * 为什么要按轮存：图片原先只能挂在"最后一条 user"上，追问就再也看不到图。
 * 现在每一轮各自带图，`messagesJson` 按轮组装内容块数组。
 */
data class ChatTurn(
    val role: String,
    val content: String,
    val images: List<String> = emptyList()
)

/**
 * OpenAI Chat Completions 兼容客户端。
 * 兼容 OpenAI / DeepSeek / 通义 / Moonshot / Ollama / LM Studio / One-API 等服务。
 */
class AiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 探测专用：必须在回复链路的可接受延迟内结束，不能沿用 75 秒的生成超时。
     *
     * 注意这里的总时长必须小于调用方 `AccountRuntime.VISION_DETECT_TIMEOUT_MS`（15 秒），
     * 否则外层 withTimeoutOrNull 会把探测直接取消：结论既不落缓存、也不打日志，
     * 表现为「每条图片消息都拖满 15 秒，然后永远按纯文本回复」。
     * 探测只关心 HTTP 状态码，不需要等模型把话说完，因此读超时给 6 秒足够。
     */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun complete(
        profile: AiProfileEntity,
        turns: List<ChatTurn>,
        maxTokens: Int = profile.maxTokens,
        /**
         * 附图（data URL）。只会追加到最后一条 user 消息上：
         * 历史轮次保持纯文本，避免每轮重复上传 base64 导致 token 与耗时膨胀。
         */
        imageDataUrls: List<String> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(profile.baseUrl.isNotBlank()) { "未配置 AI 接口地址" }
            require(turns.isNotEmpty()) { "对话内容为空" }

            var budget = maxTokens.coerceIn(32, MAX_TOKENS_CAP)
            var retried = false
            var text = ""
            while (text.isEmpty()) {
                val reply = request(profile, turns, imageDataUrls, budget)
                if (reply.text.isNotBlank()) {
                    text = reply.text.trim()
                    break
                }

                // 空内容最常见的原因不是模型坏了，而是「额度被推理过程吃光」：
                // 思考型模型（DeepSeek 的默认思考模式等）先写 reasoning_content，
                // 余额才给 content。带图请求的推理更长，于是同一份配置下纯文本能回、
                // 发图就空——这正是 20:42 那次 `[ai] AI 调用失败：模型返回内容为空`。
                com.jiuguan.ailink.core.SessionLog.add(
                    "ai",
                    "回复为空：finish_reason=${reply.finishReason.ifBlank { "无" }} " +
                        "completion_tokens=${reply.completionTokens} " +
                        "含推理内容=${reply.hasReasoning} max_tokens=$budget"
                )
                val truncated = reply.finishReason == "length"
                if (!truncated || retried || budget >= MAX_TOKENS_CAP) {
                    error(
                        "模型返回内容为空（finish_reason=${reply.finishReason.ifBlank { "无" }}，max_tokens=$budget）" +
                            if (reply.hasReasoning || truncated) {
                                "；额度被推理内容占满，请调大配置页的「单次最大 Token」"
                            } else {
                                ""
                            }
                    )
                }
                retried = true
                budget = (budget * 4).coerceAtMost(MAX_TOKENS_CAP)
                com.jiuguan.ailink.core.SessionLog.add("ai", "按 max_tokens=$budget 重试一次")
            }
            text
        }
    }

    /** 一次请求 + 解析；空内容的判断与重试交给 [complete]。 */
    private fun request(
        profile: AiProfileEntity,
        turns: List<ChatTurn>,
        imageDataUrls: List<String>,
        maxTokens: Int
    ): Reply {
        val payload = buildJsonObject {
            put("model", profile.model.ifBlank { "gpt-3.5-turbo" })
            put("temperature", profile.temperature)
            put("max_tokens", maxTokens)
            put("stream", false)
            put("messages", messagesJson(turns, imageDataUrls))
        }

        val request = Request.Builder()
            .url(endpoint(profile.baseUrl))
            .apply {
                if (profile.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${profile.apiKey.trim()}")
                }
            }
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("接口返回 HTTP ${response.code}${extractError(body)}")
            }
            return parseReply(body)
        }
    }

    /** 一次回复的解析结果：正文 + 结束原因（用于区分"被截断"与"真的空"）。 */
    private data class Reply(
        val text: String,
        val finishReason: String = "",
        val completionTokens: Int = 0,
        val hasReasoning: Boolean = false
    )

    /** 连通性测试：发送一条极短的 ping。 */
    suspend fun test(profile: AiProfileEntity): Result<String> =
        complete(
            profile = profile,
            turns = listOf(ChatTurn("user", "ping，请只回复 ok")),
            maxTokens = 16
        )

    /**
     * 用一张内置的 32×32 纯色小图探测接口是否接受多模态内容块。
     *
     * 判定策略偏保守：
     * - 2xx 且能解析出回复 → SUPPORTED；
     * - 400/415/422 且响应明确指向图片/内容格式 → UNSUPPORTED；
     * - 其余（鉴权、限流、模型不存在、网络错误）→ UNKNOWN，交由调用方按「不支持」降级，
     *   且不写缓存，下次再探。
     */
    suspend fun probeVision(profile: AiProfileEntity): VisionSupport = withContext(Dispatchers.IO) {
        if (profile.baseUrl.isBlank()) return@withContext VisionSupport.UNKNOWN
        val payload = buildJsonObject {
            put("model", profile.model.ifBlank { "gpt-3.5-turbo" })
            // 给足额度：V4.1 Flash 这类默认开思考模式的模型，8 个 token 会被思考过程吃光，
            // 返回空内容会让探测误判。探测只看 HTTP 状态，不看内容长度。
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

        val request = Request.Builder()
            .url(endpoint(profile.baseUrl))
            .apply {
                if (profile.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${profile.apiKey.trim()}")
                }
            }
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            probeClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    // 2xx 已经说明接口接受了图片内容块。不再要求回复非空：
                    // 思考型模型可能把额度花在 reasoning 上，内容为空却不代表不支持识图。
                    response.isSuccessful -> VisionSupport.SUPPORTED

                    response.code in VISION_REJECT_CODES && looksLikeImageRejection(body) ->
                        VisionSupport.UNSUPPORTED

                    else -> VisionSupport.UNKNOWN
                }
            }
        } catch (e: Exception) {
            com.jiuguan.ailink.core.SessionLog.add(
                "vision",
                "识图探测失败：${e.message ?: e.javaClass.simpleName}"
            )
            VisionSupport.UNKNOWN
        }
    }

    /**
     * 组装 messages：某一轮带图时，该轮 `content` 用内容块数组，其余轮保持纯字符串。
     *
     * [extraImages] 是"当前这一轮"的附图（本条消息 + 引用消息的图），
     * 会合并到最后一条 user 轮上，与 [ChatTurn.images] 共存。
     */
    private fun messagesJson(turns: List<ChatTurn>, extraImages: List<String>) = buildJsonArray {
        turns.forEachIndexed { index, turn ->
            val images = if (index == turns.lastIndex) {
                turn.images + extraImages
            } else {
                turn.images
            }
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

    /** 错误体里出现这些词，说明是「不认识图片内容块」而不是额度/鉴权问题。 */
    private fun looksLikeImageRejection(body: String): Boolean {
        val lower = body.lowercase()
        return IMAGE_REJECTION_HINTS.any { lower.contains(it) }
    }

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") || base.endsWith("/openai") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun parseReply(body: String): Reply {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Reply("")
        val choices = root["choices"]?.jsonArray ?: return Reply("")
        val first = choices.firstOrNull()?.jsonObject ?: return Reply("")
        val message = first["message"]?.jsonObject ?: return Reply("")
        val text = contentOf(message["content"])
        val finishReason = runCatching {
            first["finish_reason"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
        // 思考型模型的思考过程在独立字段里，出现即说明额度被它先花掉了一部分
        val hasReasoning = runCatching {
            message["reasoning_content"]?.jsonPrimitive?.content?.isNotBlank() == true
        }.getOrDefault(false)
        val completionTokens = runCatching {
            root["usage"]?.jsonObject?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 0
        return Reply(text, finishReason, completionTokens, hasReasoning)
    }

    private fun contentOf(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> runCatching { content.content }.getOrDefault("")
        is JsonArray -> content.mapNotNull { element ->
            val obj = element as? JsonObject
            val type = runCatching {
                obj?.get("type")?.jsonPrimitive?.content
            }.getOrNull()
            if (type == "text") {
                runCatching { obj?.get("text")?.jsonPrimitive?.content }.getOrNull()
            } else {
                null
            }
        }.joinToString("")
        else -> ""
    }

    private fun extractError(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ""
        val message = root["error"]?.let { err ->
            runCatching {
                val obj = err.jsonObject
                obj["message"]?.jsonPrimitive?.content
                    ?: obj["code"]?.jsonPrimitive?.content
            }.getOrNull()
        }
        return if (message.isNullOrBlank()) "" else "：$message"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** max_tokens 上限：重试时最多放大到这个值。 */
        const val MAX_TOKENS_CAP = 4096

        /** 明确表示「请求体格式不被接受」的状态码；鉴权/限流/5xx 不在其中。 */
        val VISION_REJECT_CODES = setOf(400, 415, 422)

        /** 错误体关键词：出现即认定是图片内容块不被支持，而非额度或密钥问题。 */
        val IMAGE_REJECTION_HINTS = listOf(
            "image_url",
            "image",
            "vision",
            "multimodal",
            "must be a string",
            "不支持图片"
        )

        const val VISION_PROBE_PROMPT = "这张图是什么颜色？只回复颜色名。"

        /** 32×32 纯红 PNG，99 字节；用于能力探测，避免引入任何图片依赖。 */
        const val VISION_PROBE_IMAGE =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKklEQVR42mO4o6FBU8QwasGoBaMWjFowasGoBaMWjFowasGoBaMWDBULAIahsD0l4r5QAAAAAElFTkSuQmCC"
    }
}
