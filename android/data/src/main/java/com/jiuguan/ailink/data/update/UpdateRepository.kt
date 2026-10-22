package com.jiuguan.ailink.data.update

import android.content.Context
import com.jiuguan.ailink.core.AppVersion
import com.jiuguan.ailink.core.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 更新流程状态机：Idle → Checking → UpToDate/Available → Downloading → Downloaded / Failed。 */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState()
    data class Downloaded(val info: UpdateInfo, val file: File) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

/**
 * 应用内自更新的状态编排。
 *
 * - 检查：启动时静默调用（[maybeAutoCheck]，6 小时节流），设置页手动触发（[check]）；
 * - 下载：直连 GitHub Releases，可选镜像前缀（AppPrefs.updateMirrorBase，如 `https://gh-proxy.com/`）；
 * - 安装：回调 UI 层（文件经 FileProvider 提供给系统安装器）。
 */
class UpdateRepository(
    private val context: Context,
    private val prefs: AppPrefs,
    private val client: GithubUpdateClient = GithubUpdateClient()
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = AppVersion.name(context)

    /** 设置页手动检查。 */
    suspend fun check() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        _state.value = fetchAndDecide()
    }

    /** 启动时静默检查：距上次成功检查不足 6 小时则跳过。 */
    suspend fun maybeAutoCheck() {
        if (_state.value !is UpdateState.Idle) return
        val elapsed = System.currentTimeMillis() - prefs.lastUpdateCheck
        if (elapsed < AUTO_CHECK_INTERVAL_MS) return
        runCatching { fetchAndDecide() }
    }

    private suspend fun fetchAndDecide(): UpdateState =
        withContext(Dispatchers.IO) {
            client.fetchLatest().fold(
                onSuccess = { info ->
                    prefs.lastUpdateCheck = System.currentTimeMillis()
                    when {
                        info == null -> UpdateState.UpToDate // 仓库还没有任何 Release
                        GithubUpdateClient.compareVersions(info.version, currentVersion) > 0 ->
                            UpdateState.Available(info)
                        else -> UpdateState.UpToDate
                    }
                },
                onFailure = { e ->
                    UpdateState.Failed(e.message ?: "无法连接 GitHub")
                }
            )
        }

    /** 下载最新版本 APK（镜像前缀只作用于下载 URL，不影响 API 查询）。 */
    suspend fun download(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(info, 0)
        withContext(Dispatchers.IO) {
            try {
                val url = withMirrorPrefix(prefs.updateMirrorBase.value, info.apkUrl)
                val dest = apkFile()
                var lastReported = -1
                client.download(url, dest) { percent ->
                    // 回调在 IO 线程，只在整数百分比变化时推进状态
                    if (percent != lastReported) {
                        lastReported = percent
                        _state.value = UpdateState.Downloading(info, percent)
                    }
                }
                _state.value = UpdateState.Downloaded(info, dest)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "下载失败")
            }
        }
    }

    fun apkFile(): File = File(context.cacheDir, "update/ailink-update.apk")

    private fun withMirrorPrefix(mirrorBase: String, url: String): String {
        val base = mirrorBase.trim()
        if (base.isEmpty()) return url
        return base.trimEnd('/') + "/" + url.removePrefix("https://").removePrefix("http://")
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
