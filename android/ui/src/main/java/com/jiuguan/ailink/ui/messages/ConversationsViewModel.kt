package com.jiuguan.ailink.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.ConversationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ConversationFilter(val label: String) {
    ALL("全部"),
    UNREAD("未读"),
    GROUP("群聊"),
    PRIVATE("私聊")
}

class ConversationsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.messageRepository

    val accounts: StateFlow<List<AccountEntity>> = container.accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeAccountId: StateFlow<String> = container.prefs.activeAccountId

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(ConversationFilter.ALL)
    val filter: StateFlow<ConversationFilter> = _filter.asStateFlow()

    val conversations: StateFlow<List<ConversationEntity>> = combine(
        repository.observeConversations(),
        accounts,
        activeAccountId,
        _query,
        _filter
    ) { list, accountList, activeId, keyword, currentFilter ->
        list.asSequence()
            .filter { activeId.isBlank() || it.accountId == activeId }
            .filter { conversation ->
                when (currentFilter) {
                    ConversationFilter.ALL -> true
                    ConversationFilter.UNREAD -> conversation.unread > 0
                    ConversationFilter.GROUP -> conversation.isGroup
                    ConversationFilter.PRIVATE -> !conversation.isGroup
                }
            }
            .filter { conversation ->
                if (keyword.isBlank()) {
                    true
                } else {
                    conversation.title.contains(keyword, ignoreCase = true) ||
                        conversation.lastMessage.contains(keyword, ignoreCase = true) ||
                        accountList.firstOrNull { it.id == conversation.accountId }
                            ?.displayName?.contains(keyword, ignoreCase = true) == true
                }
            }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setFilter(value: ConversationFilter) {
        _filter.value = value
    }

    /** 空态里的一键复位：清空关键词并回到「全部」。 */
    fun clearFilters() {
        _query.value = ""
        _filter.value = ConversationFilter.ALL
    }

    fun markRead(convId: String) {
        viewModelScope.launch { repository.markRead(convId) }
    }

    fun accountOf(accountId: String): AccountEntity? =
        accounts.value.firstOrNull { it.id == accountId }
}
