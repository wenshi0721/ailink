// 国内网络下 Gradle 插件门户较慢，优先使用阿里云镜像；官方仓库作为兜底。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "AiLink"

// 依赖方向（单向，禁止回边）：
//   :app -> :ui -> :assembly -> { :data, :engine } -> :core
include(":app")
include(":core")
include(":data")
include(":engine")
include(":assembly")
include(":ui")
