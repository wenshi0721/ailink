package com.jiuguan.ailink.engine.container

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 极简 tar.gz 解包器。
 *
 * 为什么不用现成工具：Android 没有 tar 命令（toybox 在 /system/bin 但没有 tar applet），
 * 而 busybox 的 sh 在本机 Android 16 上会被 seccomp 终止。用纯 Kotlin 解析 tar 头，
 * 只依赖 GZIPInputStream，可移植且可测。
 *
 * 支持：普通文件、目录、符号链接、硬链接。跳过设备节点/管道（由 proot 绑定 /dev 提供）。
 */
object TarGzExtractor {

    private const val BLOCK = 512

    data class Stats(val files: Int, val dirs: Int, val links: Int, val bytes: Long)

    fun extract(archive: InputStream, targetDir: File): Stats {
        var files = 0
        var dirs = 0
        var links = 0
        var bytes = 0L

        targetDir.mkdirs()
        val rootPath = targetDir.canonicalPath

        // AAPT 打包时可能把 .tar.gz 解成裸 .tar（实测出现过），这里按魔数判断
        val buffered = BufferedInputStream(archive, 64 * 1024)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val stream: InputStream =
            if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered

        stream.use { gz ->
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break

                val name = readString(header, 0, 100)
                if (name.isEmpty()) break

                val mode = readOctal(header, 100, 8)
                val size = readOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val linkName = readString(header, 157, 100)
                val prefix = readString(header, 345, 155)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"

                val dest = File(targetDir, path)
                if (!dest.canonicalPath.startsWith(rootPath)) {
                    // 防目录穿越
                    skip(gz, size.toLong())
                    continue
                }

                when (type) {
                    '5' -> {
                        dest.mkdirs()
                        dirs++
                    }

                    '2' -> {
                        dest.parentFile?.mkdirs()
                        runCatching {
                            if (dest.exists()) dest.delete()
                            Os.symlink(linkName, dest.absolutePath)
                            links++
                        }
                    }

                    '1' -> {
                        dest.parentFile?.mkdirs()
                        val src = File(targetDir, linkName)
                        runCatching {
                            if (!dest.exists()) Os.link(src.absolutePath, dest.absolutePath)
                            links++
                        }
                    }

                    '0', '\u0000', '7' -> {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            copy(gz, out, size)
                        }
                        if (mode and 0b001_001_001 != 0) {
                            dest.setExecutable(true, false)
                        }
                        dest.setReadable(true, false)
                        files++
                        bytes += size
                    }

                    else -> skip(gz, size.toLong())
                }

                // 数据区按 512 对齐
                val padding = (BLOCK - (size % BLOCK)) % BLOCK
                if (padding > 0) skip(gz, padding.toLong())
            }
        }

        return Stats(files, dirs, links, bytes)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Int {
        var value = 0
        for (i in offset until offset + length) {
            val c = header[i].toInt() and 0xFF
            if (c == 0 || c == ' '.code) continue
            if (c < '0'.code || c > '7'.code) continue
            value = value * 8 + (c - '0'.code)
        }
        return value
    }

    private fun copy(input: InputStream, output: FileOutputStream, size: Int) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size.toLong()
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) break
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }
}
