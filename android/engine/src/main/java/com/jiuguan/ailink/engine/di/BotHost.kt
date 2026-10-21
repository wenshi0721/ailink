package com.jiuguan.ailink.engine.di

import com.jiuguan.ailink.engine.bot.BotManager

/**
 * 机器人引擎宿主，由 Application 实现。
 *
 * 为什么不是「容器宿主」：`:engine` 不能依赖 `:assembly`（装配层反过来要依赖本模块
 * 才能构造 BotManager）。所以这里只声明本模块真正需要的那一个成员，
 * 由 `:app` 的 Application 把容器里的 BotManager 转发出来，依赖链保持单向。
 */
interface BotHost {
    val botManager: BotManager
}
