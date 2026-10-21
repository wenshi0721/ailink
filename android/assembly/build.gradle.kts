plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// :assembly —— 依赖装配层：AppContainer 与 AppHost。
//
// 为什么把装配单独成模块：AppContainer 同时需要仓库（:data）与 BotManager（:engine），
// 而 :ui 的每个 ViewModel 构造参数就是 AppContainer。
// 若把它放进 :app，:ui 就必须反向依赖 :app；放进 :assembly 则依赖链保持单向：
//   :app -> :ui -> :assembly -> { :data, :engine } -> :core
android {
    namespace = "com.jiuguan.ailink.assembly"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 用 api：装配层对上层暴露的容器成员本身就是 :data / :engine 的类型，
    // :ui 取到 AppContainer 后要能直接使用这些类型。
    api(project(":core"))
    api(project(":data"))
    api(project(":engine"))

    implementation(libs.kotlinx.coroutines.android)
}
