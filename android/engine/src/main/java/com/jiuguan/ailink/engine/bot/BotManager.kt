package com.jiuguan.ailink.engine.bot

import android.content.Context
import com.jiuguan.ailink.core.AppPrefs
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.gateway.GatewayFactory
import com.jiuguan.ailink.data.ai.AiClient
import com.jiuguan.ailink.data.gateway.peerKeyOf
import com.jiuguan.ailink.data.repo.AccountRepository
import com.jiuguan.ailink.data.repo.AiRepository
import com.jiuguan.ailink.data.repo.MessageRepository
import com.jiuguan.ailink.model.ConnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import com.jiuguan.ailink.engine.service.RuntimeState
import com.jiuguan.ailink.engine.service.BotService

/**
 * 全部账号运行时的集中管理：启动/停止、状态汇总、手动发送、服务保活。
 */
class BotManager(
    private val context: Context,
    private val prefs: AppPrefs,
    private val accountRepository: AccountRepository,
    private val messageRepository: MessageRepository,
    private val aiRepository: AiRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aiClient = AiClient()
    private val runtimes = ConcurrentHashMap<String, AccountRuntime>()

    private val _states = MutableStateFlow<Map<String, RuntimeState>>(emptyMap())
    val states: StateFlow<Map<String, RuntimeState>> = _states.asStateFlow()

    fun startAccount(account: AccountEntity) {
        if (runtimes.containsKey(account.id)) return

        val runtime = AccountRuntime(
            account = account,
            gateway = GatewayFactory.create(account, scope),
            scope = scope,
            messageRepository = messageRepository,
            aiRepository = aiRepository,
            aiClient = aiClient,
            onStateChanged = { state -> publish(state) }
        )
        runtimes[account.id] = runtime
        publish(RuntimeState(accountId = account.id, status = ConnStatus.CONNECTING))
        runtime.start()
    }

    fun stopAccount(accountId: String) {
        runtimes.remove(accountId)?.stop()
        _states.update { it - accountId }
    }

    fun stopAll() {
        runtimes.keys.toList().forEach { stopAccount(it) }
    }

    fun restart(account: AccountEntity) {
        stopAccount(account.id)
        if (prefs.engineRunning.value && account.enabled) startAccount(account)
    }

    suspend fun startAllEnabled() {
        accountRepository.listEnabled().forEach { startAccount(it) }
    }

    /** 总开关：同时负责前台服务的启停。 */
    fun setEngineRunning(running: Boolean) {
        prefs.setEngineRunning(running)
        if (running) {
            BotService.start(context)
            scope.launch { startAllEnabled() }
        } else {
            stopAll()
            BotService.stop(context)
        }
    }

    fun isRunning(accountId: String): Boolean = runtimes.containsKey(accountId)

    fun stateOf(accountId: String): RuntimeState =
        _states.value[accountId] ?: RuntimeState(accountId = accountId)

    /** 手动发送消息（聊天页输入框）。 */
    suspend fun sendManual(accountId: String, convId: String, text: String): Result<Unit> {
        val content = text.trim()
        if (content.isBlank()) return Result.failure(IllegalArgumentException("消息内容为空"))

        val account = accountRepository.find(accountId)
            ?: return Result.failure(IllegalStateException("账号不存在"))
        val runtime = runtimes[accountId]
            ?: return Result.failure(IllegalStateException("账号未上线，请先在概览页启动接入服务"))

        val conversation = messageRepository.findConversation(convId)
        val peerKey = peerKeyOf(convId)
        val isGroup = conversation?.isGroup ?: false

        val ok = runtime.send(peerKey, isGroup, content)
        messageRepository.recordOutgoing(
            account = account,
            peerKey = peerKey,
            title = conversation?.title.orEmpty(),
            isGroup = isGroup,
            text = content,
            success = ok
        )
        return if (ok) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("发送失败，请检查接入端连接状态"))
        }
    }

    private fun publish(state: RuntimeState) {
        _states.update { it + (state.accountId to state) }
    }
}
