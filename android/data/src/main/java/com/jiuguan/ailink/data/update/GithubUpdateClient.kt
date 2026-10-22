package com.jiuguan.ailink.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 最新版本信息（来自 GitHub Releases）。 */
data class UpdateInfo(
    val tagName: String,
    val version: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long
)

/**
 * GitHub Releases 更新客户端。
 *
 * 只做纯网络与解析：状态编排在 [UpdateRepository]。
 * 仓库地址为本项目自身（wenshi0721/ailink），版本以 tag 为准（v1.2.3 → 1.2.3）。
 */
class GithubUpdateClient(private val client: OkHttpClient = defaultClient()) {

    /** 查询最新 Release。无任何 Release 时返回 null（API 404）。 */
    suspend fun fetchLatest(): Result<UpdateInfo?> = runCatching {
        val request = Request.Builder()
            .url("$API_BASE/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AiLink-Updater")
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null // 仓库还没有任何 Release
                !response.isSuccessful -> error("GitHub API 返回 ${response.code}")
                else -> parseRelease(response.body?.string().orEmpty())
                    ?: error("Release 中没有找到 APK 附件")
            }
        }
    }

    /** 下载 APK 到目标文件，[onProgress] 回调 0..100。 */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".part")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AiLink-Updater")
            .build()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
            val body = response.body ?: error("下载失败：响应为空")
            val total = body.contentLength()
            var received = 0L
            var lastPercent = -1

            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            error("下载已取消")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        received += read
                        if (total > 0) {
                            val percent = (received * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            check(total <= 0 || received == total) { "下载不完整：$received / $total" }
        }

        if (dest.exists()) dest.delete()
        check(temp.renameTo(dest)) { "无法保存安装包" }
    }

    companion object {
        const val REPO = "wenshi0721/ailink"
        const val API_BASE = "https://api.github.com"
        private const val BUFFER_SIZE = 64 * 1024

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * 版本号比较：按「.」分段取整数，缺失段按 0 处理；忽略前缀 v/V 与空白。
         * 返回值与 Comparable 约定一致：负数 = a < b，0 = 相等，正数 = a > b。
         */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.trim().trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.trim().trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x - y
            }
            return 0
        }

        /** 解析 releases/latest 响应；挑选第一个 .apk 附件（有非 debug 附件时优先）。 */
        internal fun parseRelease(body: String): UpdateInfo? {
            if (body.isBlank()) return null
            val root = runCatching {
                Json.parseToJsonElement(body).jsonObject
            }.getOrNull() ?: return null

            val tag = root.str("tag_name")
            if (tag.isBlank()) return null

            val assets = root["assets"]?.jsonArray ?: return null
            val apks = assets
                .mapNotNull { (it as? JsonObject) }
                .filter { it.str("name").endsWith(".apk", ignoreCase = true) }
            if (apks.isEmpty()) return null

            val apk = apks.firstOrNull { !it.str("name").contains("debug", ignoreCase = true) }
                ?: apks.first()

            return UpdateInfo(
                tagName = tag,
                version = tag.trim().trimStart('v', 'V'),
                title = root.str("name").ifBlank { tag },
                notes = root.str("body"),
                apkUrl = apk.str("browser_download_url"),
                apkSizeBytes = apk.str("size").toLongOrNull() ?: 0L
            )
        }

        private fun JsonObject.str(key: String): String =
            runCatching { this[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()
    }
}
