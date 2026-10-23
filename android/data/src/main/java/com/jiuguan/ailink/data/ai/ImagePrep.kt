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
import java.util.concurrent.TimeUnit

/**
 * 把聊天里的图片/表情包转成模型能吃的 data URL。
 *
 * 桥接端下发的可能是 PNG / JPEG / GIF / WebP：动图只取首帧（BitmapFactory 的默认行为），
 * 带透明通道的贴图先铺白底再压 JPEG，否则透明区域会变成黑块。
 */
class ImagePrep {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 下载 + 转码；任何环节失败返回 null，由调用方降级为纯文本。 */
    suspend fun toDataUrl(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = download(url)
            val bitmap = decode(bytes)
            val scaled = scaleDown(bitmap)
            val jpeg = flattenToJpeg(scaled)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        }.getOrElse { e ->
            SessionLog.add(
                "vision",
                "图片转码失败：${e.message ?: e.javaClass.simpleName}（${url.take(80)}）"
            )
            null
        }
    }

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
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
