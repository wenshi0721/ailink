package com.jiuguan.ailink.ui.accounts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.core.PairingBus
import com.jiuguan.ailink.core.reply.BehaviorConfig
import com.jiuguan.ailink.data.gateway.ConnectionTester
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.engine.container.LocalRuntime
import com.jiuguan.ailink.engine.service.QqBridgeService
import com.jiuguan.ailink.model.BridgeType
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.model.TriggerScope
import com.jiuguan.ailink.util.Ids
import com.jiuguan.ailink.util.TextUtils
import kotlinx.coroutines.launch

/**
 * 接入方式的四种预设。
 *
 * 把「平台 + 桥接类型 + 地址来源」这三个原本要用户自己组合的维度，收敛成一次选择：
 * 选完就知道地址该填什么、要不要装运行时、该去哪里拿配对码。
 */
enum class AccountPreset(
    val title: String,
    val subtitle: String,
    val tag: String
) {
    BUILTIN_QQ(
        title = "手机内置 QQ",
        subtitle = "不用电脑，App 内运行 QQ + NapCat；首次需扫码登录",
        tag = "推荐"
    ),
    LAN_QQ(
        title = "电脑/服务器上的 QQ",
        subtitle = "已有 NapCat、LLOneBot、Lagrange 等 OneBot 桥接端",
        tag = "OneBot"
    ),
    WECHAT(
        title = "微信（桥接）",
        subtitle = "需在 Windows 上运行微信 hook 桥接，输出 OneBot 协议",
        tag = "微信"
    ),
    DEMO(
        title = "本地演示",
        subtitle = "不连接真实账号，先验证自动回复流程",
        tag = "体验"
    )
}

data class AccountForm(
    val id: String = "",
    val preset: AccountPreset = AccountPreset.LAN_QQ,
    val platform: Platform = Platform.QQ,
    val bridge: BridgeType = BridgeType.ONEBOT_V11,
    val displayName: String = "",
    val accountNo: String = "",
    val endpoint: String = "",
    val accessToken: String = "",
    val aiProfileId: String = "",
    val autoReply: Boolean = true,
    val scope: TriggerScope = TriggerScope.PRIVATE_AND_AT,
    val replyDelayMs: Float = 1200f,
    val persona: String = "",
    val ignoreKeywords: String = "",
    val enabled: Boolean = true,
    /** 回复行为（分段 / 主动 / 限流）。不落 accounts 表，保存时写进偏好。 */
    val behavior: BehaviorConfig = BehaviorConfig.DEFAULT
) {
    companion object {
        fun from(entity: AccountEntity, behavior: BehaviorConfig) = AccountForm(
            id = entity.id,
            preset = presetOf(entity),
            platform = entity.platformType,
            bridge = entity.bridge,
            displayName = entity.displayName,
            accountNo = entity.accountNo,
            endpoint = entity.endpoint,
            accessToken = entity.accessToken,
            aiProfileId = entity.aiProfileId,
            autoReply = entity.autoReply,
            scope = entity.scope,
            replyDelayMs = entity.replyDelayMs.toFloat(),
            persona = entity.persona,
            ignoreKeywords = entity.ignoreKeywords,
            enabled = entity.enabled,
            behavior = behavior
        )

        fun presetOf(entity: AccountEntity): AccountPreset = when {
            entity.bridge == BridgeType.DEMO -> AccountPreset.DEMO
            entity.platformType == Platform.WECHAT -> AccountPreset.WECHAT
            isLocalEndpoint(entity.endpoint) -> AccountPreset.BUILTIN_QQ
            else -> AccountPreset.LAN_QQ
        }

        fun isLocalEndpoint(endpoint: String): Boolean =
            endpoint.contains("127.0.0.1") || endpoint.contains("localhost")
    }
}

/** 接入配置最近一次测试的结论，用于展示「已保存未测试 / 测试成功 / 测试失败」。 */
data class BridgeTestStatus(
    val ok: Boolean,
    val at: Long,
    val message: String
) {
    companion object {
        val NONE = BridgeTestStatus(ok = false, at = 0L, message = "")

        fun parse(raw: String): BridgeTestStatus {
            if (raw.isBlank()) return NONE
            val parts = raw.split('|', limit = 3)
            if (parts.size < 3) return NONE
            return BridgeTestStatus(
                ok = parts[0] == "1",
                at = parts[1].toLongOrNull() ?: 0L,
                message = parts[2]
            )
        }
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

    var testStatus by mutableStateOf(BridgeTestStatus.NONE)
        private set

    var saved by mutableStateOf(false)
        private set

    var deleted by mutableStateOf(false)
        private set

    var confirmDelete by mutableStateOf(false)
        private set

    var showAdvanced by mutableStateOf(false)
        private set

    /** 手机内置运行时的安装 / 运行状态，用于引导而不是让用户自己去找。 */
    var runtimeInstalled by mutableStateOf(LocalRuntime(container.appContext).isQqRuntimeInstalled())
        private set

    var qqRunning by mutableStateOf(QqBridgeService.running.value)
        private set

    var qqLoggedIn by mutableStateOf(QqBridgeService.loggedIn.value)
        private set

    /** 接入总开关，保存结果页据此决定是「启动」还是「完成」。 */
    var engineRunning by mutableStateOf(container.prefs.engineRunning.value)
        private set

    /** 保存前自动测试失败：让用户在「返回修改」与「仍然保存」之间选，而不是直接拦死。 */
    var saveBlocked by mutableStateOf(false)
        private set

    private var existing: AccountEntity? = null
    private var loaded = false

    /** 进入页面时的表单快照，用于判断有没有未保存的修改。 */
    private var baseline: AccountForm? = null
    private var pendingStartAfterSave = false

    val isNew: Boolean get() = accountId.isBlank()

    val isDirty: Boolean get() = !saved && baseline != null && form != baseline

    /** 当前绑定配置的展示信息（含是否默认 / 是否可用）。 */
    val selectedProfile: AiProfileEntity?
        get() = profiles.firstOrNull { it.id == form.aiProfileId }

    /**
     * 实际会生效的配置。
     *
     * 账号没有单独指定时，运行时会用默认配置；界面不能只显示「内置模拟回复」，
     * 否则用户会误以为配好的 API 没被使用。
     */
    val effectiveProfile: AiProfileEntity?
        get() = selectedProfile
            ?: profiles.firstOrNull { it.isDefault && it.enabled && !it.needsReview }

    init {
        // 新建账号时，如果是从配对码进来的，先取出并清空，保证只用一次
        val paired = if (accountId.isBlank()) PairingBus.consume() else null

        viewModelScope.launch {
            QqBridgeService.running.collect { qqRunning = it }
        }
        viewModelScope.launch {
            QqBridgeService.loggedIn.collect { qqLoggedIn = it }
        }
        viewModelScope.launch {
            container.prefs.engineRunning.collect { engineRunning = it }
        }
        // 用户在运行时页装完后回到本页，状态要跟着更新，否则会一直提示「去安装」
        viewModelScope.launch {
            com.jiuguan.ailink.engine.service.RuntimeInstallService.finished.collect { message ->
                if (!message.isNullOrBlank()) {
                    runtimeInstalled = LocalRuntime(container.appContext).isQqRuntimeInstalled()
                }
            }
        }

        viewModelScope.launch {
            container.aiRepository.observeProfiles().collect { list ->
                profiles = list
                if (!loaded || list.isEmpty()) return@collect
                // 绑定的配置被删除后不要留下悬空 id
                if (list.none { it.id == form.aiProfileId }) {
                    val next = preferredProfileId(list)
                    form = form.copy(aiProfileId = next)
                    // 自动回填不算用户修改，否则返回时会误报「有未保存的更改」
                    baseline = baseline?.copy(aiProfileId = next)
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
                current != null -> AccountForm.from(current, container.prefs.behavior(current.id))

                paired != null -> AccountForm(
                    id = Ids.newId(),
                    preset = when {
                        paired.platform == Platform.WECHAT -> AccountPreset.WECHAT
                        AccountForm.isLocalEndpoint(paired.endpoint) -> AccountPreset.BUILTIN_QQ
                        else -> AccountPreset.LAN_QQ
                    },
                    platform = paired.platform,
                    bridge = BridgeType.ONEBOT_V11,
                    displayName = paired.name.ifBlank {
                        if (paired.platform == Platform.WECHAT) "微信账号" else "QQ 账号"
                    },
                    endpoint = paired.endpoint,
                    accessToken = paired.token,
                    aiProfileId = preferredProfileId(profiles)
                )

                else -> {
                    val preset = if (runtimeInstalled) AccountPreset.BUILTIN_QQ else AccountPreset.LAN_QQ
                    newFormFor(preset).copy(aiProfileId = preferredProfileId(profiles))
                }
            }

            loaded = true
            // profiles 的 Flow 可能在 loaded 之前就发出过一次，这里补一次推荐值，
            // 避免新账号保存时 aiProfileId 为空（运行时只能回退到默认配置）。
            if (form.aiProfileId.isBlank()) {
                val list = container.aiRepository.list()
                profiles = list
                if (list.isNotEmpty()) {
                    form = form.copy(aiProfileId = preferredProfileId(list))
                }
            }
            if (!isNew) {
                testStatus = BridgeTestStatus.parse(container.prefs.bridgeTest(form.id))
            } else {
                testStatus = BridgeTestStatus.NONE
            }

            baseline = form

            // 配对码已经带上地址与令牌，直接验一次连通性，省掉一步手动操作
            if (paired != null && !paired.endpoint.isBlank()) testConnection()
        }
    }

    private fun preferredProfileId(list: List<AiProfileEntity>): String {
        val usable = list.filter { it.enabled && !it.needsReview }
        return usable.firstOrNull { it.isDefault }?.id
            ?: usable.firstOrNull { !it.isLocalOnly }?.id
            ?: usable.firstOrNull { it.isLocalOnly }?.id
            ?: ""
    }

    /**
     * 选择接入方式：一次性写好平台、桥接类型与地址来源。
     *
     * 只覆盖「为接入方式服务」的字段；用户已经填的号码、人设等保持不变。
     * 内置 QQ 与局域网 QQ 都是 OneBot，但地址来源不同，所以必须由预设区分。
     */
    fun applyPreset(preset: AccountPreset) {
        if (form.preset == preset) return
        val next = when (preset) {
            AccountPreset.BUILTIN_QQ -> newFormFor(preset).copy(
                id = form.id,
                displayName = keepOr(form.displayName, "内置 QQ"),
                accountNo = form.accountNo.ifBlank { container.prefs.qqUin.value },
                aiProfileId = form.aiProfileId,
                autoReply = form.autoReply,
                scope = form.scope,
                replyDelayMs = form.replyDelayMs,
                persona = form.persona,
                ignoreKeywords = form.ignoreKeywords,
                enabled = form.enabled,
                behavior = form.behavior
            )

            AccountPreset.LAN_QQ -> newFormFor(preset).copy(
                id = form.id,
                displayName = keepOr(form.displayName, "QQ 账号"),
                accountNo = form.accountNo,
                // 保留用户已填的远程地址；从内置/微信切过来时清空，避免端口与令牌串用
                endpoint = if (AccountForm.isLocalEndpoint(form.endpoint) ||
                    form.platform == Platform.WECHAT
                ) {
                    ""
                } else {
                    form.endpoint
                },
                accessToken = if (AccountForm.isLocalEndpoint(form.endpoint) ||
                    form.platform == Platform.WECHAT
                ) {
                    ""
                } else {
                    form.accessToken
                },
                aiProfileId = form.aiProfileId,
                autoReply = form.autoReply,
                scope = form.scope,
                replyDelayMs = form.replyDelayMs,
                persona = form.persona,
                ignoreKeywords = form.ignoreKeywords,
                enabled = form.enabled,
                behavior = form.behavior
            )

            AccountPreset.WECHAT -> newFormFor(preset).copy(
                id = form.id,
                displayName = keepOr(form.displayName, "微信账号"),
                accountNo = form.accountNo,
                aiProfileId = form.aiProfileId,
                autoReply = form.autoReply,
                scope = form.scope,
                replyDelayMs = form.replyDelayMs,
                persona = form.persona,
                ignoreKeywords = form.ignoreKeywords,
                enabled = form.enabled,
                behavior = form.behavior
            )

            AccountPreset.DEMO -> newFormFor(preset).copy(
                id = form.id,
                displayName = keepOr(form.displayName, "演示账号"),
                aiProfileId = form.aiProfileId,
                autoReply = form.autoReply,
                scope = form.scope,
                replyDelayMs = form.replyDelayMs,
                persona = form.persona,
                ignoreKeywords = form.ignoreKeywords,
                enabled = form.enabled,
                behavior = form.behavior
            )
        }
        form = next
        error = ""
        testStatus = BridgeTestStatus.NONE
        container.prefs.clearBridgeTest(form.id)
    }

    private fun keepOr(current: String, fallback: String): String =
        if (current.isBlank() || current in DEFAULT_NAMES) fallback else current

    private fun newFormFor(preset: AccountPreset): AccountForm = when (preset) {
        AccountPreset.BUILTIN_QQ -> AccountForm(
            id = if (form.id.isBlank()) Ids.newId() else form.id,
            preset = preset,
            platform = Platform.QQ,
            bridge = BridgeType.ONEBOT_V11,
            displayName = "内置 QQ",
            endpoint = "ws://${LocalRuntime.LOCAL_WS_HOST}:${LocalRuntime.LOCAL_WS_PORT}",
            accessToken = LocalRuntime.LOCAL_WS_TOKEN
        )

        AccountPreset.LAN_QQ -> AccountForm(
            id = if (form.id.isBlank()) Ids.newId() else form.id,
            preset = preset,
            platform = Platform.QQ,
            bridge = BridgeType.ONEBOT_V11,
            displayName = "QQ 账号",
            endpoint = "",
            accessToken = ""
        )

        AccountPreset.WECHAT -> AccountForm(
            id = if (form.id.isBlank()) Ids.newId() else form.id,
            preset = preset,
            platform = Platform.WECHAT,
            bridge = BridgeType.ONEBOT_V11,
            displayName = "微信账号",
            endpoint = "",
            accessToken = ""
        )

        AccountPreset.DEMO -> AccountForm(
            id = if (form.id.isBlank()) Ids.newId() else form.id,
            preset = preset,
            platform = Platform.QQ,
            bridge = BridgeType.DEMO,
            displayName = "演示账号"
        )
    }

    fun update(transform: (AccountForm) -> AccountForm) {
        val before = form
        form = transform(form)
        if (error.isNotBlank()) error = ""
        // 只有影响连通性的字段变化才作废旧测试结论；改人设/延迟不应让用户重测
        val connectionChanged = before.endpoint != form.endpoint ||
            before.accessToken != form.accessToken ||
            before.bridge != form.bridge
        if (connectionChanged) {
            container.prefs.clearBridgeTest(form.id)
            testStatus = BridgeTestStatus.NONE
        }
    }

    fun toggleAdvanced() {
        showAdvanced = !showAdvanced
    }

    fun testConnection() {
        val current = form
        if (current.bridge == BridgeType.DEMO) {
            val message = "演示模式无需外部连接；启动接入服务后会自动产生模拟消息"
            testStatus = BridgeTestStatus(true, System.currentTimeMillis(), message)
            container.prefs.setBridgeTest(current.id, true, message)
            return
        }
        if (!TextUtils.isValidWsUrl(current.endpoint)) {
            val message = "请先填写有效的 ws:// 或 wss:// 地址，例如 ws://192.168.1.10:3001"
            testStatus = BridgeTestStatus(false, System.currentTimeMillis(), message)
            container.prefs.setBridgeTest(current.id, false, message)
            return
        }

        testing = true
        viewModelScope.launch {
            ConnectionTester.testWebSocket(current.endpoint, current.accessToken)
                .fold(
                    onSuccess = { cost ->
                        val message = "握手成功（${cost} ms）。启动接入服务后即可收发消息。"
                        testStatus = BridgeTestStatus(true, System.currentTimeMillis(), message)
                        container.prefs.setBridgeTest(current.id, true, message)
                    },
                    onFailure = { e ->
                        val message = explainFailure(current, e)
                        testStatus = BridgeTestStatus(false, System.currentTimeMillis(), message)
                        container.prefs.setBridgeTest(current.id, false, message)
                    }
                )
            testing = false
        }
    }

    /**
     * 把底层异常翻译成可执行的下一步。
     *
     * 不追求覆盖所有异常，只覆盖用户最常踩的四类：没启动、地址错、令牌错、网络不通。
     */
    private fun explainFailure(current: AccountForm, error: Throwable): String {
        val raw = error.message.orEmpty()
        val lower = raw.lowercase()
        val local = AccountForm.isLocalEndpoint(current.endpoint)
        return when {
            lower.contains("401") || lower.contains("403") || lower.contains("forbidden") ->
                "认证失败：访问令牌与桥接端不一致。请复制 NapCat 配置里的 token 重新填写。"

            lower.contains("refused") || lower.contains("econnrefused") ->
                if (local) {
                    "本机端口未监听：手机内置 QQ 还没启动。请到「设置 → 手机内置运行时」启动 QQ 并完成扫码登录。"
                } else {
                    "目标端口拒绝连接：确认电脑上的 NapCat 已启动，且正向 WS 端口（默认 3001）未被防火墙拦截。"
                }

            lower.contains("timeout") || lower.contains("超时") ->
                if (local) {
                    "连接超时：内置 QQ 可能仍在启动。首次启动需要 30-60 秒，请稍后重试。"
                } else {
                    "连接超时：手机与电脑不在同一网络，或地址/端口填错。局域网请使用电脑的内网 IP。"
                }

            lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
                "域名解析失败：请检查地址拼写；局域网请直接填 IP，不要用电脑名。"

            else -> "连接失败：${raw.ifBlank { "未知错误" }}。请确认桥接端已启动、地址与令牌正确。"
        }
    }

    private fun validate(current: AccountForm): String? {
        if (current.bridge == BridgeType.ONEBOT_V11) {
            if (current.endpoint.isBlank()) {
                return "还没有填 WebSocket 地址。选「手机内置 QQ」会自动填好；用电脑桥接请填 ws://电脑IP:3001。"
            }
            if (!TextUtils.isValidWsUrl(current.endpoint)) {
                return "地址格式不对：需要以 ws:// 或 wss:// 开头，例如 ws://192.168.1.5:3001。"
            }
            // 内置运行时的安装状态不阻塞保存：允许先建好配置，装完即可使用；界面会持续提示
            if (current.accountNo.isNotBlank() && current.platform == Platform.QQ &&
                !current.accountNo.all { it.isDigit() }
            ) {
                return "QQ 号只能是数字（当前填了「${current.accountNo.take(12)}」）。不确定就留空，登录后会自动带入。"
            }
        }
        return null
    }

    /**
     * 保存的统一入口。
     *
     * 没测试过就先自动测一次：成功直接保存，失败把选择权交给用户
     * （返回修改 / 仍然保存），避免「保存完才发现连不上」。
     */
    fun requestSave(startAfterSave: Boolean = false) {
        val current = form
        validate(current)?.let {
            error = it
            return
        }
        error = ""
        pendingStartAfterSave = startAfterSave

        val needsTest = current.bridge == BridgeType.ONEBOT_V11 &&
            !(testStatus.at > 0 && testStatus.ok) &&
            // 内置 QQ 还没装/没启动/没登录时必然连不上，自动测试只会白弹一次失败；
            // 这种「先建配置、稍后安装」的路径保持直接保存，页面上的安装引导已经足够明确。
            !(current.preset == AccountPreset.BUILTIN_QQ &&
                (!runtimeInstalled || !qqRunning || !qqLoggedIn))
        if (!needsTest) {
            save(startAfterSave)
            return
        }

        testing = true
        viewModelScope.launch {
            ConnectionTester.testWebSocket(current.endpoint, current.accessToken)
                .fold(
                    onSuccess = { cost ->
                        val message = "握手成功（${cost} ms）。启动接入服务后即可收发消息。"
                        testStatus = BridgeTestStatus(true, System.currentTimeMillis(), message)
                        container.prefs.setBridgeTest(current.id, true, message)
                        testing = false
                        save(startAfterSave)
                    },
                    onFailure = { e ->
                        val message = explainFailure(current, e)
                        testStatus = BridgeTestStatus(false, System.currentTimeMillis(), message)
                        container.prefs.setBridgeTest(current.id, false, message)
                        testing = false
                        saveBlocked = true
                    }
                )
        }
    }

    /** 测试未通过时用户仍选择保存：不丢配置，后续可在账号页继续修复。 */
    fun saveAnyway() {
        saveBlocked = false
        save(pendingStartAfterSave)
    }

    fun dismissSaveBlock() {
        saveBlocked = false
    }

    fun startEngine() = container.botManager.setEngineRunning(true)

    fun save(startAfterSave: Boolean = false) {
        val current = form
        validate(current)?.let {
            error = it
            return
        }
        error = ""

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = AccountEntity(
                id = existing?.id ?: current.id.ifBlank { Ids.newId() },
                platform = current.platform.name,
                bridgeType = current.bridge.name,
                displayName = current.displayName.trim().ifBlank { defaultName(current) },
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
            // 行为配置不进 accounts 表（避免迁移与回滚清库），保存时同步写偏好
            container.prefs.setBehavior(entity.id, current.behavior)

            // 内置 QQ 的账号号码顺带写进快速登录配置：下次启动可免扫码
            if (entity.platform == Platform.QQ.name &&
                AccountForm.isLocalEndpoint(entity.endpoint) &&
                entity.accountNo.isNotBlank() &&
                entity.accountNo.all { it.isDigit() }
            ) {
                container.prefs.setQqUin(entity.accountNo)

                // 保存后顺手把 QQ 服务拉起来，省掉「再去设置页点启动」这一步
                if (runtimeInstalled && !QqBridgeService.running.value) {
                    QqBridgeService.start(container.appContext)
                }
            }

            container.botManager.restart(entity)

            // 「保存并开始使用」：一并打开接入总开关，省掉再回首页拨开关的一步
            if (startAfterSave) {
                container.botManager.setEngineRunning(true)
            }
            saved = true
        }
    }

    private fun defaultName(current: AccountForm): String = when (current.preset) {
        AccountPreset.BUILTIN_QQ -> "内置 QQ"
        AccountPreset.DEMO -> "演示账号"
        AccountPreset.WECHAT -> "微信账号"
        AccountPreset.LAN_QQ ->
            if (current.accountNo.isNotBlank()) "QQ ${current.accountNo.takeLast(4)}" else "QQ 账号"
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
            container.prefs.clearBridgeTest(current.id)
            container.prefs.clearBehavior(current.id)
            if (container.prefs.activeAccountId.value == current.id) {
                container.prefs.setActiveAccount("")
            }
            confirmDelete = false
            deleted = true
        }
    }

    private companion object {
        /** 预设生成的默认名称；切换预设时允许覆盖，用户自填的名字则保留。 */
        val DEFAULT_NAMES = setOf("内置 QQ", "QQ 账号", "微信账号", "演示账号")
    }
}
