package com.jiuguan.ailink.data.ai

import com.jiuguan.ailink.data.local.AiProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

data class ChatTurn(val role: String, val content: String)

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

    suspend fun complete(
        profile: AiProfileEntity,
        turns: List<ChatTurn>,
        maxTokens: Int = profile.maxTokens
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(profile.baseUrl.isNotBlank()) { "未配置 AI 接口地址" }
            require(turns.isNotEmpty()) { "对话内容为空" }

            val payload = buildJsonObject {
                put("model", profile.model.ifBlank { "gpt-3.5-turbo" })
                put("temperature", profile.temperature)
                put("max_tokens", maxTokens.coerceIn(32, 4096))
                put("stream", false)
                put("messages", buildJsonArray {
                    turns.forEach { turn ->
                        add(buildJsonObject {
                            put("role", turn.role)
                            put("content", turn.content)
                        })
                    }
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

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("接口返回 HTTP ${response.code}${extractError(body)}")
                }
                val text = parseContent(body)
                if (text.isBlank()) error("模型返回内容为空")
                text.trim()
            }
        }
    }

    /** 连通性测试：发送一条极短的 ping。 */
    suspend fun test(profile: AiProfileEntity): Result<String> =
        complete(
            profile = profile,
            turns = listOf(ChatTurn("user", "ping，请只回复 ok")),
            maxTokens = 16
        )

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") || base.endsWith("/openai") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun parseContent(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ""
        val choices = root["choices"]?.jsonArray ?: return ""
        val first = choices.firstOrNull()?.jsonObject ?: return ""
        val message = first["message"]?.jsonObject ?: return ""
        val content = message["content"]
        return when (content) {
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
    }
}
