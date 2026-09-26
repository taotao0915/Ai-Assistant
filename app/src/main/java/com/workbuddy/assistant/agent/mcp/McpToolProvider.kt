package com.workbuddy.assistant.agent.mcp

import android.util.Log
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.service.tool.ToolExecutor
import dev.langchain4j.service.tool.ToolProvider
import dev.langchain4j.service.tool.ToolProviderRequest
import dev.langchain4j.service.tool.ToolProviderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W4.5】MCP 工具桥 —— 把"运行时发现的工具"接进静态的 AiServices
 * ══════════════════════════════════════════════════════════════════
 *
 * W4 的 @Tool 注解是【编译期】注册：方法写死在代码里。
 * MCP 的工具是【运行时】发现：连上服务器才知道有哪些。
 * 桥接靠 LangChain4j 的 ToolProvider SPI —— 每次对话请求前，
 * 框架会问它"现在有什么工具可用"，我们返回 ToolSpecification+ToolExecutor。
 *
 * 两级翻译（这就是本文件的全部工作）：
 *   ① MCP 的 inputSchema（JSON Schema 字典）
 *        → ToolSpecification.parameters（JsonObjectSchema 对象树）
 *      —— 模型靠它知道"这个工具接受什么参数"
 *   ② 模型发起的工具调用（ToolExecutionRequest）
 *        → MCP 的 tools/call（JSON-RPC）
 *      —— ToolExecutor 闭包，方向反过来
 *
 * 线程模型（Android 红线）：
 *   provideTools() 可能被框架在主线程回调 —— 绝不在这里做网络！
 *   所以采用"预热 + 缓存"：ViewModel 启动时在 IO 协程连接并拉取工具，
 *   provideTools 只读缓存，没就绪就返回空（第一条消息可能暂时没有
 *   MCP 工具，等预热完成后的消息就都有了 —— 拿体验换稳定，值得）。
 */
class McpToolProvider private constructor() : ToolProvider {

    private val clients: List<McpClient> =
        McpServers.all.map { McpClient(it.name, it.url, it.headers) }

    @Volatile
    private var cachedTools: Map<ToolSpecification, ToolExecutor>? = null

    private val loadMutex = Mutex()

    /** App 启动时调用：逐个服务器握手（网络都在 IO 线程） */
    suspend fun warmUp() = coroutineScope {
        clients.map { client ->
            launch(Dispatchers.IO) {
                runCatching { client.connect() }
                    .onSuccess { Log.i(TAG, "MCP[${client.serverName}] 连接成功") }
                    .onFailure { Log.w(TAG, "MCP[${client.serverName}] 连接失败: ${it.message}") }
            }
        }
    }

    /** 连接完成后拉取工具清单并翻译（也是 IO 线程调用） */
    suspend fun ensureLoaded(): Int = loadMutex.withLock {
        cachedTools?.let { return it.size }
        val map = HashMap<ToolSpecification, ToolExecutor>()
        for (client in clients) {
            if (!client.connected) continue
            runCatching {
                client.listTools().forEach { tool ->
                    val spec = ToolSpecification.builder()
                        .name(sanitize("${client.serverName}_${tool.name}"))
                        // 描述里带上来源服务器 —— 模型选工具时的重要上下文
                        .description("[MCP:${client.serverName}] ${tool.description}".trim())
                        .parameters(toJsonObjectSchema(tool.inputSchema))
                        .build()
                    // ② 翻译回程：模型的调用请求 → MCP 的 tools/call
                    // ToolExecutor 运行在框架的工具执行线程（OkHttp 线程），
                    // 阻塞等待 MCP 响应在这个线程上是安全的。
                    map[spec] = ToolExecutor { req: ToolExecutionRequest, _ ->
                        Log.i(TAG, "MCP 工具执行: ${req.name()} 参数=${req.arguments()}")
                        runBlocking { client.callTool(tool.name, req.arguments()) }
                    }
                }
            }.onFailure { Log.w(TAG, "MCP[${client.serverName}] 拉取工具失败: ${it.message}") }
        }
        cachedTools = map
        Log.i(TAG, "MCP 工具装载完成: ${map.size} 个 " +
            "(${map.keys.joinToString { it.name() }})")
        return map.size
    }

    /** 框架每次对话前回调 —— 只读缓存，零网络（主线程安全） */
    override fun provideTools(request: ToolProviderRequest): ToolProviderResult {
        val tools = cachedTools
        if (tools == null) {
            Log.w(TAG, "MCP 工具尚未预热完成，本次请求暂不携带 MCP 工具")
        }
        return ToolProviderResult.builder().addAll(tools ?: emptyMap()).build()
    }

    fun close() = clients.forEach { it.close() }

    // ── ① 翻译去程：JSON Schema → JsonObjectSchema 对象树 ────────────
    // 只覆盖常见类型（string/integer/number/boolean/array/enum/嵌套 object），
    // 认不出的兜底成 string + 描述 —— 务实：90% 的 MCP 工具都是简单类型。

    private fun toJsonObjectSchema(schema: JsonObject?): JsonObjectSchema? {
        if (schema == null) return null
        val builder = JsonObjectSchema.builder()
        val props = schema["properties"]?.let { runCatching { it.jsonObject }.getOrNull() }
        props?.forEach { (name, def) ->
            runCatching {
                builder.addProperty(name, schemaElement(def.jsonObject, name))
            }.onFailure { Log.w(TAG, "参数 $name 的 Schema 无法识别: ${it.message}") }
        }
        // required 是字符串数组：声明"模型必须提供"的参数名
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        if (required.isNotEmpty()) builder.required(required)
        return runCatching { builder.build() }.getOrNull()
    }

    private fun schemaElement(def: JsonObject, fallbackName: String): JsonSchemaElement {
        val type = (def["type"] as? JsonPrimitive)?.contentOrNull
        val desc = (def["description"] as? JsonPrimitive)?.contentOrNull
        return when (type) {
            "string" -> JsonStringSchema.builder().apply { desc?.let { description(it) } }.build()
            "integer" -> JsonIntegerSchema.builder().apply { desc?.let { description(it) } }.build()
            "number" -> JsonNumberSchema.builder().apply { desc?.let { description(it) } }.build()
            "boolean" -> JsonBooleanSchema.builder().apply { desc?.let { description(it) } }.build()
            "array" -> JsonArraySchema.builder()
                .apply { desc?.let { description(it) } }
                .items(
                    def["items"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        ?.let { schemaElement(it, fallbackName) }
                        ?: JsonStringSchema.builder().build()
                )
                .build()
            "object" -> toJsonObjectSchema(def) ?: JsonObjectSchema.builder().build()
            else -> {
                // enum 在 JSON Schema 里是字符串数组，如 {"enum": ["a","b"]}
                val enumVals = (def["enum"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                if (enumVals.isNotEmpty()) {
                    JsonEnumSchema.builder()
                        .apply { desc?.let { description(it) } }
                        .enumValues(enumVals)
                        .build()
                } else {
                    JsonStringSchema.builder()
                        .description(desc ?: "参数 $fallbackName")
                        .build()
                }
            }
        }
    }

    /** 工具名约束：^[a-zA-Z0-9_-]{1,64}$（OpenAI 协议要求） */
    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

    companion object {
        private const val TAG = "SSE_CHAT"

        @Volatile
        private var instance: McpToolProvider? = null

        /** 全局单例：所有会话共享同一批 MCP 连接与工具缓存 */
        fun get(): McpToolProvider =
            instance ?: synchronized(this) {
                instance ?: McpToolProvider().also { instance = it }
            }
    }
}
