package com.jiuguan.ailink.core

import android.content.Context

/**
 * 应用版本信息。
 *
 * 运行期从 PackageManager 读取，而不是引用 `BuildConfig`：
 * 模块化之后 `com.jiuguan.ailink.BuildConfig` 只存在于 `:app`，
 * 而 `:engine`（安装日志）与 `:ui`（设置页、运行时页）都需要显示版本号，
 * 直接引用会让它们反向依赖 `:app`。
 */
object AppVersion {

    fun name(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

    @Suppress("DEPRECATION")
    fun code(context: Context): Int =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrNull() ?: 0

    /** 形如 `1.1.0(20)`，用于界面与安装日志的版本标识。 */
    fun label(context: Context): String = "${name(context)}(${code(context)})"
}
