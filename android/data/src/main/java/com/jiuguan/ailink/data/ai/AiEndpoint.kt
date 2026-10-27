package com.jiuguan.ailink.data.ai

/**
 * 接口地址的单一拼接入口。
 *
 * 旧实现把「补 /v1」的规则散落在请求方法里，遇到 `/api/v3`、`/api/paas/v4`
 * 这类路径会拼出重复前缀。这里把规则收敛成三步，并且允许用户用 [chatPath]
 * 完全接管，避免任何「猜」的行为：
 *
 * 1. chatPath 非空 → 以其为准（可以是相对路径，也可以是完整 URL）；
 * 2. chatPath 为空且地址已以 /chat/completions 结尾 → 原样使用；
 * 3. chatPath 为空 → 已带版本段的地址直接补 /chat/completions，
 *    其余按 OpenAI 习惯补 /v1/chat/completions（兼容旧配置）。
 */
object AiEndpoint {

    private val VERSION_SUFFIXES = listOf(
        "/v1", "/v2", "/v3", "/v4", "/openai", "/compatible-mode/v1"
    )

    fun resolve(baseUrl: String, chatPath: String = ""): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return ""
        val path = chatPath.trim()
        if (path.isNotBlank()) {
            if (path.startsWith("http://") || path.startsWith("https://")) return path.trimEnd('/')
            return base + "/" + path.trimStart('/')
        }
        if (base.endsWith("/chat/completions")) return base
        if (VERSION_SUFFIXES.any { base.endsWith(it) }) return "$base/chat/completions"
        return "$base/v1/chat/completions"
    }

    /** 校验 Base URL，返回可直接展示给用户的原因；合法返回 null。 */
    fun validateBaseUrl(baseUrl: String): String? {
        val value = baseUrl.trim()
        if (value.isEmpty()) return "请填写接口地址（Base URL），例如 https://api.deepseek.com/v1"
        if (value.contains(' ')) return "接口地址不能包含空格"
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return "接口地址需以 http:// 或 https:// 开头"
        }
        val host = value.removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
        if (host.isBlank()) return "接口地址缺少域名，请检查是否只填了 https://"
        return null
    }

    fun validateChatPath(chatPath: String): String? {
        val value = chatPath.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return null
        if (!value.startsWith("/")) return "接口路径需以 / 开头，或填写完整 URL"
        return null
    }
}
