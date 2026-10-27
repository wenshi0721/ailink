package com.jiuguan.ailink.data.local

/**
 * AI 配置的展示状态，由持久化字段推导，不单独存储，避免状态与配置脱节。
 */
enum class AiProfileState(val label: String) {
    NEEDS_REVIEW("旧配置待确认"),
    DISABLED("已停用"),
    LOCAL("本地模拟"),
    UNCONFIGURED("未配置地址"),
    UNTESTED("未测试"),
    TEST_OK("测试通过"),
    TEST_FAILED("测试失败")
}

/**
 * 状态判定顺序即优先级：
 * 待确认 > 停用 > 本地模拟 > 未配置地址 > 未测试 > 测试成功/失败。
 *
 * 停用优先于测试结论：停用的配置即使曾经测试成功，也不会被业务使用。
 * 本地模拟（地址为空）是合法状态，不应被标成「配置错误」。
 */
fun AiProfileEntity.state(): AiProfileState = when {
    needsReview -> AiProfileState.NEEDS_REVIEW
    !enabled -> AiProfileState.DISABLED
    isLocalOnly -> AiProfileState.LOCAL
    lastTestAt <= 0L -> AiProfileState.UNTESTED
    lastTestOk -> AiProfileState.TEST_OK
    else -> AiProfileState.TEST_FAILED
}
