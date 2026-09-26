package com.workbuddy.assistant.agent.mcp

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W4.5】MCP 客户端 —— 手写 JSON-RPC over SSE（不依赖任何 MCP SDK）
 * ══════════════════════════════════════════════════════════════════
 *
 * 为什么自己写而不用官方 langchain4j-mcp？
 *   和 W3 的 HttpClient 一模一样的故事：官方实现依赖 JDK HttpClient /
 *   Java 9+ API，在 Android 上必崩。而我们已经有 OkHttp + W1 的 SSE
 *   解析经验 —— 协议本身只是 JSON-RPC 2.0，三个方法就撑起整个生态。
 *
 * MCP 传输方式三选一（协议规范）：
 *   stdio           本地子进程   —— Android 无进程场景，✗
 *   Streamable HTTP 新版单端点   —— 较新，部分服务支持
 *   HTTP + SSE      legacy 双通道 —— 魔搭 MCP 广场用的就是它，✓ 本类实现
 *
 * ── legacy SSE 传输的时序（核心，面试能画出来就赢了）──────────────
 *
 *   客户端                                   MCP 服务器
 *     │ ── GET /sse (Accept: text/event-stream) ──▶ │  ①建流（长连接）
 *     │ ◀── event: endpoint  data: /messages?sid=xx │  ②服务器告知 POST 地址
 *     │ ── POST /messages?sid=xx {initialize}  ───▶ │  ③JSON-RPC 请求
 *     │ ◀── (同一个 SSE 流) event: message data:{}   │  ④响应从流里回来
 *     │ ── POST {tools/list} / {tools/call}  ─────▶ │
 *     │ ◀── ...                                     │
 *
 *   关键认知：POST 只负责"发"，响应永远从 GET 的 SSE 流里回来。
 *   所以客户端要维护 id→挂起协程 的映射表，收到流里的响应按 id 分发。
 */
class McpClient(
    /** 服务器名（仅字母数字，会拼进工具名，避免多服务器重名） */
    val serverName: String,
    /** SSE 端点 URL（如魔搭广场的 https://mcp.api-inference.modelscope.cn/sse/xxx） */
    private val endpoint: String,
    /** 额外请求头（如 Authorization） */
    private val headers: Map<String, String> = emptyMap(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** GET 流专用：readTimeout=0，长连接不许被掐（和 W1 流式同一个道理） */
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** POST 专用：正常超时 */
    private val callClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val idCounter = AtomicInteger(1)
    private val endpointReady = CompletableDeferred<String>()

    @Volatile
    var connected = false
        private set

    @Volatile
    private var messageUrl: String? = null
    private var streamCall: Call? = null

    // ── 连接：建流 → 等 endpoint → initialize 握手 ───────────────────

    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        if (connected) return@withContext
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val call = streamClient.newCall(request)
        streamCall = call
        val response = call.execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("MCP[$serverName] SSE 握手失败: HTTP ${response.code}" +
                "（魔搭 URL 24 小时有效，过期请重新复制）")
        }
        val body = response.body ?: throw IOException("MCP[$serverName] 无响应体")
        scope.launch { readSseLoop(body) }

        // ② 等服务器告知 POST 端点
        messageUrl = withTimeout(15_000) { endpointReady.await() }

        // ③④ initialize 握手：交换协议版本与能力
        val serverInfo = request("initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", "WorkbuddyAssistant")
                put("version", "1.0")
            })
        })
        Log.i(TAG, "MCP[$serverName] 握手成功: serverInfo=$serverInfo")
        postNotification("notifications/initialized")
        connected = true
    }

    /** 拉取服务器工具清单（MCP 的"自我介绍"） */
    suspend fun listTools(): List<McpTool> {
        val result = request("tools/list", JsonObject(emptyMap()))
        val tools = result["tools"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return tools.mapNotNull { el ->
            val t = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = (t["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            McpTool(
                name = name,
                description = (t["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                inputSchema = t["inputSchema"]?.let { runCatching { it.jsonObject }.getOrNull() },
            )
        }
    }

    /** 执行一次工具调用，返回文本结果 */
    suspend fun callTool(name: String, argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) }
            .getOrDefault(JsonObject(emptyMap()))
        val result = request("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        }, timeoutMs = 120_000)
        val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false
        val text = (result["content"] as? kotlinx.serialization.json.JsonArray)
            .orEmpty()
            .mapNotNull { item ->
                val o = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                if ((o["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (o["text"] as? JsonPrimitive)?.contentOrNull
                } else null
            }
            .joinToString("\n")
        return if (isError) "[MCP 工具报错] $text"
        else text.ifEmpty { "(工具执行成功，无文本输出)" }
    }

    fun close() {
        connected = false
        streamCall?.cancel()
        scope.cancel()
    }

    // ── JSON-RPC 两件套：request（要响应）/ notify（不要响应）────────

    private suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMs: Long = 60_000,
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = messageUrl ?: throw IOException("MCP[$serverName] 未连接（endpoint 未就绪）")
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(url).post(body)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            callClient.newCall(req).execute().use { resp ->
                // 202 = 已受理，响应稍后从 SSE 流回来（见 readSseLoop）
                if (!resp.isSuccessful) {
                    throw IOException("MCP[$serverName] POST $method → HTTP ${resp.code}")
                }
            }
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun postNotification(method: String) {
        val url = messageUrl ?: return
        val body = buildJsonObject {
            put("jsonrpc", "2.0")   // 通知没有 id 字段 —— 这是通知和请求的区别
            put("method", method)
            put("params", JsonObject(emptyMap()))
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        runCatching { callClient.newCall(req).execute().close() }
    }

    // ── SSE 读循环：W1 手写过的 parse 的近亲，多了 event 名 ───────────

    private suspend fun readSseLoop(body: ResponseBody) {
        try {
            var event = "message"
            val data = StringBuilder()
            body.source().use { source ->
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> data.appendLine(line.removePrefix("data:").trim())
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) handleEvent(event, data.toString().trim())
                            event = "message"
                            data.clear()
                        }
                        // 注释行（":"开头）等其余行直接忽略
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MCP[$serverName] SSE 流断开: ${e.message}")
        } finally {
            connected = false
        }
    }

    private fun handleEvent(event: String, payload: String) {
        when (event) {
            "endpoint" -> {
                // 端点可能是相对路径，解析成绝对 URL
                val abs = runCatching { URI(endpoint).resolve(payload).toString() }
                    .getOrDefault(payload)
                endpointReady.complete(abs)
            }
            "message" -> {
                val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return
                val deferred = pending[id] ?: return
                val error = obj["error"]
                if (error != null) {
                    deferred.completeExceptionally(IOException("MCP[$serverName] 错误: $error"))
                } else {
                    deferred.complete(
                        obj["result"]?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: JsonObject(emptyMap())
                    )
                }
            }
            // "ping"、各种 notification 事件：直接忽略
        }
    }

    companion object {
        private const val TAG = "SSE_CHAT"
    }
}

/** MCP 工具的"自我介绍"：名字 + 描述 + JSON Schema 参数说明 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject?,
)
