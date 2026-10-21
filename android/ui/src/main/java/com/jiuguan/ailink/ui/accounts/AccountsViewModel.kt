package com.jiuguan.ailink.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.engine.service.RuntimeState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccountsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.accountRepository
    private val botManager = container.botManager
    private val prefs = container.prefs

    /** 手机内置 QQ 运行时是否已安装（用于显示「一键接入」入口）。 */
    val localRuntimeInstalled: Boolean =
        com.jiuguan.ailink.engine.container.LocalRuntime(container.appContext).isQqRuntimeInstalled()

    val accounts: StateFlow<List<AccountEntity>> = repository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val runtimeStates: StateFlow<Map<String, RuntimeState>> = botManager.states
    val activeAccountId: StateFlow<String> = prefs.activeAccountId

    fun setEnabled(account: AccountEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(account.id, enabled)
            if (enabled) {
                if (prefs.engineRunning.value) botManager.startAccount(account.copy(enabled = true))
            } else {
                botManager.stopAccount(account.id)
            }
        }
    }

    fun setActive(accountId: String) = prefs.setActiveAccount(accountId)

    fun delete(account: AccountEntity) {
        viewModelScope.launch {
            botManager.stopAccount(account.id)
            repository.delete(account.id)
            container.messageRepository.clearAccountData(account.id)
            if (prefs.activeAccountId.value == account.id) prefs.setActiveAccount("")
        }
    }
}
