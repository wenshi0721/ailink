package com.jiuguan.ailink.model

/**
 * 「快速配置」模板：一家主流模型厂商的接口地址与常用模型。
 *
 * 只承载静态文案与地址，不涉及网络与持久化。模板只用于预填，
 * 用户仍可自由修改地址与模型，因此模板失配时不会阻塞任何流程。
 */
data class AiVendor(
    val id: String,
    val name: String,
    /**
     * 预置接口地址。路径不是 /v1 的服务（如智谱的 /api/paas/v4、方舟的 /api/v3）
     * 直接写成完整的 .../chat/completions，避免客户端再拼接出错误路径。
     */
    val baseUrl: String,
    /** 兼容地址别名，用于编辑既有配置时反查厂商（例如带不带 /v1 两种写法）。 */
    val aliases: List<String> = emptyList(),
    /** 常用模型，首个作为默认值；为空表示厂商要求填接入点 ID，需用户手动填写。 */
    val models: List<AiModelOption> = emptyList(),
    /** 密钥申请入口提示，只作说明文案。 */
    val keyHint: String = "",
    /** 模型名不是通用别名的厂商，给出填写说明。 */
    val modelHint: String = "",
    /** 本地自建服务：无需密钥，地址只在设备或局域网内有效。 */
    val local: Boolean = false
) {
    val defaultModelId: String get() = models.firstOrNull()?.id.orEmpty()
}

/** 模板里的一个可选模型：id 为接口实际传参，label 为界面展示名。 */
data class AiModelOption(val id: String, val label: String)

/**
 * 内置厂商目录。排序按国内用户使用广度，DeepSeek 置首位作为新配置的默认模板。
 *
 * 模型名多为厂商长期维护的通用别名；具体可售模型以各厂商控制台为准，
 * 因此界面只作推荐、输入框始终可编辑。
 */
object AiVendorCatalog {

    val vendors: List<AiVendor> = listOf(
        AiVendor(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            aliases = listOf("https://api.deepseek.com"),
            models = listOf(
                AiModelOption("deepseek-chat", "deepseek-chat 通用"),
                AiModelOption("deepseek-reasoner", "deepseek-reasoner 推理")
            ),
            keyHint = "platform.deepseek.com → API keys，密钥以 sk- 开头"
        ),
        AiVendor(
            id = "qwen",
            name = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf(
                AiModelOption("qwen-plus", "qwen-plus 均衡"),
                AiModelOption("qwen-max", "qwen-max 效果优先"),
                AiModelOption("qwen-turbo", "qwen-turbo 速度优先")
            ),
            keyHint = "阿里云百炼控制台 → API-KEY 管理（密钥与地域绑定）"
        ),
        AiVendor(
            id = "kimi",
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            models = listOf(
                AiModelOption("moonshot-v1-8k", "moonshot-v1-8k"),
                AiModelOption("moonshot-v1-32k", "moonshot-v1-32k"),
                AiModelOption("kimi-latest", "kimi-latest")
            ),
            keyHint = "platform.moonshot.cn → API Key 管理"
        ),
        AiVendor(
            id = "zhipu",
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            aliases = listOf("https://open.bigmodel.cn/api/paas/v4"),
            models = listOf(
                AiModelOption("glm-4-plus", "glm-4-plus 通用"),
                AiModelOption("glm-4-flash", "glm-4-flash 快速"),
                AiModelOption("glm-4-air", "glm-4-air 轻量")
            ),
            keyHint = "bigmodel.cn 控制台 → API Keys"
        ),
        AiVendor(
            id = "ark",
            name = "豆包方舟",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            aliases = listOf("https://ark.cn-beijing.volces.com/api/v3"),
            keyHint = "火山引擎方舟控制台 → API Key 管理",
            modelHint = "该厂商需要先在控制台创建推理接入点，模型名填接入点 ID（ep-…）"
        ),
        AiVendor(
            id = "siliconflow",
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            models = listOf(
                AiModelOption("deepseek-ai/DeepSeek-V3", "DeepSeek-V3"),
                AiModelOption("Qwen/Qwen2.5-7B-Instruct", "Qwen2.5 7B")
            ),
            keyHint = "cloud.siliconflow.cn → API 密钥"
        ),
        AiVendor(
            id = "hunyuan",
            name = "腾讯混元",
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            models = listOf(
                AiModelOption("hunyuan-turbos-latest", "hunyuan-turbos 通用"),
                AiModelOption("hunyuan-lite", "hunyuan-lite 轻量")
            ),
            keyHint = "腾讯云控制台 → 混元大模型 → API Key"
        ),
        AiVendor(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            models = listOf(
                AiModelOption("gpt-4o-mini", "gpt-4o-mini 轻量"),
                AiModelOption("gpt-4o", "gpt-4o 多模态")
            ),
            keyHint = "platform.openai.com → API keys（国内网络需自备代理）"
        ),
        AiVendor(
            id = "ollama",
            name = "Ollama 本地",
            baseUrl = "http://127.0.0.1:11434/v1",
            models = listOf(
                AiModelOption("qwen2.5:7b", "qwen2.5:7b"),
                AiModelOption("llama3.1:8b", "llama3.1:8b")
            ),
            keyHint = "本地服务，密钥可留空；手机访问电脑需把地址改成电脑局域网 IP",
            modelHint = "模型名与 `ollama list` 的输出保持一致",
            local = true
        )
    )

    fun byId(id: String): AiVendor? = vendors.firstOrNull { it.id == id }

    /**
     * 按接口地址反查厂商，用于编辑既有配置时高亮模板。
     * 归一化掉首尾空白、末尾斜杠与大小写，并同时比较别名与去掉 /chat/completions 的根地址，
     * 因此「https://api.deepseek.com/v1/」与「https://api.deepseek.com/v1」视为同一厂商。
     */
    fun matchByBaseUrl(baseUrl: String): AiVendor? {
        val target = rootOf(baseUrl)
        if (target.isBlank()) return null
        return vendors.firstOrNull { vendor ->
            val candidates = buildList {
                add(vendor.baseUrl)
                addAll(vendor.aliases)
            }
            candidates.any { rootOf(it) == target }
        }
    }

    private fun rootOf(url: String): String = url
        .trim()
        .lowercase()
        .trimEnd('/')
        .removeSuffix("/chat/completions")
        .trimEnd('/')
}
