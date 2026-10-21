package com.jiuguan.ailink.util

object TextUtils {

    /** 头像文字：中文取首字，英文取首字母大写。 */
    fun initials(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val ch = trimmed.first()
        return if (ch.isLetter()) ch.uppercase() else ch.toString()
    }

    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    /** 单行摘要：压缩换行、限制长度。 */
    fun preview(text: String, max: Int = 42): String =
        truncate(text.replace('\n', ' ').trim(), max)

    fun parseKeywords(raw: String): List<String> =
        raw.split(',', '，', ' ', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun maskToken(token: String): String =
        if (token.length <= 6) "•".repeat(token.length) else token.take(4) + "••••" + token.takeLast(2)

    fun isValidWsUrl(url: String): Boolean {
        val v = url.trim()
        return (v.startsWith("ws://") || v.startsWith("wss://")) && v.length > 8
    }
}
