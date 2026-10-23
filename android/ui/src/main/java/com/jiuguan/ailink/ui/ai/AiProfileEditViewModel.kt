package com.jiuguan.ailink.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.model.AiVendor
import com.jiuguan.ailink.model.AiVendorCatalog
import com.jiuguan.ailink.model.VisionSupport
import com.jiuguan.ailink.util.Ids
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

data class AiForm(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.8f,
    val maxTokens: Float = 512f,
    val systemPrompt: String = "",
    /** 识图开关：开启时按自动检测结论决定是否把图片送进模型。 */
    val visionEnabled: Boolean = true
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
            systemPrompt = entity.systemPrompt,
            visionEnabled = entity.visionEnabled
        )

        fun preset() = AiVendorCatalog.byId(DEFAULT_VENDOR_ID)?.let { vendor ->
            AiForm(
                name = vendor.name,
                baseUrl = vendor.baseUrl,
                model = vendor.defaultModelId,
                systemPrompt = "你是一个礼貌、自然的聊天伙伴，回复简短口语化。"
            )
        } ?: AiForm(systemPrompt = "你是一个礼貌、自然的聊天伙伴，回复简短口语化。")

        /** 新增配置时默认套用的厂商模板。 */
        private const val DEFAULT_VENDOR_ID = "deepseek"
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

    /** 识图能力的当前结论（名单/缓存），供界面展示。 */
    var visionStatus by mutableStateOf(VisionSupport.UNKNOWN)
        private set

    var visionChecking by mutableStateOf(false)
        private set

    private var existing: AiProfileEntity? = null

    val isNew: Boolean get() = profileId.isBlank()

    /** 当前地址命中的厂商模板；手动改成自定义地址后为 null。 */
    val selectedVendor: AiVendor?
        get() = AiVendorCatalog.matchByBaseUrl(form.baseUrl)

    init {
        viewModelScope.launch {
            if (profileId.isNotBlank()) {
                val current = container.aiRepository.find(profileId)
                existing = current
                if (current != null) form = AiForm.from(current)
            } else {
                form = AiForm.preset()
            }
            refreshVisionStatus()
        }
    }

    /** 只读现有结论，不发网络请求。 */
    private fun refreshVisionStatus() {
        visionStatus = container.visionProbe.cached(toEntity(form))
    }

    fun setVisionEnabled(enabled: Boolean) {
        update { it.copy(visionEnabled = enabled) }
        if (!enabled) {
            visionStatus = VisionSupport.UNSUPPORTED
        } else {
            refreshVisionStatus()
        }
    }

    fun update(transform: (AiForm) -> AiForm) {
        form = transform(form)
        if (error.isNotBlank()) error = ""
    }

    /**
     * 套用厂商模板：只覆盖地址与模型，并按需替换配置名称。
     * 用户自定义的名称（与上一个模板名不同的）不会被覆盖，避免反复切换厂商冲掉自己的命名。
     */
    fun applyVendor(vendor: AiVendor) {
        val previous = selectedVendor
        val keepName = form.name.isNotBlank() && form.name != previous?.name
        update {
            it.copy(
                name = if (keepName) it.name else vendor.name,
                baseUrl = vendor.baseUrl,
                model = vendor.defaultModelId
            )
        }
        testMessage = ""
    }

    fun applyModel(modelId: String) {
        update { it.copy(model = modelId) }
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
        visionEnabled = current.visionEnabled,
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
        visionChecking = current.visionEnabled
        testMessage = ""
        viewModelScope.launch {
            val entity = toEntity(current)
            // 连通性与识图检测互不依赖，并行发出，避免测试按钮等两次超时。
            val probe = async { container.visionProbe.detect(entity, force = true) }
            container.aiClient.test(entity).fold(
                onSuccess = { reply -> testMessage = "调用成功，模型回复：「${reply.take(40)}」" },
                onFailure = { e -> testMessage = "调用失败：${e.message ?: "未知错误"}" }
            )
            if (current.visionEnabled) visionStatus = probe.await()
            visionChecking = false
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
