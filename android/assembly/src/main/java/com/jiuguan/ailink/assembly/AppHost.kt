package com.jiuguan.ailink.assembly

/**
 * 依赖容器宿主，由 Application 实现。
 *
 * `:ui` 的每个 ViewModel 构造参数都是 [AppContainer]，但又不能反向依赖 `:app`，
 * 因此由本模块声明接口、`:app` 的 Application 实现它，界面层按接口取容器。
 */
interface AppHost {
    val container: AppContainer
}
