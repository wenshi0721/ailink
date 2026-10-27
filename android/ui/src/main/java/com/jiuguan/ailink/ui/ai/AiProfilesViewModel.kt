package com.jiuguan.ailink.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.ai.AiTestResult
import com.jiuguan.ailink.data.local.AiProfileEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AiProfilesViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.aiRepository

    val profiles: StateFlow<List<AiProfileEntity>> = repository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 正在测试的配置 id；空串表示没有测试在进行。 */
    var testingId: String by mutableStateOf("")
        private set

    /** 本次会话内的测试结论，用于列表即时反馈；持久化结论仍以数据库为准。 */
    var testResults: Map<String, AiTestResult> by mutableStateOf(emptyMap())
        private set

    var notice: String by mutableStateOf("")
        private set

    fun clearNotice() {
        notice = ""
    }

    fun setEnabled(profile: AiProfileEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(profile.id, enabled)
            notice = if (enabled) {
                "已启用「${profile.name.ifBlank { "未命名配置" }}」"
            } else {
                "已停用「${profile.name.ifBlank { "未命名配置" }}」，引用它的账号将改用默认配置或内置回复"
            }
        }
    }

    fun setDefault(profile: AiProfileEntity) {
        viewModelScope.launch {
            repository.setDefault(profile.id)
            notice = "已将「${profile.name.ifBlank { "未命名配置" }}」设为默认，并自动启用"
        }
    }

    fun clearDefault(profile: AiProfileEntity) {
        viewModelScope.launch {
            repository.clearDefault(profile.id)
            notice = "已取消默认。账号未单独指定配置时将使用内置模拟回复"
        }
    }

    fun duplicate(profile: AiProfileEntity) {
        viewModelScope.launch {
            val copy = repository.duplicate(profile)
            notice = "已复制为「${copy.name}」，请到编辑页修改名称与密钥"
        }
    }

    fun delete(profile: AiProfileEntity) {
        viewModelScope.launch {
            repository.delete(profile.id)
            testResults = testResults - profile.id
            notice = "已删除「${profile.name.ifBlank { "未命名配置" }}」"
        }
    }

    /** 一键确认所有旧配置：仅在用户通过对话框确认后调用。 */
    fun confirmAllLegacy() {
        viewModelScope.launch {
            val pending = profiles.value.filter { it.needsReview }
            pending.forEach { repository.setEnabled(it.id, true) }
            notice = "已确认并启用 ${pending.size} 个旧配置"
        }
    }

    fun test(profile: AiProfileEntity) {
        if (testingId.isNotBlank()) return
        testingId = profile.id
        notice = ""
        viewModelScope.launch {
            val result = container.aiClient.test(profile)
            testResults = testResults + (profile.id to result)
            repository.recordTest(
                id = profile.id,
                ok = result.ok,
                message = buildString {
                    appendLine(result.summary)
                    if (result.detail.isNotBlank()) appendLine(result.detail)
                    if (result.reply.isNotBlank()) append("返回：${result.reply}")
                }.trim()
            )
            testingId = ""
        }
    }
}
