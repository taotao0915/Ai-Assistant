package com.workbuddy.assistant.agent

import android.util.Log
import com.workbuddy.assistant.BuildConfig
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.MemoryId
import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.TokenStream
import dev.langchain4j.service.UserMessage
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ══════════════════════════════════════════════════════════════════
 * 【原理课 4】LangChain4j AI Services —— 声明式 AI 接口
 * ══════════════════════════════════════════════════════════════════
 *
 * W1 我们手写了 200 行：JSON 模型、SSE 逐行解析、历史拼接、OkHttp 调用。
 * 现在全部交给框架，我们只剩两样东西：
 *
 * 1. 一个接口（下面的 Assistant）—— 用注解"声明"意图，框架生成实现
 * 2. 一个 AiServices 组装器 —— 把模型/记忆/接口拼在一起
 *
 * ── 框架到底帮你做了什么（对照 W1 代码）──────────────────────────
 *   W1 手写                          →  W3 框架接管
 *   ─────────────────────────────────────────────────────────────
 *   ChatModels.kt 全部协议模型        →  langchain4j 内部处理序列化
 *   SseChatClient 逐行读 data: 帧     →  langchain4j 的 SSE parser + 我们注入的 OkHttp 客户端
 *   onDelta 回调拼接                  →  TokenStream.onPartialResponse
 *   ViewModel 手拼 history + system   →  ChatMemory 自动维护
 *   OkHttp 超时/连接管理              →  OkHttpHttpClientBuilder（自写，见 OkHttpHttpClient.kt）
 * ─────────────────────────────────────────────────────────────────
 *
 * ── AI Services 的原理（面试必考）────────────────────────────────
 * AiServices.build() 返回的并不是你写的类，而是 java.lang.reflect.Proxy
 * 动态生成的代理对象。你调用 assistant.chat(...) 时：
 *   代理拦截方法 → 读 @SystemMessage/@UserMessage 注解组装 prompt
 *   → 从 ChatMemory 取出历史 → 拼成完整 messages → 调用模型
 *   → 流式回调逐 token 返回 → 完成后把回答写回 ChatMemory
 * 这就是"声明式"：你只描述"要什么"（接口+注解），框架填"怎么做"。
 *
 * ── Android 专项适配（三处关键，缺一崩）────────────────────────
 * 1. desugaring：build.gradle.kts 里 isCoreLibraryDesugaringEnabled = true
 * 2. HTTP 底座：LangChain4j 默认的 JDK HttpClient 在 Android 上不存在，
 *    且官方 OkHttp 适配与 1.1+ 的 Java 9 API 崩溃互相矛盾（版本两头堵）。
 *    最终方案：锁定 1.0.0 + exclude 默认 JDK 实现 + 注入我们自写的
 *    OkHttpHttpClient（见 OkHttpHttpClient.kt，不到 100 行）。
 * 3. R8 keep 规则：proguard-rules.pro 里的 -keep（反射目标不能被混淆裁掉）
 */
class LangChain4jChatClient : ChatClient {

    /**
     * 【声明 1/2】用注解描述"和模型对话"长什么样。
     * 注意这个接口是 private 的 —— 它只是给 AiServices 的"说明书"，
     * 外部世界只需要看到 ChatClient 接口。
     */
    private interface Assistant {
        @SystemMessage(SYSTEM_PROMPT)
        fun chat(
            @MemoryId memoryId: String,   // 每个会话一个独立记忆（将来多会话直接换 ID）
            @UserMessage message: String, // 本轮用户输入，历史不用管，ChatMemory 会带上
        ): TokenStream                    // 返回流：一个 token 一个回调
    }

    /**
     * 流式模型：OpenAI 兼容端点 + Qwen。
     * baseUrl 填 DashScope 的兼容模式地址 —— 和 W1 手写的完全同一个 API。
     *
     * httpClientBuilder 是 Android 上的生死开关：
     * 不指定 → 框架默认走 JDK HttpClient → 类不存在 → 一构建就崩。
     * 注入自写 OkHttpHttpClientBuilder → 底座换成你 W1 就用过的 OkHttp。
     * readTimeout=ZERO 等价于 W1 手写的 readTimeout(0)：流式期间不许掐断。
     */
    private val model = OpenAiStreamingChatModel.builder()
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .apiKey(BuildConfig.DASHSCOPE_API_KEY)
        .modelName("qwen-plus")
        .httpClientBuilder(
            OkHttpHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ZERO)   // 0 = 不设读超时，流可以一直挂着
        )
        .build()

    /**
     * 【声明 2/2】组装器：模型 + 记忆 + 接口 = 可用的助手。
     * chatMemoryProvider：每个 memoryId 发一个滑动窗口记忆，保留最近 20 条。
     * 【思考】为什么是 20 条而不是无限？—— messages 全量重发是有 token 成本的，
     * 对话越长越贵越慢（docs/01 思考题的框架侧答案），窗口就是"性价比阀"。
     */
    private val assistant: Assistant = AiServices.builder(Assistant::class.java)
        .streamingChatModel(model)
        .chatMemoryProvider { MessageWindowChatMemory.withMaxMessages(20) }
        .build()

    override suspend fun streamChat(
        userText: String,
        onFirstToken: () -> Unit,
        onDelta: (String) -> Unit,
    ): String {
        // ── Key 空检查（同 W1，诊断习惯保留）──
        if (BuildConfig.DASHSCOPE_API_KEY.isBlank()) {
            throw IllegalStateException(
                "API Key 为空：请在项目根目录 local.properties 加一行 " +
                "dashscope.apiKey=sk-xxx，然后 Build > Rebuild Project"
            )
        }
        Log.i(TAG, "请求发出: model=qwen-plus (via LangChain4j)")

        // ── 回调世界 → 协程世界的桥 ─────────────────────────────
        // TokenStream 是回调风格（onPartialResponse/onError），而我们的
        // ChatClient 接口是 suspend 挂起风格。suspendCancellableCoroutine
        // 把前者包成后者：协程挂起直到 onComplete/onError 触发才恢复。
        // 这个"回调→协程"的桥接模式在 Android 里到处都用得上，值得记住。
        val startAt = System.currentTimeMillis()
        return suspendCancellableCoroutine { cont ->
            var firstFired = false
            val full = StringBuilder()

            assistant.chat(MEMORY_ID, userText)
                .onPartialResponse { delta ->
                    if (!firstFired) {
                        firstFired = true
                        Log.i(TAG, "首个 token 到达")
                        onFirstToken()
                    }
                    full.append(delta)
                    onDelta(delta)          // 回调来自 OkHttp 线程，StateFlow 天然线程安全
                }
                .onCompleteResponse {
                    Log.i(TAG, "流结束, 共 ${full.length} 字符")
                    cont.resume(full.toString())
                }
                .onError { e ->
                    Log.e(TAG, "LangChain4j 请求失败", e)
                    cont.resumeWithException(e)
                }
                .start()                    // ⚠️ 不调用 start() 请求根本不会发出去
        }
    }

    private companion object {
        const val TAG = "SSE_CHAT"   // 沿用同一日志标签，Logcat 过滤习惯不用改
        const val MEMORY_ID = "main" // 单会话固定 ID；多会话/多用户时才需要动态生成
        const val SYSTEM_PROMPT =
            "你是运行在 Android 手机上的 AI 助手，回答简洁、准确，默认使用中文。"
    }
}
