// ArkReader —— 本地漫画阅读器（复刻 MH-ARK 核心能力）
// 依赖仓库：阿里云镜像优先，官方源兜底
pluginManagement {
    val onCi = System.getenv("CI") == "true"
    repositories {
        // CI（GitHub 美区）先走官方源，避免跨境线路抖动；本机（国内）阿里云镜像优先保速度
        if (onCi) {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        if (!onCi) {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val onCi = System.getenv("CI") == "true"
    repositories {
        if (onCi) {
            google()
            mavenCentral()
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        if (!onCi) {
            google()
            mavenCentral()
        }
    }
}
rootProject.name = "XY-READER"
include(":app")
