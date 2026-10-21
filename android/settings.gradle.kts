// 国内网络下 Gradle 插件门户较慢，优先使用阿里云镜像；官方仓库作为兜底。
// GitHub Actions 等海外 CI 环境（环境变量 CI=true）直连官方仓库：
// 阿里云镜像在海外节点偶发 502，且一旦失败会污染整轮依赖解析
// （Gradle 报 "Repository has layout disabled due to earlier error"，官方仓库兜底失效）。
// 注意：pluginManagement 块先于脚本主体求值，条件须在各块内联表达，不能引用脚本级变量。

pluginManagement {
    repositories {
        if (System.getenv("CI") != null) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != null) {
            google()
            mavenCentral()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            google()
            mavenCentral()
        }
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
