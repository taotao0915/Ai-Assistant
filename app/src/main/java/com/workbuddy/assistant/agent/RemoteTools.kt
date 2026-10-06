package com.workbuddy.assistant.agent

import android.util.Log
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W5】云端工具 —— "手"从本机延伸到服务器
 * ══════════════════════════════════════════════════════════════════
 *
 * 对照 W4 的 DeviceTools：代码形状一模一样（@Tool 方法），
 * 唯一的区别是方法体里干的事从"查本地系统服务"变成"发 HTTP 请求"。
 * 这就是端云混合的核心形态：
 *
 *   模型（决策）→ 工具菜单（本地手 + 云端手并列）→ 模型自主选
 *
 * 密钥安全回顾：APK 里只有内网地址，DashScope Key 在后端环境变量里 ——
 * 反编译 APK 拿不到任何密钥（面试安全题的标准答案）。
 */
class RemoteTools {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)   // embedding/检索在后端可能要几秒
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun post(path: String, body: String): Pair<Int, String> {
        val req = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun get(path: String): Pair<Int, String> {
        val req = Request.Builder().url("$BASE_URL$path").get().build()
        client.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    // ── 云端备忘录：和 W4 的本地备忘录并存，模型按描述区分 ──────────

    @Tool("把一条备忘录保存到云端服务器，跨设备永久保存。当用户要求记录、保存信息时优先使用")
    fun saveMemoToCloud(
        @P("要保存的备忘录内容") content: String,
    ): String {
        Log.i("SSE_CHAT", "工具执行: saveMemoToCloud($content)")
        val (code, body) = post("/memos", """{"content": ${jsonStr(content)}}""")
        return if (code == 200) "已保存到云端（$body）" else "保存失败: HTTP $code"
    }

    @Tool("读取云端服务器上保存的全部备忘录（跨设备同步的那些）")
    fun listCloudMemos(): String {
        Log.i("SSE_CHAT", "工具执行: listCloudMemos()")
        val (code, body) = get("/memos")
        if (code != 200) return "读取失败: HTTP $code"
        val memos = runCatching {
            json.parseToJsonElement(body).jsonObject["memos"]?.jsonArray.orEmpty()
        }.getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
        if (memos.isEmpty()) return "云端还没有任何备忘录"
        return memos.mapIndexed { i, el ->
            val o = el.jsonObject
            "${i + 1}. ${o["content"]?.jsonPrimitive?.content}"
        }.joinToString("\n", prefix = "云端共${memos.size}条备忘录：\n")
    }

    // ── RAG 知识库检索：W5 的主角 ────────────────────────────────────
    // 模型不能"知道"你的私人文档 —— 但检索结果会作为工具返回值进入
    // 上下文，模型基于它回答。这就是 RAG 的全部：
    //   检索(Retrieval) 增强了 生成(Generation) 的 上下文。

    @Tool("在个人知识库中语义检索资料。当用户提问涉及文档、笔记、计划等私人资料内容时使用")
    fun searchKnowledge(
        @P("要检索的问题或关键词") query: String,
    ): String {
        Log.i("SSE_CHAT", "工具执行: searchKnowledge($query)")
        val (code, body) = post("/search", """{"query": ${jsonStr(query)}, "top_k": 4}""")
        if (code != 200) return "检索失败: HTTP $code"
        val results = runCatching {
            json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
        }.getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
        if (results.isEmpty()) return "知识库中没有找到相关内容"
        return results.mapIndexed { i, el ->
            val o = el.jsonObject
            "[${i + 1}] (相关度 ${o["score"]?.jsonPrimitive?.content} 来源:${o["source"]?.jsonPrimitive?.content})\n" +
                o["text"]?.jsonPrimitive?.content
        }.joinToString("\n\n")
    }

    /** 字符串安全序列化成 JSON 字面量（引号/换行转义） */
    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "") + "\""

    companion object {
        /**
         * 后端地址配置：
         *  - USB 连接：电脑执行 adb reverse tcp:8000 tcp:8000，用 127.0.0.1
         *  - 同一 WiFi：用电脑局域网 IP（ipconfig 看 IPv4），如 192.168.1.5
         */
        const val BASE_URL = "http://127.0.0.1:8000"
    }
}
