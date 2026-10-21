package com.jiuguan.ailink.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.local.AiProfileEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AiProfilesViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.aiRepository

    val profiles: StateFlow<List<AiProfileEntity>> = repository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(profile: AiProfileEntity) {
        viewModelScope.launch { repository.delete(profile.id) }
    }
}
