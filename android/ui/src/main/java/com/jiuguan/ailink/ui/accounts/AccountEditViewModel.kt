package com.jiuguan.ailink.ui.accounts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.core.PairingBus
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.data.gateway.ConnectionTester
import com.jiuguan.ailink.model.BridgeType
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.model.TriggerScope
import com.jiuguan.ailink.util.TextUtils
import kotlinx.coroutines.launch

data class AccountForm(
    val id: String = "",
    val platform: Platform = Platform.QQ,
    val bridge: BridgeType = BridgeType.DEMO,
    val displayName: String = "",
    val accountNo: String = "",
    val endpoint: String = "ws://192.168.1.10:3001",
    val accessToken: String = "",
    val aiProfileId: String = "",
    val autoReply: Boolean = true,
    val scope: TriggerScope = TriggerScope.PRIVATE_AND_AT,
    val replyDelayMs: Float = 1200f,
    val persona: String = "",
    val ignoreKeywords: String = "",
    val enabled: Boolean = true
) {
    companion object {
        fun from(entity: AccountEntity) = AccountForm(
            id = entity.id,
            platform = entity.platformType,
            bridge = entity.bridge,
            displayName = entity.displayName,
            accountNo = entity.accountNo,
            endpoint = entity.endpoint.ifBlank { "ws://192.168.1.10:3001" },
            accessToken = entity.accessToken,
            aiProfileId = entity.aiProfileId,
            autoReply = entity.autoReply,
            scope = entity.scope,
            replyDelayMs = entity.replyDelayMs.toFloat(),
            persona = entity.persona,
            ignoreKeywords = entity.ignoreKeywords,
            enabled = entity.enabled
        )
    }
}

class AccountEditViewModel(
    private val container: AppContainer,
    private val accountId: String
) : ViewModel() {

    var form by mutableStateOf(AccountForm())
        private set

    var profiles by mutableStateOf<List<AiProfileEntity>>(emptyList())
        private set

    var error by mutableStateOf("")
        private set

    var testing by mutableStateOf(false)
        private set

    var testMessage by mutableStateOf("")
        private set

    var saved by mutableStateOf(false)
        private set

    var deleted by mutableStateOf(false)
        private set

    var confirmDelete by mutableStateOf(false)
        private set

    private var existing: AccountEntity? = null

    val isNew: Boolean get() = accountId.isBlank()

    init {
        // 新建账号时，如果是从配对码进来的，先取出并清空，保证只用一次
        val paired = if (accountId.isBlank()) PairingBus.consume() else null

        viewModelScope.launch {
            container.aiRepository.observeProfiles().collect { list ->
                profiles = list
                if (form.aiProfileId.isBlank() && list.isNotEmpty() && existing == null) {
                    form = form.copy(aiProfileId = list.first().id)
                }
            }
        }

        viewModelScope.launch {
            val current = if (accountId.isNotBlank()) {
                container.accountRepository.find(accountId)
            } else {
                null
            }
            existing = current

            form = when {
                current != null -> AccountForm.from(current)

                paired != null -> AccountForm(
                    platform = paired.platform,
                    bridge = BridgeType.ONEBOT_V11,
                    displayName = paired.name.ifBlank {
                        if (paired.platform == Platform.WECHAT) "微信账号" else "QQ 账号"
                    },
                    endpoint = paired.endpoint,
                    accessToken = paired.token,
                    aiProfileId = profiles.firstOrNull()?.id.orEmpty()
                )

                else -> AccountForm(aiProfileId = profiles.firstOrNull()?.id.orEmpty())
            }

            // 配对码已经带上地址与令牌，直接验一次连通性，省掉一步手动操作
            if (paired != null) testConnection()
        }
    }

    fun update(transform: (AccountForm) -> AccountForm) {
        form = transform(form)
        if (error.isNotBlank()) error = ""
    }

    private fun isLocalEndpoint(endpoint: String): Boolean =
        endpoint.contains("127.0.0.1") || endpoint.contains("localhost")

    fun testConnection() {
        val current = form
        when (current.bridge) {
            BridgeType.DEMO -> {
                testMessage = "演示模式无需外部连接，启动服务后会自动产生模拟消息"
            }

            BridgeType.ONEBOT_V11 -> {
                if (!TextUtils.isValidWsUrl(current.endpoint)) {
                    testMessage = "请先填写有效的 ws:// 或 wss:// 地址"
                    return
                }
                testing = true
                testMessage = ""
                viewModelScope.launch {
                    ConnectionTester.testWebSocket(current.endpoint, current.accessToken)
                        .fold(
                            onSuccess = { cost -> testMessage = "握手成功，耗时 $cost ms" },
                            onFailure = { e ->
                                val base = "连接失败：${e.message ?: "未知错误"}"
                                testMessage = if (isLocalEndpoint(current.endpoint)) {
                                    "$base\n如果用的是手机内置 QQ，请先到「设置 → 手机内置运行时」点「启动 QQ」"
                                } else {
                                    base
                                }
                            }
                        )
                    testing = false
                }
            }
        }
    }

    fun save() {
        val current = form
        if (current.displayName.isBlank()) {
            error = "请填写账号备注名，例如「工作 QQ」"
            return
        }
        if (current.bridge == BridgeType.ONEBOT_V11) {
            if (!TextUtils.isValidWsUrl(current.endpoint)) {
                error = "WebSocket 地址需以 ws:// 或 wss:// 开头"
                return
            }
            if (current.accountNo.isBlank()) {
                error = "OneBot 接入请填写账号号码，便于核对登录身份"
                return
            }
        }
        error = ""

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = AccountEntity(
                id = existing?.id ?: current.id.ifBlank { com.jiuguan.ailink.util.Ids.newId() },
                platform = current.platform.name,
                bridgeType = current.bridge.name,
                displayName = current.displayName.trim(),
                accountNo = current.accountNo.trim(),
                endpoint = current.endpoint.trim(),
                accessToken = current.accessToken.trim(),
                aiProfileId = current.aiProfileId,
                autoReply = current.autoReply,
                triggerScope = current.scope.name,
                replyDelayMs = current.replyDelayMs.toLong(),
                persona = current.persona.trim(),
                ignoreKeywords = current.ignoreKeywords.trim(),
                enabled = current.enabled,
                createdAt = existing?.createdAt ?: now,
                lastOnlineAt = existing?.lastOnlineAt ?: 0L
            )
            container.accountRepository.save(entity)

            // 内置 QQ 的账号号码顺带写进快速登录配置：下次启动可免扫码，且 App 打开会自动恢复 QQ 服务
            if (entity.platform == Platform.QQ.name &&
                isLocalEndpoint(entity.endpoint) &&
                entity.accountNo.isNotBlank() &&
                entity.accountNo.all { it.isDigit() }
            ) {
                container.prefs.setQqUin(entity.accountNo)

                // 内置 QQ：保存后顺手把 QQ 服务拉起来，省掉「再去设置页点启动」这一步
                val runtime = com.jiuguan.ailink.engine.container.LocalRuntime(container.appContext)
                if (runtime.isQqRuntimeInstalled() &&
                    !com.jiuguan.ailink.engine.service.QqBridgeService.running.value
                ) {
                    com.jiuguan.ailink.engine.service.QqBridgeService.start(container.appContext)
                }
            }
            container.botManager.restart(entity)
            saved = true
        }
    }

    fun askDelete() {
        confirmDelete = true
    }

    fun dismissDelete() {
        confirmDelete = false
    }

    fun delete() {
        val current = existing ?: return
        viewModelScope.launch {
            container.botManager.stopAccount(current.id)
            container.accountRepository.delete(current.id)
            container.messageRepository.clearAccountData(current.id)
            if (container.prefs.activeAccountId.value == current.id) {
                container.prefs.setActiveAccount("")
            }
            confirmDelete = false
            deleted = true
        }
    }
}
