package com.jiuguan.ailink.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jiuguan.ailink.model.BridgeType
import com.jiuguan.ailink.model.Platform
import com.jiuguan.ailink.model.TriggerScope
import com.jiuguan.ailink.model.bridgeOf
import com.jiuguan.ailink.model.platformOf
import com.jiuguan.ailink.model.scopeOf

/**
 * 一个被托管的账号：QQ 或微信，对应一套接入参数与 AI 回复策略。
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val platform: String = Platform.QQ.name,
    val bridgeType: String = BridgeType.DEMO.name,
    /** 展示用名称，例如「工作 QQ」。 */
    val displayName: String = "",
    /** 账号号码 / 微信号，仅用于展示与核对。 */
    val accountNo: String = "",
    /** OneBot v11 正向 WebSocket 地址，例如 ws://192.168.1.10:3001。 */
    val endpoint: String = "",
    /** 桥接端访问令牌，可留空。 */
    val accessToken: String = "",
    /** 绑定的 AI 服务配置 id。 */
    val aiProfileId: String = "",
    val autoReply: Boolean = true,
    val triggerScope: String = TriggerScope.PRIVATE_AND_AT.name,
    /** 收到消息后延迟回复的毫秒数，模拟真人节奏。 */
    val replyDelayMs: Long = 1200L,
    /** 人设补充提示词。 */
    val persona: String = "",
    /** 忽略关键词，英文逗号分隔。命中则不回复。 */
    val ignoreKeywords: String = "",
    /** 是否参与接入（服务启动时自动上线）。 */
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOnlineAt: Long = 0L
) {
    val platformType: Platform get() = platformOf(platform)
    val bridge: BridgeType get() = bridgeOf(bridgeType)
    val scope: TriggerScope get() = scopeOf(triggerScope)

    val ignoreKeywordList: List<String>
        get() = ignoreKeywords.split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
