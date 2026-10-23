package com.jiuguan.ailink.data.ai

import com.jiuguan.ailink.core.AppPrefs
import com.jiuguan.ailink.core.SessionLog
import com.jiuguan.ailink.data.local.AiProfileEntity
import com.jiuguan.ailink.model.VisionSupport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 判断某份 AI 配置能否识图。
 *
 * 三层：用户开关 → 模型名快判 → 实测（一张内置小图）。实测结论按「地址 + 模型」缓存，
 * 配置一改指纹就对不上，自动重新判定；鉴权/额度等无法定性的情况不写缓存，下次再试。
 */
class VisionProbe(
    private val aiClient: AiClient,
    private val prefs: AppPrefs
) {

    /** 串行化探测，避免多个账号同时给同一份配置发探测请求。 */
    private val probeMutex = Mutex()

    /**
     * 已知结论（不触发网络）：开关关闭 → 不支持；有缓存用缓存；否则用模型名快判。
     * 供界面展示与回复链路的快速判断使用。
     */
    fun cached(profile: AiProfileEntity): VisionSupport {
        if (!profile.visionEnabled) return VisionSupport.UNSUPPORTED
        return readCache(profile)
            ?: VisionCatalog.quickJudge(profile.model)
            ?: VisionSupport.UNKNOWN
    }

    /** 返回 null 表示没有可用的缓存（没探过，或配置已改导致指纹不匹配）。 */
    private fun readCache(profile: AiProfileEntity): VisionSupport? {
        val raw = prefs.visionCache(profile.id)
        if (raw.isBlank()) return null
        val parts = raw.split('|', limit = 3)
        if (parts.size != 3) return null
        val status = runCatching { VisionSupport.valueOf(parts[0]) }.getOrNull() ?: return null
        if ("${parts[1]}|${parts[2]}" != fingerprintOf(profile)) return null
        return status
    }

    /**
     * 给出结论，必要时发一次探测请求。
     *
     * @param force true 表示忽略缓存（配置页「测试调用」用），仍遵守模型名快判
     */
    suspend fun detect(profile: AiProfileEntity, force: Boolean = false): VisionSupport {
        if (!profile.visionEnabled) return VisionSupport.UNSUPPORTED
        if (profile.isLocalOnly || profile.baseUrl.isBlank()) return VisionSupport.UNSUPPORTED

        VisionCatalog.quickJudge(profile.model)?.let { return it }

        if (!force) {
            readCache(profile)?.let { return it }
        }

        return probeMutex.withLock {
            if (!force) {
                readCache(profile)?.let { return@withLock it }
            }
            val result = aiClient.probeVision(profile)
            // UNKNOWN 也写缓存：否则每条图片消息都会重探一次，把回复拖慢十几秒。
            // 配置改动会让指纹失效；用户也可在配置页点「测试调用」强制重探。
            prefs.setVisionCache(profile.id, encode(profile, result))
            SessionLog.add(
                "vision",
                "识图检测：${describe(result)}（配置「${profile.name}」模型 ${profile.model.ifBlank { "未填" }}）"
            )
            result
        }
    }

    private fun encode(profile: AiProfileEntity, status: VisionSupport): String =
        "${status.name}|${profile.baseUrl.trim()}|${profile.model.trim()}"

    private fun fingerprintOf(profile: AiProfileEntity): String =
        "${profile.baseUrl.trim()}|${profile.model.trim()}"

    private fun describe(status: VisionSupport): String = when (status) {
        VisionSupport.SUPPORTED -> "支持图片输入"
        VisionSupport.UNSUPPORTED -> "不支持图片输入，按纯文本处理"
        VisionSupport.UNKNOWN -> "无法判定（鉴权/额度/网络问题），先按纯文本处理"
    }
}
