package com.jiuguan.ailink.data.repo

import com.jiuguan.ailink.data.local.AccountDao
import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.local.AiProfileDao
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.model.BridgeType
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.util.Ids

/**
 * 首次启动时写入一条演示账号与一份内置 AI 配置，
 * 使应用无需任何外部服务即可完成完整的收发与自动回复验证。
 */
object SeedData {

    const val LOCAL_AI_ID = "ai_local_builtin"
    const val DEMO_ACCOUNT_ID = "acc_demo_local"

    suspend fun ensure(accountDao: AccountDao, aiProfileDao: AiProfileDao) {
        if (aiProfileDao.count() == 0) {
            aiProfileDao.upsert(
                AiProfileEntity(
                    id = LOCAL_AI_ID,
                    name = "内置模拟回复",
                    baseUrl = "",
                    apiKey = "",
                    model = "local-persona",
                    temperature = 0.8f,
                    maxTokens = 256,
                    systemPrompt = "你是一个礼貌、自然的聊天伙伴，回复简短口语化，控制在 1-3 句。",
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        if (accountDao.count() == 0) {
            val profileId = aiProfileDao.first()?.id ?: LOCAL_AI_ID
            accountDao.upsert(
                AccountEntity(
                    id = DEMO_ACCOUNT_ID,
                    platform = Platform.QQ.name,
                    bridgeType = BridgeType.DEMO.name,
                    displayName = "演示账号",
                    accountNo = "${Ids.randomNumber(100000, 999999)}",
                    aiProfileId = profileId,
                    autoReply = true,
                    persona = "你是这个 QQ 号的主人，正在用手机随手回复消息。",
                    enabled = true,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }
}
