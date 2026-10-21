package com.jiuguan.ailink.data.ai

import kotlin.random.Random

/**
 * 未配置外部 AI 接口时的内置回复器。
 * 目标不是「聪明」，而是保证在无网络、无密钥的情况下，
 * 自动回复链路仍然可运行、可演示、可回归测试。
 */
object LocalResponder {

    private val greetings = listOf(
        "在的，刚看到消息～",
        "来了来了，怎么啦？",
        "我在，你说。",
        "在呢，刚好看到。"
    )

    private val thanks = listOf(
        "客气啦，小事。",
        "不客气，随时找我。",
        "嗯嗯，收到，别放心上。"
    )

    private val farewell = listOf(
        "好，那我先忙一会儿。",
        "行，晚点聊～",
        "嗯，回头说。",
        "晚安，早点休息。"
    )

    private val busy = listOf(
        "我这会儿有点事，等下回你。",
        "在忙，稍等我看一下。",
        "手上还有活，回头细说。"
    )

    private val generic = listOf(
        "嗯，我看到了，等我理一下思路。",
        "这个话题可以展开说，你先说说你的想法？",
        "收到，我先记下来了。",
        "听起来挺有意思的，多说两句？",
        "可以，你先按你的想法来。",
        "我大概明白了，晚点给你准话。",
        "行，那就这么定，有问题再喊我。",
        "嗯嗯，我看到你发的了。"
    )

    fun reply(persona: String, incoming: String): String {
        val text = incoming.trim()

        val pool = when {
            text.matches(
                Regex(
                    "^(在吗|在不在|在\\?|hi|hello|你好|哈喽|早|早上好|晚上好).*",
                    RegexOption.IGNORE_CASE
                )
            ) -> greetings

            text.contains("谢谢") || text.contains("多谢") || text.contains("感谢") -> thanks
            text.contains("再见") || text.contains("拜拜") || text.contains("晚安") -> farewell
            text.contains("忙") && text.length < 12 -> busy
            else -> generic
        }

        return pool[Random.nextInt(pool.size)]
    }
}
