package com.jiuguan.ailink.data.local

/**
 * 聚合查询的结果类型（只用于统计，不落表）。
 */

/** 某会话最后一次指定来源消息的时间。 */
data class ConvOriginStamp(
    val convId: String,
    val at: Long
)

/** 某会话里出现过的消息方向数量：2 表示双方都说过话。 */
data class ConvDirectionCount(
    val convId: String,
    val total: Int
)
