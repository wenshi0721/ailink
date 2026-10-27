package com.jiuguan.ailink.data.ai

/**
 * 自定义请求头的解析与校验。
 *
 * 采用「一行一个 `Name: Value`」而不是 JSON：手输成本最低，复制粘贴浏览器抓包
 * 结果后几乎不用改格式。解析失败时返回具体行号，界面可直接提示。
 */
object AiHeaders {

    data class Parsed(
        val headers: List<Pair<String, String>> = emptyList(),
        val errors: List<String> = emptyList()
    ) {
        val valid: Boolean get() = errors.isEmpty()
    }

    private val NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    /** 这些头由客户端自己管理，用户填写会破坏请求，直接拒绝。 */
    private val RESERVED = setOf("host", "content-length", "connection")

    fun parse(raw: String): Parsed {
        val headers = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String>()
        raw.lines().forEachIndexed { index, line ->
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#") || text.startsWith("//")) return@forEachIndexed
            val separator = text.indexOf(':')
            if (separator <= 0) {
                errors += "第 ${index + 1} 行格式应为「名称: 值」"
                return@forEachIndexed
            }
            val name = text.substring(0, separator).trim()
            val value = text.substring(separator + 1).trim()
            when {
                !NAME.matches(name) -> errors += "第 ${index + 1} 行的请求头名称不合法：$name"
                name.lowercase() in RESERVED -> errors += "第 ${index + 1} 行：$name 由客户端管理，不能自定义"
                value.isEmpty() -> errors += "第 ${index + 1} 行的值为空"
                else -> headers += name to value
            }
        }
        return Parsed(headers, errors)
    }
}
