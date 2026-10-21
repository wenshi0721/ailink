plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// :data —— 数据层：Room 实体/DAO/数据库、仓库、OneBot 与演示网关、AI 客户端。
// 约束：可以依赖 :core；不得依赖 :engine / :ui / :app。
android {
    namespace = "com.jiuguan.ailink.data"
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

ksp {
    // 与单模块时期保持一致：schema 输出到模块内 schemas/（exportSchema = false 时仅作占位）
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    api(project(":core"))

    // api：AiLinkDatabase 继承 RoomDatabase，:engine/:app 需要解析该超类型
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // 纯函数解析逻辑（OneBot 消息段 / CQ 码）在此层做回归保护
    testImplementation(libs.junit)
}
