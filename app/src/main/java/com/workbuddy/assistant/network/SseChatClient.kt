package com.workbuddy.assistant.network

import com.workbuddy.assistant.BuildConfig
import com.workbuddy.assistant.model.ChatMessage
import com.workbuddy.assistant.model.ChatRequest
import com.workbuddy.assistant.model.StreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════════════════════════════
 * 【原理课 2】SSE（Server-Sent Events）—— 打字机效果的本质
 * ══════════════════════════════════════════════════════════════════
 *
 * 一次流式对话在网络上发生了什么：
 *
 *   App                          云端
 *   │  POST /chat/completions     │
 *   │  stream: true               │
 *   ├────────────────────────────▶│
 *   │◀── data: {"delta":"你"}─────│  ← 第 1 块
 *   │◀── data: {"delta":"好"}─────│  ← 第 2 块
 *   │◀── data: {"delta":"！"}─────│  ← 第 3 块 ... 每秒几十块
 *   │◀── data: [DONE] ───────────│  ← 结束标记
 *
 * SSE 的三条铁律（HTTP 层面）：
 * 1. 响应头 Content-Type: text/event-stream
 * 2. 连接不关闭，服务端可以一直往里"写行"
 * 3. 数据格式是文本行：以 "data: " 开头，一条消息一个换行
 *
 * 为什么用 SSE 而不是 WebSocket？
 *   - SSE 是单向的（服务端→客户端），对话场景"一问一答"刚好够用
 *   - SSE 就是一次普通 HTTP POST，过防火墙/网关/CDN 无障碍；WebSocket 需要协议升级
 *   - 自动重连、可以走 HTTP/2 多路复用 —— 对聊天 UI 完全够
 *   你手机上所有 AI App（豆包/Kimi/ChatGPT）的打字机效果全是 SSE。
 *
 * 为什么 OkHttp 可以直接读 SSE？
 *   OkHttp 的 response.body!!.source() 返回一个"阻塞式字节流读取器"，
 *   服务端每写一行，readLine() 就返回一行 —— 流式就自然而然发生了。
 */
class SseChatClient {

    private val json = Json { ignoreUnknownKeys = true } // 服务端字段比我们多，忽略不报错

    // readTimeout = 0：流式连接可能几十秒不断有数据，绝不能让 OkHttp 按"总时长"掐断
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 流式对话入口。
     *
     * @param onFirstToken  第一个 token 到达时回调（注意：不是响应开始！）
     *                      ── 这是 LLM 应用的核心性能指标 TTFT（Time To First Token）
     * @param onDelta       每收到一个增量片段就回调一次
     */
    suspend fun streamChat(
        history: List<ChatMessage>,
        onFirstToken: () -> Unit = {},
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer ${BuildConfig.DASHSCOPE_API_KEY}")
            .post(
                json.encodeToString(
                    ChatRequest.serializer(),
                    ChatRequest(model = MODEL, messages = history, stream = true)
                ).toRequestBody("application/json".toMediaType())
            )
            .build()

        var firstTokenAt = 0L
        var full = StringBuilder()

        // execute() 只等"响应头"返回；body 要一行行读 —— 这就是流的含义
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
            val source = response.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue      // 跳过空行/注释行
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break                // 流结束标记

                val chunk = json.decodeFromString(StreamResponse.serializer(), payload)
                val delta = chunk.choices.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) {
                    if (full.isEmpty()) {
                        firstTokenAt = System.currentTimeMillis()
                        onFirstToken()
                    }
                    full.append(delta)
                    onDelta(delta)   // ← 每次 UI 界面就"多打一个字"，打字机效果来源
                }
            }
        }
        full.toString()
    }

    companion object {
        // 通义千问的 OpenAI 兼容端点（国内直连、有免费额度）
        private const val BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1"
        private const val MODEL = "qwen-plus"

        /** 想换模型只改这两行：DeepSeek/豆包/Kimi 都是同样协议不同域名 */
        fun endpoints() = mapOf(
            "Qwen" to BASE_URL,
            "DeepSeek" to "https://api.deepseek.com/v1",
        )
    }
}
