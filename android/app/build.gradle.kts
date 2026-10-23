plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.util.Properties

/**
 * 把工作区级的容器安装脚本同步进 assets，作为 APK 内的唯一副本。
 *
 * 单一真源：<workspace>/tools/runtime/install-qq.sh
 * 复制时强制 LF：Windows 上编辑会带 CRLF，容器内 bash 会报
 * "$'\r': command not found"（见 HANDOVER 问题 C1）。App 侧投放脚本时还会再归一化一次。
 */
abstract class SyncContainerScript : DefaultTask() {

    @get:InputFile
    abstract val scriptFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val source = scriptFile.get().asFile
        if (!source.isFile) {
            throw GradleException(
                "找不到容器安装脚本：${source.absolutePath}\n" +
                    "android/ 工程必须位于工作区内（与 tools/ 同级）才能构建。"
            )
        }
        val target = outputDir.get().asFile.resolve("container").apply { mkdirs() }
        val text = source.readText(Charsets.UTF_8).replace("\r\n", "\n")
        target.resolve("install-qq.sh").writeText(text, Charsets.UTF_8)
    }
}

// :app —— 应用壳：Application 入口、AndroidManifest、assets（rootfs / NapCat / 容器脚本）、
// jniLibs（proot 系列）、APK 打包配置。业务代码全部在下面的库里。

// 正式签名属性：密钥与密码放在工作区 keystore/（已 gitignore）。
// 文件不存在时 release 退化为未签名构建（CI 上只出 debug 包，不受影响）。
// 注意：KTS 里 Gradle 的 `java` 扩展访问器会遮蔽 java.* 包名，须经 import 引入 Properties。
val signingProps = Properties().apply {
    val f = rootProject.file("../keystore/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.jiuguan.ailink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jiuguan.ailink"
        minSdk = 24
        targetSdk = 34
        versionCode = 24
        versionName = "1.1.4"
    }

    // 正式签名：见文件顶部 signingProps 的说明
    signingConfigs {
        create("release") {
            if (signingProps.isNotEmpty()) {
                storeFile = rootProject.file("../keystore/${signingProps.getProperty("storeFile")}")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 不加 .debug 包名后缀：真机复现需要能直接覆盖已安装的正式版。
            // 后缀会让包名变成 ...ailink.debug，系统视为另一个应用 —— 覆盖安装失败，
            // 且已装好的 350 MB 容器运行时（/data/data/<pkg>/files/runtime）用不上，只能重装。
            // 包名一致后 `adb install -r` 会原地升级并保留全部数据。
            // 同理必须沿用正式签名，否则 INSTALL_FAILED_UPDATE_INCOMPATIBLE（签名冲突）。
            versionNameSuffix = "-debug"
            if (signingProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
        }
        // 必须解压到 nativeLibraryDir：Android 10+ 只允许执行 APK 内嵌的二进制，
        // 解压后的 lib*.so 是唯一可 execve 的落地点。
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
    }

    sourceSets {
        getByName("main") {
            // 容器安装脚本由 syncContainerScript 任务生成（单一真源在 tools/runtime/）
            assets.srcDir(layout.buildDirectory.dir("generated/containerAssets"))
        }
    }
}

// 容器安装脚本：从工作区 tools/ 同步到构建目录，再作为 asset 打包
val syncContainerScript = tasks.register<SyncContainerScript>("syncContainerScript") {
    scriptFile.set(rootProject.layout.projectDirectory.file("../tools/runtime/install-qq.sh"))
    outputDir.set(layout.buildDirectory.dir("generated/containerAssets"))
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncContainerScript) }

dependencies {
    // 依赖方向（单向）：:app -> :ui -> :assembly -> { :data, :engine } -> :core
    implementation(project(":ui"))
    implementation(project(":assembly"))

    // Manifest 中的 FileProvider 来自 androidx.core
    implementation(libs.androidx.core.ktx)

    // 应用壳自身不写业务代码，因此不直接引入 compose / room / okhttp，
    // 这些依赖由各自的模块携带，避免版本在两个地方漂移。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
