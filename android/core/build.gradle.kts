plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // 回复行为配置（BehaviorConfig）以 JSON 存在偏好里，需要序列化
    alias(libs.plugins.kotlin.serialization)
}

// :core —— 最底层模块，只放被所有层共用的模型、偏好、日志、工具。
// 约束：不得依赖 :data / :engine / :ui / :app（否则分层立刻退化成环）。
android {
    namespace = "com.jiuguan.ailink.core"
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
    // AppPrefs / SessionLog / PairingBus 用 StateFlow + SharedFlow
    implementation(libs.kotlinx.coroutines.android)
    // BehaviorConfig 的 @Serializable
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
