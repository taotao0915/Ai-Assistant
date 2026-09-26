package com.workbuddy.assistant.agent

import android.util.Log
import dev.langchain4j.exception.HttpException
import dev.langchain4j.http.client.HttpClient
import dev.langchain4j.http.client.HttpClientBuilder
import dev.langchain4j.http.client.HttpRequest
import dev.langchain4j.http.client.SuccessfulHttpResponse
import dev.langchain4j.http.client.sse.ServerSentEvent
import dev.langchain4j.http.client.sse.ServerSentEventParser
import dev.langchain4j.http.client.sse.ServerSentEventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════════════════════════════
 * 【原理课 5】自己动手实现 LangChain4j 的 HTTP 客户端 SPI
 * ══════════════════════════════════════════════════════════════════
 *
 * 为什么这个文件存在？（版本考古见 build.gradle.kts 的注释）
 *   LangChain4j 的 OpenAI 模块从 1.0.0-beta2 起通过可插拔抽象
 *   dev.langchain4j.http.client.HttpClient 发请求，官方默认实现是
 *   JDK HttpClient（Android 上不存在 → 必崩），官方 OkHttp 适配
 *   又从 1.13 才有（而 1.1+ 的多个 Java 9~15 API 崩溃让我们必须停在 1.0.0）。
 *   所以自己写一个 —— 连 SSE 解析器一起，总共不到 200 行。
 *
 * SPI 只要求实现两件事（这就是"抽象层"的全部秘密）：
 *   1. execute(request): SuccessfulHttpResponse     ← 同步请求
 *   2. execute(request, parser, listener)           ← 流式请求：
 *      拿到字节流后自己解析 SSE（见 parseSseStream），
 *      把每个事件构造为 ServerSentEvent 回调给 listener.onEvent。
 *      【为什么不用框架的 parser？】它内部用了 StringBuilder.isEmpty()
 *      ——Java 15 API，Android API 33+ 才有且脱糖不覆盖，真机必崩。
 *
 * 【面试考点】"框架适配 Android 时最常踩的坑是什么？"
 *   答案模板：JVM 库默认依赖三类 Android 没有的东西——
 *   ① java.net.http（JDK 11 网络层）② Java 9+ 的新 API（如 ServiceLoader.findFirst，
 *   脱糖不覆盖）③ SPI 反射加载（R8 会裁）。
 *   对策：能注入实现就注入实现（httpClientBuilder）、版本锁定在坑引入之前、
 *   R8 keep 规则保住反射目标。这三招我们全用上了。
 */
class OkHttpHttpClientBuilder : HttpClientBuilder {

    private var connectTimeout: Duration = Duration.ofSeconds(15)
    private var readTimeout: Duration = Duration.ZERO   // 0 = 不设读超时（SSE 流必须能一直挂着）

    override fun connectTimeout(): Duration = connectTimeout
    override fun connectTimeout(timeout: Duration): HttpClientBuilder = apply { connectTimeout = timeout }
    override fun readTimeout(): Duration = readTimeout
    override fun readTimeout(timeout: Duration): HttpClientBuilder = apply { readTimeout = timeout }

    override fun build(): HttpClient = OkHttpHttpClient(connectTimeout, readTimeout)
}

class OkHttpHttpClient(
    connectTimeout: Duration,
    readTimeout: Duration,
) : HttpClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
        // readTimeout=0：流式响应中间可能停顿几秒甚至更久（模型在"思考"），
        // 读超时一掐流就断了 —— 和 W1 手写 SseChatClient 的 readTimeout(0) 同一个道理
        .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    // ── 同步：非流式请求（我们暂时用不到，但 SPI 要求实现）──────────
    override fun execute(request: HttpRequest): SuccessfulHttpResponse {
        val response = client.newCall(request.toOkHttpRequest()).execute()
        response.use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                // HttpException 是框架认识的标准错误类型，会带上状态码向上传
                throw HttpException(resp.code, "HTTP ${resp.code}: ${body.take(300)}")
            }
            return SuccessfulHttpResponse.builder()
                .statusCode(resp.code)
                .headers(resp.headers.toMultimap())
                .body(body)
                .build()
        }
    }

    // ── 异步：SSE 流式请求（打字机效果的底层通道）────────────────────
    // 职责：拿到响应体字节流后，自己解析 SSE 并逐事件回调 listener。
    // 解析是阻塞的，会一直读到流结束 —— 正好跑在 OkHttp 的回调线程上。
    override fun execute(request: HttpRequest, parser: ServerSentEventParser, listener: ServerSentEventListener) {
        client.newCall(request.toOkHttpRequest()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                listener.onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        listener.onError(HttpException(resp.code, "HTTP ${resp.code}"))
                        return
                    }
                    listener.onOpen(
                        SuccessfulHttpResponse.builder()
                            .statusCode(resp.code)
                            .headers(resp.headers.toMultimap())
                            .build()
                    )
                    Log.i("SSE_CHAT", "流式连接建立: HTTP ${resp.code}")
                    try {
                        // 【W3 排障第 8 层】不再调用 parser.parse()——框架自带的
                        // DefaultServerSentEventParser 用了 StringBuilder.isEmpty()，
                        // 这是 Java 15 API（Android API 33+ 才有，脱糖不覆盖），
                        // API < 33 的真机上解析第一行 SSE 就崩 NoSuchMethodError。
                        // 解析这步本来就在我们手里（SPI 的设计），换成自写解析器，
                        // 逻辑逐行对照框架源码（DefaultServerSentEventParser.java）
                        // 移植自 W1 手写的 SSE 解析，语义完全一致：
                        parseSseStream(resp.body!!.byteStream(), listener)
                        listener.onClose()
                    } catch (e: Exception) {
                        listener.onError(e)
                    }
                }
            }
        })
    }

    // ── 自写 SSE 解析器（替换框架的 DefaultServerSentEventParser）────
    // 协议语义与框架解析器逐行一致（照着 1.0.0 源码移植的）：
    //   空行        → 一个事件结束，把攒好的 data 派发给 listener
    //   event: xxx  → 记录事件名
    //   data: xxx   → 追加数据（多行 data 用 \n 连接，内容 trim）
    //   其他行       → 忽略（SSE 协议的注释 ":"、id:、retry: 等）
    //   流结束       → 若还有未派发的 data，补派发一次
    // 唯一的区别：框架版用了 StringBuilder.isEmpty()（Java 15，API 33+），
    // 我们用 Kotlin 的 isNotEmpty()（编译成 length > 0），全 API 级别安全。
    private fun parseSseStream(body: InputStream, listener: ServerSentEventListener) {
        BufferedReader(InputStreamReader(body, Charsets.UTF_8)).use { reader ->
            var event: String? = null
            val data = StringBuilder()

            fun dispatch() {
                val payload = data.toString()
                if (payload.isNotEmpty()) {
                    // 与框架一致：listener 抛异常不中断解析
                    runCatching { listener.onEvent(ServerSentEvent(event, payload)) }
                }
                event = null
                data.setLength(0)
            }

            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> dispatch()
                    line.startsWith("event:") -> event = line.substring("event:".length).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring("data:".length).trim())
                    }
                    // 其余行按 SSE 协议忽略
                }
            }
            dispatch()
        }
    }

    // ── 框架的 HttpRequest → OkHttp 的 Request ──────────────────────
    private fun HttpRequest.toOkHttpRequest(): Request {
        val builder = Request.Builder().url(url())

        headers().forEach { (name, values) -> values.forEach { builder.header(name, it) } }

        val bodyText = body()
        val okBody = if (bodyText != null) {
            bodyText.toRequestBody("application/json".toMediaType())
        } else null

        return builder.method(method().name, okBody).build()
    }
}
