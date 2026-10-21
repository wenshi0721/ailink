package com.jiuguan.ailink.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.core.AppVersion
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.AiProfileEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val engineRunning: StateFlow<Boolean> = container.prefs.engineRunning

    val accounts: StateFlow<List<AccountEntity>> = container.accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val aiProfiles: StateFlow<List<AiProfileEntity>> = container.aiRepository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalMessages: StateFlow<Int> = container.messageRepository.observeTotalMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val versionName: String = AppVersion.name(container.appContext)

    fun setEngineRunning(running: Boolean) = container.botManager.setEngineRunning(running)

    fun clearAllMessages() {
        viewModelScope.launch { container.messageRepository.clearAll() }
    }
}
