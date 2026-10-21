package com.jiuguan.ailink.assembly

import android.content.Context
import com.jiuguan.ailink.data.local.AiLinkDatabase
import com.jiuguan.ailink.data.ai.AiClient
import com.jiuguan.ailink.data.repo.AccountRepository
import com.jiuguan.ailink.data.repo.AiRepository
import com.jiuguan.ailink.data.repo.MessageRepository
import com.jiuguan.ailink.data.repo.SeedData
import com.jiuguan.ailink.engine.bot.BotManager
import com.jiuguan.ailink.engine.service.BotService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.jiuguan.ailink.core.AppPrefs

/**
 * 手工依赖容器：项目规模有限，用显式装配替代注解框架，
 * 减少构建期的注解处理与运行期反射。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val prefs = AppPrefs(appContext)

    private val database = AiLinkDatabase.get(appContext)

    val accountRepository = AccountRepository(database.accountDao())
    val aiRepository = AiRepository(database.aiProfileDao(), database.accountDao())
    val messageRepository = MessageRepository(database.conversationDao(), database.messageDao())
    val aiClient = AiClient()

    val botManager = BotManager(
        context = appContext,
        prefs = prefs,
        accountRepository = accountRepository,
        messageRepository = messageRepository,
        aiRepository = aiRepository
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bootstrap() {
        appScope.launch {
            runCatching {
                SeedData.ensure(database.accountDao(), database.aiProfileDao())
            }
            if (prefs.engineRunning.value) {
                BotService.start(appContext)
            }
        }
    }
}
