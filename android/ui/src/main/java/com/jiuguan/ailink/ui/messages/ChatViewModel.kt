package com.jiuguan.ailink.ui.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.ConversationEntity
import com.jiuguan.ailink.data.local.MessageEntity
import com.jiuguan.ailink.data.gateway.accountIdOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val container: AppContainer,
    private val convId: String
) : ViewModel() {

    private val repository = container.messageRepository

    val conversation: StateFlow<ConversationEntity?> = repository.observeConversation(convId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<MessageEntity>> = repository.observeMessages(convId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val account: StateFlow<AccountEntity?> = container.accountRepository.observeAccounts()
        .map { list -> list.firstOrNull { it.id == accountIdOf(convId) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val runtimeStates = container.botManager.states

    /** 图片缓存文件名 → 本地文件；界面缩略图用，避免把绝对路径塞进 UI 层。 */
    fun imageFile(cache: String): java.io.File? = container.imagePrep.cacheFile(cache)

    var input by mutableStateOf("")
        private set

    var sending by mutableStateOf(false)
        private set

    var notice by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            repository.observeMessages(convId).collect {
                repository.markRead(convId)
            }
        }
    }

    fun onInputChange(value: String) {
        input = value
        if (notice.isNotBlank()) notice = ""
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        sending = true
        viewModelScope.launch {
            container.botManager.sendManual(accountIdOf(convId), convId, text)
                .onSuccess { input = "" }
                .onFailure { notice = it.message ?: "发送失败" }
            sending = false
        }
    }

    fun setAutoReply(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoReply(convId, enabled) }
    }

    /** 重发失败的消息：成功落库后删掉原来那条失败记录，避免记录里留下两条。 */
    fun retry(message: MessageEntity) {
        if (sending) return
        sending = true
        viewModelScope.launch {
            container.botManager.sendManual(accountIdOf(convId), convId, message.content)
                .onSuccess { repository.deleteMessage(message.id) }
                .onFailure { notice = it.message ?: "重试失败" }
            sending = false
        }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearConversation(convId) }
    }

    fun dismissNotice() {
        notice = ""
    }
}
