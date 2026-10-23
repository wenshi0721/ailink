package com.jiuguan.ailink.assembly

import android.content.Context
import com.jiuguan.ailink.data.local.AiLinkDatabase
import com.jiuguan.ailink.data.ai.AiClient
import com.jiuguan.ailink.data.ai.ImagePrep
import com.jiuguan.ailink.data.ai.VisionProbe
import com.jiuguan.ailink.data.repo.AccountRepository
import com.jiuguan.ailink.data.repo.AiRepository
import com.jiuguan.ailink.data.repo.MessageRepository
import com.jiuguan.ailink.data.repo.SeedData
import com.jiuguan.ailink.data.update.UpdateRepository
import com.jiuguan.ailink.engine.bot.BotManager
import com.jiuguan.ailink.engine.service.BotService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.jiuguan.ailink.core.AppPrefs
import java.io.File

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
    // 图片落盘缓存：QQ 图床链接带 rkey 会过期，回看历史图片/引用图片只能靠本地副本
    val imagePrep = ImagePrep(File(appContext.filesDir, "imgcache"))
    val visionProbe = VisionProbe(aiClient, prefs)
    val updateRepository = UpdateRepository(appContext, prefs)

    val botManager = BotManager(
        context = appContext,
        prefs = prefs,
        accountRepository = accountRepository,
        messageRepository = messageRepository,
        aiRepository = aiRepository,
        aiClient = aiClient,
        visionProbe = visionProbe,
        imagePrep = imagePrep
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
        // 启动时静默检查应用更新（内部 6 小时节流，不打扰用户）
        appScope.launch { runCatching { updateRepository.maybeAutoCheck() } }
        // 图片缓存按 LRU 裁剪：200 张 / 40 MB 双上限
        appScope.launch { runCatching { imagePrep.prune() } }
    }
}
