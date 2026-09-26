// 根构建脚本：只声明插件版本，不在这里写任何业务逻辑（Gradle 官方推荐的约定）
plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.2.21：LangChain4j 1.19.x / OkHttp 5.3.2 是用新版 Kotlin 编译的，
    // 元数据版本太新 —— Kotlin 2.0.20 的编译器读不懂，会在 K2 分析阶段直接
    // 内部崩溃（FileAnalysisException: source must not be null，报错位置随机
    // 指向某个无辜文件）。2.2.21 可读 ≤2.3 的元数据，覆盖当前所有依赖。
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 版本走，必须加这个插件（版本必须和上面完全一致）
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    // JSON 序列化（比手动拼 JSONObject 安全得多，编译期就能查出字段错误）
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
}
