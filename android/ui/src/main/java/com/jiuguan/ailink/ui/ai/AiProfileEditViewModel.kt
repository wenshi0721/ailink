package com.jiuguan.ailink.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.ai.AiEndpoint
import com.jiuguan.ailink.data.ai.AiHeaders
import com.jiuguan.ailink.data.ai.AiTestResult
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.model.AiAuthStyle
import com.jiuguan.ailink.model.AiProtocol
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
    val protocol: AiProtocol = AiProtocol.OPENAI_COMPAT,
    val authStyle: AiAuthStyle = AiAuthStyle.BEARER,
    /** 留空 = 按协议自动补全；填写后完全以用户值为准。 */
    val chatPath: String = "",
    val extraHeaders: String = "",
    val timeoutSec: Float = 60f,
    val maxRetries: Int = 1,
    val temperature: Float = 0.8f,
    val maxTokens: Float = 512f,
    val systemPrompt: String = "",
    /** 识图开关：开启时按自动检测结论决定是否把图片送进模型。 */
    val visionEnabled: Boolean = true,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    /** 旧版本迁移而来、尚未确认；保存时若仍停用则保留该标记。 */
    val needsReview: Boolean = false
) {
    companion object {
        fun from(entity: AiProfileEntity) = AiForm(
            id = entity.id,
            name = entity.name,
            baseUrl = entity.baseUrl,
            apiKey = entity.apiKey,
            model = entity.model,
            protocol = entity.protocolType,
            authStyle = entity.auth,
            chatPath = entity.chatPath,
            extraHeaders = entity.extraHeaders,
            timeoutSec = entity.timeoutSec.toFloat(),
            maxRetries = entity.maxRetries,
            temperature = entity.temperature,
            maxTokens = entity.maxTokens.toFloat(),
            systemPrompt = entity.systemPrompt,
            visionEnabled = entity.visionEnabled,
            enabled = entity.enabled,
            isDefault = entity.isDefault,
            needsReview = entity.needsReview
        )

        /**
         * 新增配置的初始值：只给系统提示词，不预填任何厂商地址与模型。
         *
         * 旧版本默认套用 DeepSeek 模板，等于让所有人一进来就连到同一个接口；
         * 一旦该接口调整或下线，用户会在不知情时调用失效地址。现在必须由用户
         * 主动点选模板或手动填写，地址为空时明确走内置模拟回复。
         */
        fun preset() = AiForm(systemPrompt = DEFAULT_SYSTEM_PROMPT)

        const val DEFAULT_SYSTEM_PROMPT = "你是一个礼貌、自然的聊天伙伴，回复简短口语化。"
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

    /** 本次进入页面后的测试结论；保存过结论时用持久化值初始化。 */
    var testResult by mutableStateOf<AiTestResult?>(null)
        private set

    /** 配置改动后旧结论失效，界面提示「需重新测试」。 */
    var testStale by mutableStateOf(false)
        private set

    var saved by mutableStateOf(false)
        private set

    var visionStatus by mutableStateOf(VisionSupport.UNKNOWN)
        private set

    var visionChecking by mutableStateOf(false)
        private set

    var showAdvanced by mutableStateOf(false)
        private set

    var showKey by mutableStateOf(false)
        private set

    private var existing: AiProfileEntity? = null

    /** 进入页面时的表单快照，用于判断有没有未保存的修改。 */
    private var baseline: AiForm? = null

    val isNew: Boolean get() = profileId.isBlank()

    /** 已保存之后如果又改了字段，仍然算「有未保存修改」，避免点「完成」时静默丢改动。 */
    val isDirty: Boolean get() = baseline != null && form != baseline

    /** 当前地址命中的厂商模板；手动改成自定义地址后为 null。 */
    val selectedVendor: AiVendor?
        get() = AiVendorCatalog.matchByBaseUrl(form.baseUrl)

    /** 实际会请求的地址，供界面直接展示，避免用户猜「到底拼成什么」。 */
    val resolvedEndpoint: String
        get() = AiEndpoint.resolve(form.baseUrl, form.chatPath)

    init {
        viewModelScope.launch {
            if (profileId.isNotBlank()) {
                val current = container.aiRepository.find(profileId)
                existing = current
                if (current != null) {
                    form = AiForm.from(current)
                    if (current.lastTestAt > 0) {
                        testResult = AiTestResult(
                            ok = current.lastTestOk,
                            summary = if (current.lastTestOk) "上次测试成功" else "上次测试失败",
                            detail = current.lastTestMessage
                        )
                    }
                }
            } else {
                // 提前分配 id：测试、识图探测、保存都使用同一个 id，
                // 否则每次调用都会生成新 id，缓存与测试结论无法对应。
                form = AiForm.preset().copy(id = Ids.newId())
            }
            baseline = form
            refreshVisionStatus()
            autoDetectIfNeeded()
        }
    }

    fun toggleAdvanced() {
        showAdvanced = !showAdvanced
    }

    fun toggleShowKey() {
        showKey = !showKey
    }

    private fun refreshVisionStatus() {
        visionStatus = container.visionProbe.cached(toEntity(form))
    }

    /**
     * 结论未知时在后台补一次检测：用户打开配置页几秒后就能看到真实结果。
     * 待确认/停用的配置不探测，避免无意中请求旧接口。
     */
    private fun autoDetectIfNeeded() {
        val current = form
        if (!current.visionEnabled || current.baseUrl.isBlank()) return
        if (!current.enabled) return
        if (visionStatus != VisionSupport.UNKNOWN) return
        visionChecking = true
        viewModelScope.launch {
            visionStatus = container.visionProbe.detect(toEntity(current))
            visionChecking = false
        }
    }

    fun setVisionEnabled(enabled: Boolean) {
        update { it.copy(visionEnabled = enabled) }
        if (!enabled) {
            visionStatus = VisionSupport.UNSUPPORTED
        } else {
            refreshVisionStatus()
            autoDetectIfNeeded()
        }
    }

    fun setEnabled(enabled: Boolean) {
        update {
            it.copy(
                enabled = enabled,
                // 停用默认配置时同时取消默认身份，否则保存时 setDefault 会把它重新启用
                isDefault = if (enabled) it.isDefault else false,
                needsReview = if (enabled) false else it.needsReview
            )
        }
        if (enabled) autoDetectIfNeeded()
    }

    fun setDefault(isDefault: Boolean) {
        update { it.copy(isDefault = isDefault, enabled = if (isDefault) true else it.enabled) }
    }

    fun update(transform: (AiForm) -> AiForm) {
        val before = form
        form = transform(form)
        if (error.isNotBlank()) error = ""
        val connectionChanged = listOf(
            before.baseUrl to form.baseUrl,
            before.model to form.model,
            before.apiKey to form.apiKey,
            before.protocol to form.protocol,
            before.chatPath to form.chatPath,
            before.authStyle to form.authStyle,
            before.extraHeaders to form.extraHeaders
        ).any { (a, b) -> a != b }

        if (connectionChanged) {
            testStale = testResult != null
            refreshVisionStatus()
        }
    }

    /**
     * 套用厂商模板：覆盖地址、模型与协议相关项，并按需替换配置名称。
     * 用户自定义的名称不会被覆盖；自定义请求头会清空，避免把上一家的鉴权头带给新厂商。
     */
    fun applyVendor(vendor: AiVendor) {
        val previous = selectedVendor
        val keepName = form.name.isNotBlank() && form.name != previous?.name
        update {
            it.copy(
                name = if (keepName) it.name else vendor.name,
                baseUrl = vendor.baseUrl,
                model = vendor.defaultModelId,
                protocol = AiProtocol.OPENAI_COMPAT,
                authStyle = if (vendor.local) AiAuthStyle.NONE else AiAuthStyle.BEARER,
                chatPath = "",
                extraHeaders = ""
            )
        }
        testResult = null
        testStale = false
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
        protocol = current.protocol.name,
        authStyle = current.authStyle.name,
        chatPath = current.chatPath.trim(),
        extraHeaders = current.extraHeaders.trim(),
        timeoutSec = current.timeoutSec.toInt().coerceIn(5, 300),
        maxRetries = current.maxRetries.coerceIn(0, 3),
        temperature = current.temperature,
        maxTokens = current.maxTokens.toInt(),
        systemPrompt = current.systemPrompt.trim(),
        visionEnabled = current.visionEnabled,
        enabled = current.enabled,
        isDefault = current.isDefault,
        needsReview = if (current.enabled) false else current.needsReview,
        lastTestAt = if (testStale) 0L else existing?.lastTestAt ?: 0L,
        lastTestOk = if (testStale) false else existing?.lastTestOk ?: false,
        lastTestMessage = if (testStale) "" else existing?.lastTestMessage.orEmpty(),
        createdAt = existing?.createdAt ?: System.currentTimeMillis()
    )

    fun testConnection() {
        val current = form
        error = ""
        val reason = connectionError(current)
        if (reason != null) {
            testResult = AiTestResult.failure("配置不完整", reason)
            return
        }

        testing = true
        visionChecking = current.visionEnabled && current.baseUrl.isNotBlank() && current.enabled
        testResult = null
        viewModelScope.launch {
            val entity = toEntity(current)
            val probe = if (visionChecking) {
                async { container.visionProbe.detect(entity, force = true) }
            } else {
                null
            }
            val result = container.aiClient.test(entity)
            testResult = result
            testStale = false
            if (visionChecking) visionStatus = probe?.await() ?: visionStatus
            visionChecking = false
            testing = false

            // 只持久化「已保存配置且当前表单未改动」的结论，避免把未保存内容误记为已测。
            if (existing != null && !testStale) {
                container.aiRepository.recordTest(
                    id = entity.id,
                    ok = result.ok,
                    message = buildString {
                        appendLine(result.summary)
                        if (result.detail.isNotBlank()) appendLine(result.detail)
                        if (result.reply.isNotBlank()) append("返回：${result.reply}")
                    }.trim()
                )
                existing = existing?.copy(
                    lastTestAt = System.currentTimeMillis(),
                    lastTestOk = result.ok,
                    lastTestMessage = result.summary
                )
            }
        }
    }

    /** 与 AiClient.validate 保持同一套规则，保证「保存能过、运行却报错」不会发生。 */
    private fun connectionError(current: AiForm): String? {
        if (current.baseUrl.isBlank()) {
            return "未填写接口地址：当前会使用内置模拟回复。填写 Base URL 后可测试。"
        }
        AiEndpoint.validateBaseUrl(current.baseUrl)?.let { return it }
        if (current.protocol == AiProtocol.CUSTOM && current.chatPath.isBlank()) {
            return "已选择「自定义路径」，请填写接口路径（例如 /chat/completions）"
        }
        AiEndpoint.validateChatPath(current.chatPath)?.let { return it }
        if (current.model.isBlank()) return "请填写模型名称，例如 deepseek-flash / gpt-4o-mini"
        val headers = AiHeaders.parse(current.extraHeaders)
        if (!headers.valid) return "自定义请求头有误：${headers.errors.first()}"
        if (current.authStyle.requiresKey && current.apiKey.isBlank()) {
            return "未填写 API Key。若该服务无需鉴权，请把「认证方式」改为「不发送密钥」"
        }
        return null
    }

    /**
     * 保存配置。
     *
     * [testAfter] 为 true 时保存后立刻跑一次测试：用户不必自己判断
     * 「保存完还要不要再点测试」，失败也留在本页，修正建议就在下方。
     */
    fun save(testAfter: Boolean = false) {
        val current = autoNamed(form)
        // 纯本地模拟回复允许地址为空；否则走完整校验
        if (current.baseUrl.isNotBlank()) {
            connectionError(current)?.let {
                error = it
                return
            }
        }
        error = ""
        viewModelScope.launch {
            val entity = toEntity(current)
            container.aiRepository.save(entity)
            existing = entity
            baseline = current
            val others = container.aiRepository.list()
            val hasDefault = others.any { it.isDefault && it.id != entity.id }
            // 第一份真实可用的配置自动成为默认：避免「配好了，账号却还在用模拟回复」
            if (entity.isDefault || (!hasDefault && entity.baseUrl.isNotBlank() && entity.enabled)) {
                container.aiRepository.setDefault(entity.id)
            }
            saved = true
            if (testAfter && entity.baseUrl.isNotBlank() && entity.enabled) {
                testConnection()
            }
        }
    }

    /** 名称留空时按模型或域名取名，少一个必填项，也避免保存被卡住。 */
    private fun autoNamed(current: AiForm): AiForm {
        if (current.name.isNotBlank()) return current
        val name = current.model.trim().ifBlank {
            current.baseUrl.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .ifBlank { "AI 配置" }
        }
        val next = current.copy(name = name)
        form = next
        return next
    }
}
