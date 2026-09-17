package com.workbuddy.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workbuddy.assistant.model.ChatMessage
import com.workbuddy.assistant.model.UiMessage
import com.workbuddy.assistant.network.SseChatClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ══════════════════════════════════════════════════════════════════
 * 【原理课 3】MVVM + 单向数据流 —— "状态驱动 UI"
 * ══════════════════════════════════════════════════════════════════
 *
 * 数据流永远是这个方向（Compose 官方架构）：
 *
 *   UI 事件 ──▶ ViewModel 方法 ──▶ 修改 StateFlow ──▶ Compose 重组 ──▶ 新 UI
 *   (点击发送)    (send)            (messages.value=…)   (自动)
 *
 * 关键点：ChatScreen 里没有任何一行"手动刷新列表"的代码。
 * messages 每变一次，LazyColumn 自动重画 —— 这就是"声明式 UI"。
 * 每秒几十个 token = messages 每秒变几十次 = UI 每秒重画几十次，
 * Compose 的智能重组（只重画变化的那一条 item）让它毫不卡顿。
 *
 * 【面试考点】LLM 应用里的"记忆"分两层：
 *   - UI 层记忆：messages 列表，只负责显示（上限 200 条防内存爆）
 *   - 请求层记忆：每次发请求，把历史映射成 role/content 再发出去
 *   W3 接 LangChain4j 时，这层会被框架的 ChatMemory 接管，
 *   你就能对比"手写"和"框架"到底帮你省了什么。
 */
class ChatViewModel : ViewModel() {

    private val chatClient = SseChatClient()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** TTFT 埋点：首个 token 延迟（毫秒），UI 会显示出来 —— W2 要优化的指标 */
    private val _ttftMs = MutableStateFlow<Long?>(null)
    val ttftMs: StateFlow<Long?> = _ttftMs.asStateFlow()

    fun send(text: String) {
        android.util.Log.i("SSE_CHAT", "send() 被调用: \"$text\"")
        if (text.isBlank()) { android.util.Log.w("SSE_CHAT", "输入为空，忽略"); return }
        if (_isStreaming.value) { android.util.Log.w("SSE_CHAT", "上一条还在生成中，忽略本次点击"); return }

        // 1. 先把"用户消息"和一条空的"AI 占位消息"塞进列表
        _messages.value = _messages.value + UiMessage(isUser = true, content = text)
        _messages.value = _messages.value + UiMessage(isUser = false, content = "")
        _ttftMs.value = null
        _isStreaming.value = true

        // 2. 把历史（含刚发的这句）映射成协议消息 —— 注意看 system 消息怎么来的
        val history = buildList {
            add(ChatMessage("system", SYSTEM_PROMPT))
            _messages.value
                .filter { !it.isError && it.content.isNotBlank() }
                .forEach { add(ChatMessage(if (it.isUser) "user" else "assistant", it.content)) }
        }

        viewModelScope.launch {
            val startAt = System.currentTimeMillis()
            try {
                chatClient.streamChat(
                    history,
                    onFirstToken = { _ttftMs.value = System.currentTimeMillis() - startAt },
                    onDelta = { delta ->
                        // 3. 每个 token 到达：替换最后一条占位消息（内容追加）
                        val list = _messages.value.toMutableList()
                        if (list.isNotEmpty() && !list.last().isUser) {
                            list[list.size - 1] =
                                list.last().copy(content = list.last().content + delta)
                            _messages.value = list
                        }
                    }
                )
            } catch (e: Exception) {
                // 4. 断网/超时/Key 错误：把错误渲染成一条 AI 消息（别让用户面对崩溃）
                val list = _messages.value.toMutableList()
                if (list.isNotEmpty() && !list.last().isUser) {
                    list[list.size - 1] = list.last().copy(
                        content = "⚠️ 请求失败：${e.message}",
                        isError = true
                    )
                    _messages.value = list
                }
            } finally {
                _isStreaming.value = false
            }
        }
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "你是运行在 Android 手机上的 AI 助手，回答简洁、准确，默认使用中文。"
    }
}
