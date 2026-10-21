package com.jiuguan.ailink.engine.container

import android.system.Os
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 极简 zip 解包器（NapCat 包用）。
 *
 * 为什么不调用 unzip：在 proot 里 /usr/bin/unzip 的可见性不稳定（同一环境多次
 * 执行结果不同），而 zip 解析本身不需要外部工具。用 Kotlin 直接解，行为可控。
 */
object ZipExtractor {

    data class Stats(val files: Int, val dirs: Int, val bytes: Long)

    fun extract(archive: InputStream, targetDir: File): Stats {
        var files = 0
        var dirs = 0
        var bytes = 0L
        targetDir.mkdirs()
        val rootPath = targetDir.canonicalPath

        ZipInputStream(archive.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.isEmpty()) continue

                val dest = File(targetDir, name)
                if (!dest.canonicalPath.startsWith(rootPath)) {
                    // 防目录穿越
                    zip.closeEntry()
                    continue
                }

                if (entry.isDirectory) {
                    dest.mkdirs()
                    dirs++
                } else {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            bytes += n
                        }
                    }
                    files++
                }

                // unix 模式（ZipEntry 不直接暴露，NapCat 包内无符号链接，这里只处理可执行位）
                zip.closeEntry()
            }
        }
        return Stats(files, dirs, bytes)
    }

    /** 解压并尽力给 shell 脚本补上可执行位。 */
    fun extractExecutable(archive: InputStream, targetDir: File): Stats {
        val stats = extract(archive, targetDir)
        targetDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".sh") || it.name == "napcat.mjs") }
            .forEach { file -> runCatching { file.setExecutable(true, false) } }
        return stats
    }
}
