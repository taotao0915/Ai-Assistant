package com.workbuddy.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.workbuddy.assistant.agent.ChatClient
import com.workbuddy.assistant.agent.LangChain4jChatClient
import com.workbuddy.assistant.model.UiMessage
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
 * 【W3 变化】"请求层记忆"交给了框架：
 *   W1 时这里每次 send 都要手拼 history（system + 全部历史 role/content）；
 *   W3 换成 LangChain4j 后，这段代码整个消失了 —— ChatMemory 在框架内部
 *   做着完全一样的事（模型依然无状态！只是拼历史的活儿换人干了）。
 *   对照 git 历史看这个文件的 diff，"框架帮你省了什么"一目了然。
 *   UI 层记忆（messages 列表）依然归我们管 —— 显示和协议是两回事。
 *
 * 【W4 变化】ViewModel → AndroidViewModel：
 *   工具（DeviceTools）要访问系统服务和 SharedPreferences，需要 Context。
 *   两不推荐：① 在 ViewModel 里持有 Activity 引用（内存泄漏）；
 *             ② 静态变量存 applicationContext（测试地狱、时序坑）。
 *   正确姿势：AndroidViewModel(application) —— 框架把 Application 塞给你，
 *   ViewModelProvider 反射创建时自动匹配 (Application) 构造函数。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // W1 的 SseChatClient.kt 保留在 network 包里作为"手写参考实现"，不再接入。
    // 想对比两种实现：把下面这行换回 SseChatClient 会编译失败（签名不同，
    // 它需要 history 参数）—— 这个编译错误本身就说明了 W1/W3 的职责差异。
    // W4：把 applicationContext 传给客户端 → 传给工具集（电量/备忘录要用）。
    private val chatClient: ChatClient = LangChain4jChatClient(application.applicationContext)

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

        // 2. 【W3 对比点】直接把用户这句话交给客户端 ——
        //    W1 里这里的 buildList { system + 全部历史 } 整块消失了，
        //    框架的 ChatMemory 会自动带上历史和 system prompt。
        viewModelScope.launch {
            val startAt = System.currentTimeMillis()
            try {
                chatClient.streamChat(
                    text,
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
}
