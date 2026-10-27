package com.jiuguan.ailink.data.ai.provider

import com.jiuguan.ailink.model.AiProtocol

/**
 * 协议注册表：把配置里的协议字符串映射到具体实现。
 *
 * 当前只有 OpenAI 兼容协议；`CUSTOM` 共用同一实现，仅路径由用户显式指定。
 * 新增协议时在此注册即可，调用方与界面无需改动。
 */
object AiProviderRegistry {

    private val openAiCompatible = OpenAiCompatibleProvider()

    private val providers: Map<AiProtocol, AiProvider> = mapOf(
        AiProtocol.OPENAI_COMPAT to openAiCompatible,
        AiProtocol.CUSTOM to openAiCompatible
    )

    fun of(protocol: AiProtocol): AiProvider? = providers[protocol]

    val supported: List<AiProtocol> = providers.keys.toList()
}
