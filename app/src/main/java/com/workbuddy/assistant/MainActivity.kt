package com.workbuddy.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.workbuddy.assistant.ui.ChatScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Compose 应用没有 xml 布局，setContent 里直接写 UI
            ChatScreen()
        }
    }
}
