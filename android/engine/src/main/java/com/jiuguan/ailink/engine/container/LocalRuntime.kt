package com.jiuguan.ailink.engine.container

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.jiuguan.ailink.core.AppVersion

/**
 * 手机内置运行时的底座。
 *
 * Android 10（API 29）起，应用无法执行自身数据目录里的二进制（W^X 限制），
 * 唯一合法路径是执行 APK 内嵌、由系统解压出来的 nativeLibraryDir 下的 `lib*.so`。
 * 本类负责：定位这些二进制、探测可执行性、检查 rootfs 是否就绪。
 */
class LocalRuntime(private val context: Context) {

    companion object {
        /** 容器安装脚本版本：改脚本时同步 +1，便于日志里确认跑的是哪一版。 */
        const val SCRIPT_VERSION = "v7"

        /** 容器内 NapCat 的 OneBot 正向 WS 地址（与 install-qq.sh 写入的配置一致）。 */
        const val LOCAL_WS_HOST = "127.0.0.1"
        const val LOCAL_WS_PORT = 3001
        const val LOCAL_WS_TOKEN = "ailink-local"

        /** QQ stdout 日志的体积上限：超过后整轮丢弃，避免无限增长。 */
        private const val MAX_LAUNCH_LOG_BYTES = 2L * 1024L * 1024L
    }

    data class CheckItem(
        val label: String,
        val ok: Boolean,
        val detail: String
    )

    /** 执行自检的三种结果，区分「完全不能执行」与「能执行但二进制不兼容」。 */
    enum class ExecLevel { NONE, OK, INCOMPATIBLE, FAILED }

    data class Report(
        val abi: String,
        val androidRelease: String,
        val nativeDir: String,
        val items: List<CheckItem>,
        val execOutput: String = "",
        val execLevel: ExecLevel = ExecLevel.NONE
    )

    val nativeDir: File = File(context.applicationInfo.nativeLibraryDir)

    /** 运行时根目录：解压 rootfs、放配置，都在这里。 */
    val runtimeDir: File = File(context.filesDir, "runtime")

    fun binary(name: String): File? =
        File(nativeDir, name).takeIf { it.exists() }

    fun inspect(): Report {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val busybox = binary("libbusybox.so")
        val proot = binary("libproot.so")
        val rootfs = File(runtimeDir, "rootfs")
        // NapCat 现在注入在 QQ 的 app_launcher 下
        val napcat = File(runtimeDir, "rootfs/opt/QQ/resources/app/app_launcher/napcat")

        val items = listOf(
            CheckItem(
                label = "系统架构",
                ok = abi.startsWith("arm64"),
                detail = if (abi.startsWith("arm64")) {
                    "arm64，支持内置 QQ 运行时"
                } else {
                    "$abi —— 仅 arm64 设备可运行 QQ Linux 客户端，当前设备只能做链路自检"
                }
            ),
            CheckItem(
                label = "可执行入口（busybox）",
                ok = busybox != null,
                detail = busybox?.let { "${it.absolutePath}（${it.length() / 1024} KB）" }
                    ?: "未找到 libbusybox.so，检查 jniLibs 与 useLegacyPackaging"
            ),
            CheckItem(
                label = "容器引导（proot）",
                ok = proot != null,
                detail = proot?.let { "${it.absolutePath}（${it.length() / 1024} KB）" }
                    ?: "尚未打包，需要先编译 arm64 版 proot 并放入 jniLibs"
            ),
            CheckItem(
                label = "Linux rootfs",
                ok = rootfs.isDirectory,
                detail = if (rootfs.isDirectory) {
                    rootfs.absolutePath
                } else {
                    "未安装（预期目录 ${rootfs.absolutePath}）"
                }
            ),
            CheckItem(
                label = "NapCat 注入",
                ok = napcat.isDirectory,
                detail = if (napcat.isDirectory) {
                    "已注入到 QQ app_launcher"
                } else {
                    "未安装（点下方「安装 QQ 运行时」）"
                }
            )
        )

        return Report(
            abi = abi,
            androidRelease = Build.VERSION.RELEASE,
            nativeDir = nativeDir.absolutePath,
            items = items
        )
    }

    /**
     * 真正执行一次内嵌二进制，验证 execve 没有被 SELinux / seccomp 拦下。
     *
     * 注意：APK 里的文件必须叫 lib*.so，而 busybox 是按 argv[0] 的文件名分发 applet 的，
     * 直接用路径执行会报 "applet not found"。因此先在私有目录建一个名为 busybox 的
     * 符号链接指向 native 库，再由符号链接启动。
     */
    suspend fun selfTest(): Report = withContext(Dispatchers.IO) {
        val base = inspect()
        val proot = binary("libproot.so")
            ?: return@withContext base.copy(
                execOutput = "缺少 libproot.so，先运行 tools/runtime/prepare-proot.py",
                execLevel = ExecLevel.FAILED
            )

        // proot 不依赖 argv[0]，必须直接用 native 库路径启动：
        // 经符号链接启动时，链接器会以链接所在目录（私有目录）为基准找依赖而失败。
        val target = proot

        val result = runCatching {
            val process = ProcessBuilder(target.absolutePath, "--version")
                .redirectErrorStream(true)
                .apply {
                    // 同目录下的 libtalloc.so / libandroid-shmem.so 需要被找到
                    environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = process.waitFor()
            Triple(code, output, null as String?)
        }.getOrElse { error ->
            Triple(-1, "", error.message ?: error.javaClass.simpleName)
        }

        val (code, output, error) = result

        // 159 = 128 + SIGSYS：进程确实被创建并执行了，只是随后被 seccomp 终止。
        // "applet not found" 则说明二进制已运行到自己的分发逻辑。
        val level = when {
            error != null && code == -1 -> ExecLevel.FAILED
            code == 0 && output.contains("proot", ignoreCase = true) -> ExecLevel.OK
            code == 159 || output.contains("Bad system call", ignoreCase = true) ->
                ExecLevel.INCOMPATIBLE
            output.contains("applet not found", ignoreCase = true) -> ExecLevel.INCOMPATIBLE
            else -> ExecLevel.FAILED
        }

        val text = buildString {
            appendLine("执行目标：${target.absolutePath}")
            appendLine("启动方式：native 库路径直连（LD_LIBRARY_PATH 指向同目录）")
            appendLine("依赖：libtalloc.so / libandroid-shmem.so 应位于同目录")
            appendLine("退出码：$code")
            if (error != null) appendLine("异常：$error")
            if (output.isNotBlank()) {
                appendLine("输出：")
                appendLine(output.lineSequence().take(6).joinToString("\n"))
            }
        }
        base.copy(execOutput = text.trim(), execLevel = level)
    }

    /** 解压 rootfs 到私有目录。优先用 assets 内的同 ABI 包，其次用开发期推送的本地 tar。 */
    suspend fun installRootfs(onProgress: (String) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
                val dest = File(runtimeDir, "rootfs")
                if (dest.exists()) dest.deleteRecursively()
                dest.mkdirs()

                // AAPT 可能把 .tar.gz 改名为 .tar，两种都试
                val assetName = listOf("rootfs/$abi.rootfs", "rootfs/$abi.tar.gz", "rootfs/$abi.tar")
                    .firstOrNull { assetExists(it) }
                val stats = if (assetName != null) {
                    onProgress("从安装包解压 $abi 运行时…")
                    context.assets.open(assetName).use { TarGzExtractor.extract(it, dest) }
                } else {
                    val devTar = File(runtimeDir, "rootfs.tar.gz")
                    check(devTar.exists()) {
                        "未找到 rootfs：请把 $abi.tar.gz 放入 assets/rootfs/，或推送到 ${devTar.absolutePath}"
                    }
                    onProgress("解压本地 rootfs.tar.gz…")
                    devTar.inputStream().use { TarGzExtractor.extract(it, dest) }
                }

                val sh = File(dest, "bin/sh")
                check(sh.exists()) { "解压完成但缺少 /bin/sh，包可能不完整" }

                "解压完成：${stats.files} 个文件 / ${stats.dirs} 个目录 / ${stats.links} 个链接，" +
                    "共 ${stats.bytes / 1024 / 1024} MB"
            }
        }

    /** 进入容器执行一条命令，验证 proot 与 rootfs 的配合。 */
    suspend fun containerProbe(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val proot = binary("libproot.so") ?: error("缺少 libproot.so")
            val loader = binary("libproot-loader.so") ?: error("缺少 libproot-loader.so")
            val rootfs = File(runtimeDir, "rootfs")
            check(File(rootfs, "bin/sh").exists()) { "rootfs 未安装，请先点「安装最小运行时」" }

            val tmpDir = File(runtimeDir, "tmp").apply { mkdirs() }
        // 容器内的 /tmp：Android 会把 TMPDIR 指向 /data/user/0/<pkg>/cache，
        // 这个路径在 rootfs 内不存在，dpkg/mktemp 会直接失败（曾在真机导致 ca-certificates 配置失败）
        File(rootfs, "tmp").apply {
            if (!exists()) mkdirs()
            runCatching { setReadable(true, false); setWritable(true, false); setExecutable(true, false) }
        }

        // QQ 基于 Electron，启动时强制要求 /dev/shm 可写，否则直接 abort：
        //   FATAL: ... incorrect permissions on /dev/shm
        // 容器的 /dev 是绑定自 Android 的，里面没有 shm，因此另建目录再绑到 /dev/shm。
        val shmDir = File(runtimeDir, "shm").apply {
            if (!exists()) mkdirs()
            runCatching {
                setReadable(true, false); setWritable(true, false); setExecutable(true, false)
            }
        }

            val command = listOf(
                proot.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "--rootfs=${rootfs.absolutePath}",
                "--bind=/dev",
                "--bind=/proc",
                "--bind=/sys",
                "--cwd=/root",
                "/bin/sh",
                "-c",
                "uname -a; echo '---'; head -3 /etc/os-release; id"
            )

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    environment()["PROOT_LOADER"] = loader.absolutePath
                    environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                    environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
                    environment()["HOME"] = "/root"
                    environment()["PATH"] =
                        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                }
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = process.waitFor()
            "退出码：$code\n$output"
        }
    }

    // ---------------- QQ 运行时（容器内安装与启动） ----------------

    private fun newProotProcess(rootfs: File, inner: List<String>): Process {
        val proot = binary("libproot.so") ?: error("缺少 libproot.so")
        val loader = binary("libproot-loader.so") ?: error("缺少 libproot-loader.so")
        val tmpDir = File(runtimeDir, "tmp").apply { mkdirs() }
        // 容器内的 /tmp：Android 会把 TMPDIR 指向 /data/user/0/<pkg>/cache，
        // 这个路径在 rootfs 内不存在，dpkg/mktemp 会直接失败（曾在真机导致 ca-certificates 配置失败）
        File(rootfs, "tmp").apply {
            if (!exists()) mkdirs()
            runCatching { setReadable(true, false); setWritable(true, false); setExecutable(true, false) }
        }

        // QQ 基于 Electron，启动时强制要求 /dev/shm 可写，否则直接 abort：
        //   FATAL: ... incorrect permissions on /dev/shm
        // 容器的 /dev 是绑定自 Android 的，里面没有 shm，因此另建目录再绑到 /dev/shm。
        val shmDir = File(runtimeDir, "shm").apply {
            if (!exists()) mkdirs()
            runCatching {
                setReadable(true, false); setWritable(true, false); setExecutable(true, false)
            }
        }

        val command = mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "--rootfs=${rootfs.absolutePath}",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            // 必须放在 --bind=/dev 之后：后绑定的覆盖先绑定的
            "--bind=${shmDir.absolutePath}:/dev/shm",
            "--cwd=/root"
        )
        command.addAll(inner)

        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
                environment()["HOME"] = "/root"
                environment()["PATH"] =
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["TERM"] = "xterm-256color"
                // 覆盖 Android 的 TMPDIR，让容器用自己的 /tmp
                environment()["TMPDIR"] = "/tmp"
                environment()["TMP"] = "/tmp"
                environment()["TEMP"] = "/tmp"
                environment()["DEBIAN_FRONTEND"] = "noninteractive"
            }
            .start()
    }

    fun isQqRuntimeInstalled(): Boolean =
        File(runtimeDir, "rootfs/opt/ailink/.qq-installed").exists()

    fun qqRuntimeSizeBytes(): Long {
        fun sizeOf(dir: File): Long = dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        val qq = File(runtimeDir, "rootfs/opt/QQ")
        return if (qq.exists()) sizeOf(qq) else 0L
    }

    /**
     * 在容器内执行 QQ 安装脚本（下载 QQ + NapCat，约 350 MB）。
     * 逐行回调日志，便于界面实时展示。
     */
    suspend fun installQqRuntime(onLine: (String) -> Unit): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rootfs = File(runtimeDir, "rootfs")
                check(File(rootfs, "bin/bash").exists()) { "rootfs 未安装，请先点「安装 / 重装最小运行时」" }

                // 1) 把脚本放进容器
                val scriptDir = File(rootfs, "opt/ailink").apply { mkdirs() }
                val script = File(scriptDir, "install-qq.sh")
                // 统一成 LF：Windows 上编辑过的脚本会带 CRLF，bash 会报 "$'\r': command not found"
                val scriptText = context.assets.open("container/install-qq.sh")
                    .bufferedReader()
                    .use { it.readText() }
                    .replace("\r\n", "\n")
                script.writeText(scriptText)
                script.setExecutable(true, false)

                onLine("[app] 运行时脚本 $SCRIPT_VERSION · App ${AppVersion.label(context)}")

                // 2) 解压随包的 NapCat 到容器内的 /opt/ailink/napcat
                //    不依赖容器里的 unzip（proot 下该二进制可见性不稳定）
                val napcatDir = File(scriptDir, "napcat")
                runCatching {
                    if (napcatDir.exists()) napcatDir.deleteRecursively()
                    napcatDir.mkdirs()
                    val stats = context.assets.open("runtime/NapCat.Shell.zip").use { input ->
                        ZipExtractor.extractExecutable(input, napcatDir)
                    }
                    onLine("[app] NapCat 解压完成：${stats.files} 个文件，${stats.bytes / 1024 / 1024} MB")
                }.onFailure { error ->
                    onLine("[app] NapCat 解压失败：${error.message ?: "未知错误"}")
                }

                // 3) 容器内的 DNS（Ubuntu base 默认没有 resolv.conf，apt 会解析失败）
                File(rootfs, "etc").mkdirs()
                File(rootfs, "etc/resolv.conf").writeText(
                    "nameserver 223.5.5.5\nnameserver 1.1.1.1\nnameserver 8.8.8.8\n"
                )

                // 4) 执行（日志追加写入 install.log，保留历史，便于对比多次运行）
                val logFile = File(scriptDir, "install.log")
                val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                runCatching { logFile.appendText("\n===== 安装开始 $stamp =====\n") }

                val process = newProotProcess(rootfs, listOf("/bin/bash", "/opt/ailink/install-qq.sh"))
                val startedAt = System.currentTimeMillis()
                var lastOutput = startedAt

                coroutineScope {
                    // 心跳：apt 在 -qq 模式下无输出，界面会像卡死，这里每 15 秒提示一次
                    val heartbeat = launch {
                        while (true) {
                            delay(15_000)
                            if (System.currentTimeMillis() - lastOutput >= 15_000) {
                                val elapsed = (System.currentTimeMillis() - startedAt) / 1000
                                onLine("[app] 仍在进行…已耗时 ${elapsed}s（依赖安装阶段通常无输出）")
                            }
                        }
                    }
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (line.isNotBlank()) {
                                    lastOutput = System.currentTimeMillis()
                                    onLine(line)
                                    runCatching { logFile.appendText(line + "\n") }
                                }
                            }
                        }
                        val exitCode = process.waitFor()
                        onLine("[app] 安装脚本退出码：$exitCode")
                        return@coroutineScope exitCode
                    } finally {
                        heartbeat.cancel()
                    }
                }
            }
        }

    /**
     * 启动 QQ。
     *
     * [uin] 非空时带 `-q <uin>` 走快速登录：QQ 会用上次登录留下的本地凭据，
     * 免扫码。首次登录没有凭据，仍需扫码。
     */
    fun launchQq(uin: String = ""): Process {
        val rootfs = File(runtimeDir, "rootfs")
        check(File(rootfs, "opt/QQ/qq").exists()) { "QQ 未安装" }
        val quick = if (uin.isBlank()) "" else " -q $uin"
        return newProotProcess(
            rootfs,
            listOf(
                "/bin/bash", "-lc",
                "cd /opt/QQ && exec xvfb-run -a /opt/QQ/qq --no-sandbox$quick"
            )
        )
    }

    /** 读取容器内的安装日志（失败时用于诊断）。 */
    fun readInstallLog(maxChars: Int = 20_000): String {
        val file = File(runtimeDir, "rootfs/opt/ailink/install.log")
        if (!file.exists()) return ""
        val text = file.readText()
        return if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    private fun launchLogFile(): File =
        File(runtimeDir, "rootfs/opt/ailink/qq-launch.log")

    fun resetLaunchLog() {
        // 不清空，只做体积上限 + 分隔：之前每次启动 QQ 都覆盖，"复现完再导出"时
        // 上一轮的 stdout 已经没了，而图片/掉线这类问题恰恰要靠它定位。
        runCatching {
            val file = launchLogFile()
            if (file.exists() && file.length() > MAX_LAUNCH_LOG_BYTES) file.writeText("")
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("\n===== 新一轮启动 $stamp =====\n")
        }
    }

    fun appendLaunchLog(line: String) {
        runCatching { launchLogFile().appendText(line + "\n") }
    }

    fun readLaunchLog(): String =
        launchLogFile().takeIf { it.exists() }?.readText().orEmpty()

    /** 完整日志（导出用，不截断）。 */
    fun readInstallLogFull(): String {
        val file = File(runtimeDir, "rootfs/opt/ailink/install.log")
        return if (file.exists()) file.readText() else ""
    }

    /** 读取 NapCat 写出的登录二维码。 */
    fun readQrCode(): ByteArray? {
        val file = File(
            runtimeDir,
            "rootfs/opt/QQ/resources/app/app_launcher/napcat/cache/qrcode.png"
        )
        return if (file.exists() && file.length() > 0) file.readBytes() else null
    }

    private fun assetExists(path: String): Boolean =
        runCatching { context.assets.open(path).close() }.isSuccess

    /** 在私有目录建 busybox -> native 库 的符号链接。 */
    private fun prepareLink(target: File, name: String): File {
        val binDir = File(runtimeDir, "bin").apply { mkdirs() }
        val link = File(binDir, name)
        runCatching { link.delete() }
        Os.symlink(target.absolutePath, link.absolutePath)
        return link
    }
}
