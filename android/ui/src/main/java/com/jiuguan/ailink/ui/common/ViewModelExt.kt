package com.jiuguan.ailink.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiuguan.ailink.assembly.AppContainer
import com.jiuguan.ailink.assembly.AppHost

/**
 * 统一的 ViewModel 构造入口：所有 ViewModel 只依赖 [AppContainer]，
 * 由该函数注入，避免在界面层散落工厂代码。
 */
@Composable
inline fun <reified T : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> T
): T {
    val context = LocalContext.current
    val container = (context.applicationContext as AppHost).container
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer<T> { create(container) }
        }
    )
}
