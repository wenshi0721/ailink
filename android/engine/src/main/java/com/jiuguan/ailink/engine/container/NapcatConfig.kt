package com.jiuguan.ailink.engine.container

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File

/**
 * NapCat 反检测配置（`o3HookMode` + `bypass`）的读写。
 *
 * 为什么要做成开关：社区实践（XiaoYou-QQ-Bot）建议挂个人号时保持
 * `bypass` 六项全开、`o3HookMode = 0`，能显著降低被协议层识别后静默断流的概率。
 * 但这两项属于"押上稳定性换兼容性"的取舍，出问题时必须能一键退回默认值，
 * 所以这里保留原文件备份（`.bak`，只在第一次改写时生成）。
 *
 * 配置文件位置：NapCat 登录成功后按真实 uin 落盘 `napcat_<uin>.json`，
 * 未登录时只有全局 `napcat.json`；两者取其一，优先按号的。
 */
object NapcatConfig {

    private const val CONFIG_DIR = "rootfs/opt/QQ/resources/app/app_launcher/napcat/config"

    /** 六个 bypass 开关；全 true 表示"伪装全开"。 */
    private val BYPASS_KEYS = listOf("hook", "window", "module", "process", "container", "js")

    private val writer = Json { prettyPrint = true }

    data class State(
        val file: String,
        val exists: Boolean,
        val o3HookMode: Int,
        val bypassOn: Int
    ) {
        val hardened: Boolean get() = exists && o3HookMode == 0 && bypassOn == BYPASS_KEYS.size

        val summary: String
            get() = if (!exists) {
                "未找到配置文件"
            } else {
                "o3HookMode=$o3HookMode，bypass $bypassOn/${BYPASS_KEYS.size} 已开"
            }
    }

    /** 优先按号取配置，其次退回全局配置；都不存在返回 null。 */
    private fun locate(runtimeDir: File, uin: String): File? {
        val dir = File(runtimeDir, CONFIG_DIR)
        if (!dir.isDirectory) return null
        if (uin.isNotBlank()) {
            val perAccount = File(dir, "napcat_$uin.json")
            if (perAccount.isFile) return perAccount
        }
        val global = File(dir, "napcat.json")
        return if (global.isFile) global else null
    }

    /** 读取当前状态；配置文件不存在返回 null（还没装运行时/没登录过）。 */
    fun read(runtimeDir: File, uin: String): State? {
        val file = locate(runtimeDir, uin) ?: return null
        val root = runCatching {
            Json.parseToJsonElement(file.readText()) as JsonObject
        }.getOrNull() ?: return State(file.absolutePath, true, -1, 0)

        val o3 = (root["o3HookMode"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
        val bypass = root["bypass"] as? JsonObject
        val on = BYPASS_KEYS.count { key ->
            (bypass?.get(key) as? JsonPrimitive)?.booleanOrNull == true
        }
        return State(file.absolutePath, true, o3, on)
    }

    /**
     * 写入加固/还原配置。
     *
     * 只改这两个字段，其余键原样保留（NapCat 的配置schema有默认值，
     * 但用户的 ConsoleLog、packetBackend 等设置不能被我们抹掉）。
     */
    fun setHardened(runtimeDir: File, uin: String, enabled: Boolean): Result<State> = runCatching {
        val file = locate(runtimeDir, uin)
            ?: error("未找到 NapCat 配置：请先安装 QQ 运行时并至少登录一次")

        val original = runCatching { file.readText() }.getOrDefault("")
        val root = runCatching { Json.parseToJsonElement(original) as JsonObject }
            .getOrNull()
            ?: error("配置文件不是合法 JSON，已放弃改写：${file.name}")

        val map = root.toMutableMap()
        map["o3HookMode"] = JsonPrimitive(if (enabled) 0 else 1)
        val bypass = (root["bypass"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        BYPASS_KEYS.forEach { key -> bypass[key] = JsonPrimitive(enabled) }
        map["bypass"] = JsonObject(bypass)

        // 首次改写前留一份原文，便于人工对照或彻底回滚
        val backup = File(file.parentFile, file.name + ".bak")
        if (!backup.exists() && original.isNotBlank()) {
            runCatching { backup.writeText(original) }
        }

        file.writeText(writer.encodeToString(JsonObject.serializer(), JsonObject(map)))
        read(runtimeDir, uin) ?: error("写入成功但回读失败")
    }
}
