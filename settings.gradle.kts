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

// —— 可选外部源模块 ——
// 主仓不含任何具体来源实现；若 local.properties 里配了 optionalSourcesDir（本机路径，不入库），
// 且该目录是个 Gradle 模块，就把它编进构建；未配置时主仓照常构建。
val optionalProps = java.util.Properties().apply {
    val file = java.io.File(settingsDir, "local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val optionalSourcesDir = optionalProps.getProperty("optionalSourcesDir")
    ?: System.getenv("XY_OPTIONAL_SOURCES_DIR")
if (!optionalSourcesDir.isNullOrBlank()) {
    val moduleDir = java.io.File(optionalSourcesDir)
    if (java.io.File(moduleDir, "build.gradle.kts").isFile) {
        include(":optional-source")
        project(":optional-source").projectDir = moduleDir
        println("[XY-READER] 可选外部源模块已编入：$moduleDir")
    } else {
        println("[XY-READER] optionalSourcesDir 指向的目录不是 Gradle 模块，已忽略：$moduleDir")
    }
}
