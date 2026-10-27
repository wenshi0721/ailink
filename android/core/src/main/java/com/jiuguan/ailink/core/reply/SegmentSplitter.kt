package com.jiuguan.ailink.core.reply

/**
 * 把一次 AI 生成拆成多条消息。
 *
 * 设计取舍：**模型分段优先，本地切分兜底**。
 * - 纯本地按标点切会把语义割裂（中文短句常常没有标点），所以先请模型用 `###` 自己分；
 * - 但模型不一定听话，于是保留兜底：把长段落按句末切开再均分到条数上限内。
 *
 * 两条路径都不做「每段必须多短」的硬约束——是否限制单段长度由 [MultiReplyConfig.segmentMaxChars]
 * 决定（0 = 不限）。真正防刷屏的是 [MultiReplyConfig.budgetChars] 与 [MultiReplyConfig.totalMaxChars]。
 */
object SegmentSplitter {

    /** 分段结果的来源。写进日志，用来判断「模型到底听话了没有」。 */
    enum class Source(val label: String) {
        /** 分段关闭，单条发送。 */
        SINGLE("单条"),

        /** 模型按 `###` 自己分的，语义最连贯。 */
        MODEL("模型"),

        /** 模型没给分隔符，本地按句末兜底切的，可能切断语义。 */
        SENTENCE("句末兜底"),

        /** 既没分隔符也没法按句切（一句话到底），整段一条。 */
        VERBATIM("整段")
    }

    /**
     * @param segments 最终要发出去的各段
     * @param source 这些段是怎么来的
     */
    data class Outcome(val segments: List<String>, val source: Source)

    /**
     * 模型被要求使用的分段标记：独立一行的 `###`（兼容 `---`）。
     *
     * 选 `###` 而不是换行的原因：换行在正常回复里太常见（段落、列举），
     * 用它当分隔符会把一条长回复误拆成很多碎片。
     */
    private val MARKER_LINE = Regex("(?m)^[ \\t]*(?:#{2,}|-{3,}|\\*{3,})[ \\t]*$")

    /** 句末切分点。分号也算：长句里分号之后通常可以断开换气。 */
    private val TERMINATORS = charArrayOf('。', '！', '？', '!', '?', '…', '~', '～', '；', ';')

    /** 兜底切分时，短于这个长度的碎片并进相邻段，避免「嗯。」「好的。」这种单字气泡。 */
    private const val MIN_FRAGMENT_CHARS = 10

    /**
     * @param raw 模型原始输出
     * @param config 多段配置；`splitting == false` 时只做清洗与总量截断，返回单条
     */
    fun split(raw: String, config: MultiReplyConfig): List<String> = splitWith(raw, config).segments

    /** 同 [split]，额外返回分段来源，供日志还原「模型听话了没有」。 */
    fun splitWith(raw: String, config: MultiReplyConfig): Outcome {
        // 关闭分段：原样返回一条，不清洗、不按句切分、不合并，
        // 截断方式与改造前的 `trim().take(600)` 逐字一致。
        if (!config.splitting) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return Outcome(emptyList(), Source.SINGLE)
            val capped = if (config.totalMaxChars > 0) trimmed.take(config.totalMaxChars) else trimmed
            return Outcome(listOf(capped), Source.SINGLE)
        }

        val text = clean(raw)
        if (text.isBlank()) return Outcome(emptyList(), Source.VERBATIM)

        val maxSegments = config.maxSegments.coerceAtLeast(1)

        // 1) 先用模型给的分隔符
        val byMarker = splitByMarker(text, maxSegments)
        val source: Source
        val segments: List<String>
        if (byMarker.size >= 2) {
            source = Source.MODEL
            segments = byMarker
        } else {
            // 2) 模型没分段：按句末切开，再均分到条数上限内
            segments = splitBySentences(text, maxSegments)
            source = if (segments.size > 1) Source.SENTENCE else Source.VERBATIM
        }

        // 3) 依次施加：单段上限 → 整轮上限
        val limited = segments
            .map { applySegmentLimit(it, config.segmentMaxChars) }
            .filter { it.isNotBlank() }
        return Outcome(capToLimit(limited, config.totalMaxChars), source)
    }

    /** 去掉会影响观感的 Markdown 残留。克制处理：只清明显是格式符的东西。 */
    internal fun clean(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')

        // 代码围栏整行去掉（保留内容）
        text = text.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n")

        // 加粗 / 斜体标记
        text = text.replace("**", "").replace("__", "")

        // 行首列表符号与有序列表编号
        text = text.lines().joinToString("\n") { line ->
            line.trimStart().let { trimmed ->
                when {
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") ->
                        trimmed.substring(2)
                    // 「1. 内容」这种整行带编号的，去掉编号；纯编号行（「1.」）本身也会被清成空
                    ORDERED_LIST.containsMatchIn(trimmed) -> ORDERED_LIST.replaceFirst(trimmed, "")
                    else -> line
                }
            }
        }

        // 连续空行压成一个，首尾空白去掉
        text = text.replace(Regex("\n{3,}"), "\n\n")
        return text.trim()
    }

    private val ORDERED_LIST = Regex("^\\d{1,2}[.)、]\\s*")

    /** 按独立成行的 `###` / `---` 切。超过条数上限的尾巴合并进最后一条，而不是丢弃。 */
    private fun splitByMarker(text: String, maxSegments: Int): List<String> {
        val parts = text.split(MARKER_LINE)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return parts
        return trimToLimit(parts, maxSegments)
    }

    /** 按句末标点切成句子，再按「每 N 句一组」均分到条数上限内。 */
    private fun splitBySentences(text: String, maxSegments: Int): List<String> {
        val sentences = splitSentences(text)
        if (sentences.size < 2) return listOf(text)

        val perGroup = ceilDiv(sentences.size, maxSegments)
        val grouped = if (perGroup <= 1) {
            // 句子数本来就不超过条数上限，逐个成条即可
            trimToLimit(sentences, maxSegments)
        } else {
            sentences.chunked(perGroup).map { it.joinToString("").trim() }
        }
        // 合并必须在分组之后统一做：否则「嗯。」这种语气词会被单独发出去
        return mergeTinyFragments(grouped)
    }

    private fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            buffer.append(ch)
            val isTerminator = ch == '\n' || TERMINATORS.contains(ch)
            // 连续终止符（「……」「？！」）只算一次断点，否则会切出只有一个标点的碎片
            val nextIsTerminator = index + 1 < text.length &&
                (text[index + 1] == '\n' || TERMINATORS.contains(text[index + 1]))
            if (isTerminator && !nextIsTerminator) {
                buffer.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
                buffer.clear()
            }
            index++
        }
        buffer.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /** 短碎片并进前一段；如果第一段就很短，则并进后一段。 */
    private fun mergeTinyFragments(segments: List<String>): List<String> {
        if (segments.size < 2) return segments
        val merged = mutableListOf<String>()
        for (segment in segments) {
            if (merged.isNotEmpty() && segment.length < MIN_FRAGMENT_CHARS) {
                merged[merged.lastIndex] = merged.last() + segment
            } else {
                merged += segment
            }
        }
        // 首段过短且还有后续：并进第二段，避免一上来发个语气词
        if (merged.size > 1 && merged.first().length < MIN_FRAGMENT_CHARS) {
            val head = merged.removeAt(0)
            merged[0] = head + merged[0]
        }
        return merged
    }

    /** 超过条数上限的尾巴并进最后一条：宁可一条长一点，也不丢内容。 */
    private fun trimToLimit(segments: List<String>, maxSegments: Int): List<String> {
        if (segments.size <= maxSegments) return segments
        val head = segments.take(maxSegments - 1)
        val tail = segments.drop(maxSegments - 1).joinToString("\n")
        return head + tail
    }

    /**
     * 单段限长：超额时截到最近的句末边界，避免把半句话发出去。
     * [limit] 为 0 表示不限制。
     */
    private fun applySegmentLimit(segment: String, limit: Int): String {
        if (limit <= 0 || segment.length <= limit) return segment
        val head = segment.take(limit)
        val boundary = head.lastIndexOfAny(TERMINATORS)
        val cut = if (boundary >= limit / 2) head.take(boundary + 1) else head
        return cut.trim()
    }

    /**
     * 整轮限长：累计超出预算后丢弃后续段，最后一段按预算截断。
     *
     * public 是给运行时做「单位时间输出总量」裁剪用的：每次发送前用剩余预算再过一遍。
     * [totalLimit] 为 0 表示不限制。
     */
    fun capToLimit(segments: List<String>, totalLimit: Int): List<String> {
        if (totalLimit <= 0) return segments
        val out = mutableListOf<String>()
        var used = 0
        for (segment in segments) {
            val remaining = totalLimit - used
            if (remaining <= 0) break
            if (segment.length <= remaining) {
                out += segment
                used += segment.length
            } else {
                val cut = applySegmentLimit(segment, remaining)
                if (cut.isNotBlank()) {
                    out += cut
                    used += cut.length
                }
                break
            }
        }
        return out
    }

    private fun ceilDiv(a: Int, b: Int): Int = if (b <= 0) a else (a + b - 1) / b
}
