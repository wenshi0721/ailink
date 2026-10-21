package com.jiuguan.ailink.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.ConversationEntity
import com.jiuguan.ailink.engine.service.RuntimeState
import com.jiuguan.ailink.model.Direction
import com.jiuguan.ailink.util.TimeFmt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val accountRepository = container.accountRepository
    private val messageRepository = container.messageRepository
    private val botManager = container.botManager
    private val prefs = container.prefs

    val accounts: StateFlow<List<AccountEntity>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentConversations: StateFlow<List<ConversationEntity>> =
        messageRepository.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val runtimeStates: StateFlow<Map<String, RuntimeState>> = botManager.states

    val activeAccountId: StateFlow<String> = prefs.activeAccountId
    val engineRunning: StateFlow<Boolean> = prefs.engineRunning

    val todayIncoming: StateFlow<Int> = messageRepository
        .observeTodayCount(Direction.IN, TimeFmt.startOfDay())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val todayReplied: StateFlow<Int> = messageRepository
        .observeTodayCount(Direction.OUT, TimeFmt.startOfDay())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val todayFailed: StateFlow<Int> = messageRepository
        .observeFailedSince(TimeFmt.startOfDay())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setEngineRunning(running: Boolean) = botManager.setEngineRunning(running)

    fun selectAccount(accountId: String) = prefs.setActiveAccount(accountId)

    fun setAccountEnabled(account: AccountEntity, enabled: Boolean) {
        viewModelScope.launch {
            accountRepository.setEnabled(account.id, enabled)
            if (enabled) {
                if (prefs.engineRunning.value) {
                    botManager.startAccount(account.copy(enabled = true))
                }
            } else {
                botManager.stopAccount(account.id)
            }
        }
    }

    fun refreshAccount(accountId: String) {
        viewModelScope.launch {
            val account = accountRepository.find(accountId) ?: return@launch
            botManager.restart(account)
        }
    }
}
