package com.workbuddy.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.workbuddy.assistant.ui.ChatScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动即打日志：用于确认部署的是最新构建（排查"日志消失"问题）
        android.util.Log.i("SSE_CHAT", "App 启动, 构建时间=${BuildConfig.BUILD_TYPE}")
        setContent {
            // Compose 应用没有 xml 布局，setContent 里直接写 UI
            ChatScreen()
        }
    }
}
