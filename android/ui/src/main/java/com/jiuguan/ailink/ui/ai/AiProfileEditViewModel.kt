package com.jiuguan.ailink.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.util.Ids
import kotlinx.coroutines.launch

data class AiForm(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.8f,
    val maxTokens: Float = 512f,
    val systemPrompt: String = ""
) {
    companion object {
        fun from(entity: AiProfileEntity) = AiForm(
            id = entity.id,
            name = entity.name,
            baseUrl = entity.baseUrl,
            apiKey = entity.apiKey,
            model = entity.model,
            temperature = entity.temperature,
            maxTokens = entity.maxTokens.toFloat(),
            systemPrompt = entity.systemPrompt
        )

        fun preset() = AiForm(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            systemPrompt = "你是一个礼貌、自然的聊天伙伴，回复简短口语化。"
        )
    }
}

class AiProfileEditViewModel(
    private val container: AppContainer,
    private val profileId: String
) : ViewModel() {

    var form by mutableStateOf(AiForm())
        private set

    var error by mutableStateOf("")
        private set

    var testing by mutableStateOf(false)
        private set

    var testMessage by mutableStateOf("")
        private set

    var saved by mutableStateOf(false)
        private set

    private var existing: AiProfileEntity? = null

    val isNew: Boolean get() = profileId.isBlank()

    init {
        viewModelScope.launch {
            if (profileId.isNotBlank()) {
                val current = container.aiRepository.find(profileId)
                existing = current
                if (current != null) form = AiForm.from(current)
            } else {
                form = AiForm.preset()
            }
        }
    }

    fun update(transform: (AiForm) -> AiForm) {
        form = transform(form)
        if (error.isNotBlank()) error = ""
    }

    private fun toEntity(current: AiForm): AiProfileEntity = AiProfileEntity(
        id = existing?.id ?: current.id.ifBlank { Ids.newId() },
        name = current.name.trim(),
        baseUrl = current.baseUrl.trim(),
        apiKey = current.apiKey.trim(),
        model = current.model.trim(),
        temperature = current.temperature,
        maxTokens = current.maxTokens.toInt(),
        systemPrompt = current.systemPrompt.trim(),
        createdAt = existing?.createdAt ?: System.currentTimeMillis()
    )

    fun testConnection() {
        val current = form
        if (current.baseUrl.isBlank()) {
            testMessage = "未填写接口地址：当前为本地模拟回复模式"
            return
        }
        if (!current.baseUrl.startsWith("http")) {
            testMessage = "接口地址需以 http:// 或 https:// 开头"
            return
        }
        testing = true
        testMessage = ""
        viewModelScope.launch {
            container.aiClient.test(toEntity(current)).fold(
                onSuccess = { reply -> testMessage = "调用成功，模型回复：「${reply.take(40)}」" },
                onFailure = { e -> testMessage = "调用失败：${e.message ?: "未知错误"}" }
            )
            testing = false
        }
    }

    fun save() {
        val current = form
        if (current.name.isBlank()) {
            error = "请填写配置名称"
            return
        }
        if (current.baseUrl.isNotBlank() && !current.baseUrl.startsWith("http")) {
            error = "接口地址需以 http:// 或 https:// 开头"
            return
        }
        if (current.baseUrl.isNotBlank() && current.model.isBlank()) {
            error = "请填写模型名称"
            return
        }
        error = ""
        viewModelScope.launch {
            container.aiRepository.save(toEntity(current))
            saved = true
        }
    }
}
