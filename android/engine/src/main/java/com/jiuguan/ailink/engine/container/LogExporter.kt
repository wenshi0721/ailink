package com.jiuguan.ailink.engine.container

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把安装日志导出成文件。
 *
 * 日志动辄上千行，复制粘贴会超出聊天工具的传输上限，导出成文件更实际：
 * Android 10+ 直接写进系统「下载」目录，用户可在文件管理里找到；
 * 低版本退化为应用外部目录。导出后同时提供系统分享。
 */
object LogExporter {

    data class Result(val uri: Uri?, val displayName: String, val location: String)

    fun export(context: Context, fileNamePrefix: String, content: String): Result {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "$fileNamePrefix-$stamp.log"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                runCatching {
                    resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    return Result(uri, fileName, "下载/$fileName")
                }
            }
        }

        // 兜底：应用外部目录（无需额外权限）
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, fileName)
        file.writeText(content)
        return Result(null, fileName, file.absolutePath)
    }

    fun share(context: Context, result: Result, subject: String) {
        val uri = result.uri
            ?: runCatching {
                // 低版本用 file:// 会触发 FileUriExposedException，这里只提示路径
                null
            }.getOrNull()
            ?: return

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "导出日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
