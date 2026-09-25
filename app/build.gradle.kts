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
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
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

    // W1 的两大主角：
    implementation("com.squareup.okhttp3:okhttp:4.12.0")                       // 网络与 SSE
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")    // 请求/响应模型
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")    // 协程

    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
