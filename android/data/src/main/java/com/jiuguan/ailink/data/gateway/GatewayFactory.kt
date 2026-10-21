package com.jiuguan.ailink.data.gateway

import com.jiuguan.ailink.data.local.AccountEntity
import com.jiuguan.ailink.data.gateway.demo.DemoGateway
import com.jiuguan.ailink.data.gateway.onebot.OneBotGateway
import com.jiuguan.ailink.model.BridgeType
import kotlinx.coroutines.CoroutineScope

/** 根据账号配置创建对应的消息网关。 */
object GatewayFactory {

    fun create(account: AccountEntity, scope: CoroutineScope): MessageGateway =
        when (account.bridge) {
            BridgeType.ONEBOT_V11 -> OneBotGateway(
                url = account.endpoint.trim(),
                token = account.accessToken.trim(),
                scope = scope
            )

            BridgeType.DEMO -> DemoGateway(
                platform = account.platformType,
                scope = scope
            )
        }
}
