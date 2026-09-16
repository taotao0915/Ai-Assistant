package com.workbuddy.assistant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ══════════════════════════════════════════════════════════════════
 * 【原理课 1】大模型对话 API 的"协议模型"
 * ══════════════════════════════════════════════════════════════════
 *
 * 全行业事实标准是 OpenAI 的 /chat/completions 协议，Qwen(通义)、
 * DeepSeek、Kimi、GLM 全部兼容它。学会这一套 = 会调用所有主流模型。
 *
 * 请求体长这样（就是下面 ChatRequest 的序列化结果）：
 * {
 *   "model": "qwen-plus",
 *   "messages": [
 *     {"role": "system",    "content": "你是一个助手"},
 *     {"role": "user",      "content": "你好"},
 *     {"role": "assistant", "content": "你好！"},   ← 上一轮回复也要带上
 *     {"role": "user",      "content": "今天天气"}
 *   ],
 *   "stream": true
 * }
 *
 * 核心认知（面试必考）：
 * 1. 模型是"无状态"的 —— 它不记得你上一句说了什么！所谓"多轮对话"，
 *    就是客户端每次把完整历史 messages 重新发一遍。
 * 2. system 消息是"人设"，必须放最前面。
 * 3. stream=true 时，服务端会把回答切成几百个"token 增量"逐个推送，
 *    这就是打字机效果的本质。
 */

@Serializable
data class ChatMessage(
    val role: String,        // system / user / assistant
    val content: String,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double = 0.7,
)

/**
 * 流式响应里每个 SSE data 帧的 JSON 结构（OpenAI 兼容格式）：
 * {"choices":[{"delta":{"content":"今"}}]}
 *
 * 注意：流式时是 delta（增量片段），非流式时才是 message（完整回答）。
 */
@Serializable
data class StreamResponse(
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(
        val delta: Delta = Delta(),
        val finishReason: String? = null,
    ) {
        @Serializable
        data class Delta(
            val content: String? = null,
            @SerialName("reasoning_content") // DeepSeek-R1 等推理模型的思维链增量
            val reasoningContent: String? = null,
        )
    }
}

/** UI 层的消息模型（多了"是不是我发的"和"是否出错"这类展示状态） */
data class UiMessage(
    val isUser: Boolean,
    val content: String,
    val isError: Boolean = false,
)
