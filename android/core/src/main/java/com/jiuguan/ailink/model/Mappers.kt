package com.jiuguan.ailink.model



/** 枚举与数据库字符串之间的转换：实体里存字符串，读出来后用这些函数还原。 */
fun platformOf(value: String?): Platform =
    if (value == Platform.WECHAT.name) Platform.WECHAT else Platform.QQ

fun bridgeOf(value: String?): BridgeType =
    if (value == BridgeType.ONEBOT_V11.name) BridgeType.ONEBOT_V11 else BridgeType.DEMO

fun scopeOf(value: String?): TriggerScope =
    TriggerScope.entries.firstOrNull { it.name == value } ?: TriggerScope.PRIVATE_AND_AT

fun statusOf(value: String?): ConnStatus =
    ConnStatus.entries.firstOrNull { it.name == value } ?: ConnStatus.OFFLINE
