package com.jiuguan.ailink.model



/** 群聊触发范围。 */
/** 群聊触发范围。 */
enum class TriggerScope(val label: String, val desc: String) {
    PRIVATE_ONLY("仅私聊", "只回复好友私聊消息"),
    PRIVATE_AND_AT("私聊 + 群聊@", "群聊中被 @ 时才回复"),
    ALL("全部消息", "私聊与群聊消息都回复")
}
