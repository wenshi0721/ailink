package com.jiuguan.ailink.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.jiuguan.ailink.core.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 把聊天里的图片/表情包转成模型能吃的 data URL。
 *
 * 桥接端下发的可能是 PNG / JPEG / GIF / WebP：动图只取首帧（BitmapFactory 的默认行为），
 * 带透明通道的贴图先铺白底再压 JPEG，否则透明区域会变成黑块。
 *
 * 第二个职责是**本地缓存**：QQ 图床地址带 `rkey` 鉴权、几十分钟到几小时就失效，
 * 只存 URL 等于"当下能用、下一轮就没了"。因此成功转码后把 JPEG 落到
 * `filesDir/imgcache/`，后续轮次直接读文件，不再依赖网络。
 *
 * @param cacheDir 缓存目录；传 null 表示只用内存（单测/无 Context 场景）。
 */
class ImagePrep(private val cacheDir: File? = null) {

    /** 一张已就绪的图片：可直接塞进请求的 data URL + 缓存文件名（供入库/回看）。 */
    data class Prepared(val dataUrl: String, val cache: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * 优先读本地缓存，其次下载转码并落盘。
     *
     * [file] 是桥接端的内部文件名，比 URL 稳定（URL 里的 rkey 会变），有它就优先用它做键。
     */
    suspend fun prepare(url: String, file: String = ""): Prepared? = withContext(Dispatchers.IO) {
        val key = cacheKey(url, file)
        readCache(key)?.let { return@withContext Prepared(it, cacheName(key)) }

        runCatching {
            val bytes = download(url)
            val bitmap = decode(bytes)
            val scaled = scaleDown(bitmap)
            val jpeg = flattenToJpeg(scaled)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            writeCache(key, jpeg)
            Prepared("data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP), cacheName(key))
        }.getOrElse { e ->
            SessionLog.add(
                "vision",
                "图片转码失败：${e.message ?: e.javaClass.simpleName}（${url.take(80)}）"
            )
            null
        }
    }

    /** 只从本地缓存取；命中返回 data URL。历史轮次与引用回复走这条路，不产生网络请求。 */
    suspend fun fromCache(cache: String): String? = withContext(Dispatchers.IO) {
        if (cache.isBlank()) return@withContext null
        val file = File(cacheDir ?: return@withContext null, cache)
        if (!file.isFile) return@withContext null
        runCatching {
            file.setLastModified(System.currentTimeMillis()) // LRU 用
            "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /** 下载 + 转码；任何环节失败返回 null，由调用方降级为纯文本。 */
    suspend fun toDataUrl(url: String): String? = prepare(url)?.dataUrl

    private fun cacheKey(url: String, file: String): String {
        val name = file.ifBlank { url }
        return sha1(name).take(32)
    }

    private fun cacheName(key: String): String = "$key.jpg"

    private fun readCache(key: String): String? {
        val dir = cacheDir ?: return null
        val file = File(dir, cacheName(key))
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            file.setLastModified(System.currentTimeMillis())
            "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun writeCache(key: String, jpeg: ByteArray) {
        val dir = cacheDir ?: return
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            File(dir, cacheName(key)).writeBytes(jpeg)
        }
    }

    /** 缓存文件名 → 本地文件；不存在返回 null。供界面渲染缩略图。 */
    fun cacheFile(cache: String): File? {
        val dir = cacheDir ?: return null
        if (cache.isBlank()) return null
        return File(dir, cache).takeIf { it.isFile }
    }

    /**
     * LRU 清理：按文件数 + 总字节双上限裁剪。
     * 由 Application 启动时调用一次，避免长期使用后无限增长。
     */
    fun prune(maxFiles: Int = MAX_CACHE_FILES, maxBytes: Long = MAX_CACHE_BYTES) {
        val dir = cacheDir ?: return
        if (!dir.isDirectory) return
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
            var total = files.sumOf { it.length() }
            var count = files.size
            for (file in files) {
                if (count <= maxFiles && total <= maxBytes) break
                total -= file.length()
                count -= 1
                file.delete()
            }
        }
    }

    private fun sha1(text: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun download(url: String): ByteArray {
        require(url.startsWith("http")) { "图片地址不是 http(s)" }
        val request = Request.Builder()
            .url(url)
            // QQ 图床会校验 UA，用默认的 OkHttp UA 容易被拒
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/*,*/*;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败 HTTP ${response.code}")
            val body = response.body ?: error("响应为空")
            val declared = body.contentLength()
            if (declared > MAX_BYTES) error("图片过大 ${declared / 1024}KB")
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) error("图片过大 ${bytes.size / 1024}KB")
            return bytes
        }
    }

    /**
     * 先按尺寸降采样再解码：聊天图片偶有 4000px 以上的原图，
     * 直接整张解码在低端机上会 OOM。
     */
    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide / sample > MAX_EDGE * 2) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("无法解码图片（可能是加密或非常规格式）")
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= MAX_EDGE) return src
        val ratio = MAX_EDGE.toFloat() / maxSide
        val width = (src.width * ratio).toInt().coerceAtLeast(1)
        val height = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, width, height, true)
    }

    private fun flattenToJpeg(src: Bitmap): ByteArray {
        val canvasBitmap = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        val out = ByteArrayOutputStream()
        canvasBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        canvasBitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        /** 单边上限：1024 足够看清聊天图片内容，又能把单张图压到几十 KB。 */
        const val MAX_EDGE = 1024
        const val JPEG_QUALITY = 85
        const val MAX_BYTES = 5 * 1024 * 1024

        /** 缓存上限：200 张 / 40 MB，按最后访问时间 LRU 淘汰。 */
        const val MAX_CACHE_FILES = 200
        const val MAX_CACHE_BYTES = 40L * 1024L * 1024L

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
