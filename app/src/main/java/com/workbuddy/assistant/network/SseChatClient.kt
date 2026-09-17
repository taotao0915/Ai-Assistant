package com.workbuddy.assistant.network

import android.util.Log
import com.workbuddy.assistant.BuildConfig
import com.workbuddy.assistant.model.ChatMessage
import com.workbuddy.assistant.model.ChatRequest
import com.workbuddy.assistant.model.NonStreamResponse
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

    // 【坑位预警】encodeDefaults = true 是必须的！
    // kotlinx.serialization 默认【省略等于默认值的字段】——
    // ChatRequest.stream = true 是默认值，不加这项的话请求体里根本没有 "stream":true，
    // 服务器就按非流式返回完整 JSON（打字机消失，等 3-5 秒一次性出全文）。
    // 这个 bug 曾潜伏了整个 W1，靠"没有 Content-Type: text/event-stream"定位到。
    private val json = Json {
        ignoreUnknownKeys = true   // 服务端字段比客户端模型多，忽略不报错
        encodeDefaults = true      // 序列化时带上默认值字段（stream/temperature）
    }

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
        // ── 诊断 1：Key 为空 = local.properties 没写或改完没重新 Build ──
        // BuildConfig 字段是编译期注入的：先 sync 后改 local.properties 不会生效，
        // 必须 Build > Rebuild Project 一次。
        if (BuildConfig.DASHSCOPE_API_KEY.isBlank()) {
            throw IllegalStateException(
                "API Key 为空：请在项目根目录 local.properties 加一行 " +
                "dashscope.apiKey=sk-xxx，然后 Build > Rebuild Project"
            )
        }
        Log.i(TAG, "请求发出: model=$MODEL, 历史消息数=${history.size}")

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer ${BuildConfig.DASHSCOPE_API_KEY}")
            // 显式声明期望流式 —— 部分网关/服务商要求带这个头才走 SSE，
            // 不带时即使 stream=true 也可能返回完整 JSON
            .header("Accept", "text/event-stream")
            .post(
                json.encodeToString(
                    ChatRequest.serializer(),
                    ChatRequest(model = MODEL, messages = history, stream = true)
                ).toRequestBody("application/json".toMediaType())
            )
            .build()

        var full = StringBuilder()

        // execute() 只等"响应头"返回；body 要一行行读 —— 这就是流的含义
        client.newCall(request).execute().use { response ->
            // ── 诊断 2：把 HTTP 状态码打出来（401=Key 错，403=没开通模型服务，
            //    429=限流，超时=网络/代理问题）──
            val contentType = response.header("Content-Type") ?: ""
            Log.i(TAG, "响应到达: HTTP ${response.code}, Content-Type=$contentType")
            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(300) ?: "(无响应体)"
                Log.e(TAG, "请求失败: HTTP ${response.code}, body=$errBody")
                throw RuntimeException("HTTP ${response.code}: $errBody")
            }

            // ══ 兜底：服务器没走流式（Content-Type 不是 event-stream）══
            // 把整个 JSON 体读出来：可能是一次性完整回答，也可能是 200 包装的错误
            if (!contentType.contains("event-stream", ignoreCase = true)) {
                val bodyStr = response.body?.string() ?: ""
                Log.w(TAG, "非流式响应(长度=${bodyStr.length}): ${bodyStr.take(1000)}")
                val resp = runCatching {
                    json.decodeFromString(NonStreamResponse.serializer(), bodyStr)
                }.getOrNull()
                resp?.error?.let {
                    throw RuntimeException("服务端错误 ${it.code}: ${it.message}")
                }
                val content = resp?.choices?.firstOrNull()?.message?.content
                if (!content.isNullOrEmpty()) {
                    onFirstToken()
                    onDelta(content)   // 一次性整段上屏（无打字机，但功能可用）
                    return@withContext content
                }
                throw RuntimeException("非流式响应但无法解析内容: ${bodyStr.take(300)}")
            }

            val source = response.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue      // 跳过空行/注释行
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") {                    // 流结束标记
                    Log.i(TAG, "流正常结束, 共 ${full.length} 字符")
                    break
                }

                val chunk = json.decodeFromString(StreamResponse.serializer(), payload)
                val delta = chunk.choices.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) {
                    if (full.isEmpty()) {
                        Log.i(TAG, "首个 token 到达")          // 对照 Logcat 时间戳看 TTFT
                        onFirstToken()
                    }
                    full.append(delta)
                    onDelta(delta)   // ← 每次 UI 界面就"多打一个字"，打字机效果来源
                }
            }
        }
        if (full.isEmpty()) {
            // ── 诊断 3：连接成功但一个 token 都没拿到（协议变了/被网关拦截）──
            throw RuntimeException("连接成功但未收到任何内容，请把 Logcat 中 SSE_CHAT 标签的日志发给我")
        }
        full.toString()
    }

    companion object {
        private const val TAG = "SSE_CHAT"

        // 通义千问的 OpenAI 兼容端点（国内直连、有免费额度）
        private const val BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1"
        private const val MODEL = "qwen-plus"
    }
}
