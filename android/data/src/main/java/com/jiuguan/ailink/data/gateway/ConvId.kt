package com.jiuguan.ailink.data.gateway

/**
 * 会话 id 与「账号 + 对端」之间的编解码。
 * 约定：convId = "<accountId>|<peerKey>"，实体里用它做唯一键，避免多账号串会话。
 */
fun convIdOf(accountId: String, peerKey: String): String = "$accountId|$peerKey"

fun peerKeyOf(convId: String): String = convId.substringAfter('|', convId)

fun accountIdOf(convId: String): String = convId.substringBefore('|', "")
