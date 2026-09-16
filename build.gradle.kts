// 根构建脚本：只声明插件版本，不在这里写任何业务逻辑（Gradle 官方推荐的约定）
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 版本走，必须加这个插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    // JSON 序列化（比手动拼 JSONObject 安全得多，编译期就能查出字段错误）
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
}
