pluginManagement {
    repositories {
        // ── 教学点：国内 Gradle 网络问题的标准解法 ──────────────────
        // 报 "Plugin not found in any of the following sources" 时，
        // 第一反应不是"版本不存在"，而是"仓库连接失败"（超时被 Gradle
        // 当成找不到）。阿里云镜像在国内稳定且快，官方仓库保留兜底。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "AIAssistant"
include(":app")
