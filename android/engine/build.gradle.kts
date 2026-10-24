plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// :engine —— 运行时层：机器人引擎、前台服务、proot 容器运行时。
// 自带通知资源（图标与通道文案），避免向下依赖 :app 的 R。
// 约束：可以依赖 :core / :data；不得依赖 :ui / :app。
android {
    namespace = "com.jiuguan.ailink.engine"
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
    api(project(":core"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // 读写 NapCat 的 napcat_<uin>.json（反检测开关），只用到 JSON 解析与序列化
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
