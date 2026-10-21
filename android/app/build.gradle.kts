plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

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
android {
    namespace = "com.jiuguan.ailink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jiuguan.ailink"
        minSdk = 24
        targetSdk = 34
        versionCode = 19
        versionName = "1.0.18"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // 应用壳自身不写业务代码，因此不直接引入 compose / room / okhttp，
    // 这些依赖由各自的模块携带，避免版本在两个地方漂移。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
