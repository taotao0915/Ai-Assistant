import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ── 教学点：API Key 的正确放法 ────────────────────────────────────────
// Key 放在根目录 local.properties（git 默认忽略，永不进版本库），
// 打包时注入 BuildConfig。注意：这仍不是最终方案 —— 反编译 APK 可以
// 看到 BuildConfig 字段，所以 W5-W6 我们会用 FastAPI 后端代理收敛 Key。
// 现在这样做是为了 W1-W2 本地开发方便，同时养成"Key 不进代码"的习惯。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.workbuddy.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.workbuddy.assistant"
        minSdk = 26          // 我们架构图的底线：Android 8.0+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // local.properties 里加一行：dashscope.apiKey=sk-xxxxxxxx
        buildConfigField(
            "String", "DASHSCOPE_API_KEY",
            "\"${localProps.getProperty("dashscope.apiKey", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // W3 接入 LangChain4j 时再开 R8 + keep 规则
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 【W3 新增】desugaring：LangChain4j 按 Java 17 标准库编译，
        // 用到了 java.time/java.util.stream 等在低 API Level 不全的 API。
        // 开启后编译期把这些 API "脱糖"成 Android 可用的形式。
        // 这是 LangChain4j 上 Android 的第一道必答题。
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ── 【W3 排障】打包资源去重 ────────────────────────────────────────
    // 报错长相：mergeDebugJavaResource 失败
    //   "2 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF'"
    // 根因：多个 jar（okhttp-jvm 5.3.2、jspecify 等）都带了 OSGi 的清单文件，
    // 它们是给 JVM OSGi 容器用的元数据，App 打包时毫无用处——
    // 但 Android 打包器默认"遇到重复资源就报错"，所以必须显式排除。
    // 记住套路：以后凡是 "Duplicate file / 2 files found with path 'META-INF/…'"
    // 一律往 packaging { resources.excludes } 里加对应路径即可。
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/**",
                "META-INF/native-image/**",      // GraalVM native-image 元数据，App 用不上
                "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES", "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/*.md",                 // README 等文档类资源
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    // Compose BOM：统一管理所有 Compose 库版本，防止版本错配
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // W1 的两大主角（W3 起转为"参考实现"，代码保留供对照学习）：
    implementation("com.squareup.okhttp3:okhttp:4.12.0")                        // 网络与 SSE（含 W3 自写适配器）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")    // 请求/响应模型
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")    // 协程

    // ── 【W3】LangChain4j ─────────────────────────────────────────────
    // ⚠️ 版本考古：为什么锁在 1.0.0（这段是五轮真机踩坑的结晶，面试现成素材）
    //
    // 坑 1（≥1.0.0-beta2）：OpenAI 模块的可插拔 HTTP 抽象默认实现是
    //   JDK HttpClient——java.net.http.HttpClient 在 Android 不存在，
    //   构建模型即崩 NoClassDefFoundError（官方 issue #2836）。
    // 坑 2（1.1.0 起逐步恶化）：Java 9 API ServiceLoader.findFirst() 混进
    //   AiServices 必经之路——Android 的 ServiceLoader 没有这个方法，
    //   脱糖库也不支持 java.util.ServiceLoader。三次真机崩、三个调用点：
    //     1.6.0+  可观测性模块 AiServiceListenerRegistrar.newInstance()
    //     1.1.0+  护栏模块 GuardrailService.builder() 挂进 AiServiceContext 构造
    //   AiServices 路径干净的最后一个版本 = 1.0.0。
    // 坑 3：官方 OkHttp 适配 langchain4j-http-client-okhttp 从 1.13.0-beta23
    //   才开始提供——和坑 2 的版本窗口（1.1~1.13）对不上，两头堵。
    //
    // ✅ 解法：锁定 1.0.0 + exclude 默认 JDK 客户端，用自写 OkHttp 适配器
    //   （agent/OkHttpHttpClient.kt）显式注入。
    // 【验证方法学】jar 是 deflate 压缩的，直接 grep jar 二进制搜不到字符串
    //   （第一版验证就是这么翻车的：误判 1.5.0 干净，真机照崩）。
    //   正确姿势：解压 jar → javap -c 逐类反编译 → 精确匹配
    //   "java/util/ServiceLoader.findFirst" 调用。1.0.0 四件套
    //   （langchain4j / core / open-ai / http-client）实测零调用。
    //   API 兼容性也已逐个 javap 核对：HttpClient SPI 签名、
    //   httpClientBuilder()、TokenStream 三回调 + start()、
    //   chatMemoryProvider、MessageWindowChatMemory.withMaxMessages 全部一致。
    implementation("dev.langchain4j:langchain4j:1.0.0")                  // 核心：AiServices/ChatMemory/TokenStream
    implementation("dev.langchain4j:langchain4j-open-ai:1.0.0") {        // OpenAI 兼容协议实现
        // 默认 JDK HttpClient 实现直接排除：Android 上没有也不需要它
        exclude(group = "dev.langchain4j", module = "langchain4j-http-client-jdk")
    }

    // desugaring 的运行时库（配合 compileOptions 里的开关）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
