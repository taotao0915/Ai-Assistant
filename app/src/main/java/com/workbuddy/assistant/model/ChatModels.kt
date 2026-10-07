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

/**
 * 非流式响应（stream=false 或服务器拒绝流式时返回）：
 * {"choices":[{"message":{"role":"assistant","content":"完整回答..."}}]}
 * 注意与流式的 delta 区分：非流式是完整的 message。
 */
@Serializable
data class NonStreamResponse(
    val choices: List<Choice> = emptyList(),
    val error: ApiError? = null,
) {
    @Serializable
    data class Choice(val message: Message = Message())

    @Serializable
    data class Message(val role: String? = null, val content: String? = null)

    @Serializable
    data class ApiError(
        val code: String? = null,
        val message: String? = null,
    )
}

/**
 * 【W7 新增】这条回答是谁产出的。
 *
 * 为什么值得单独一个字段？因为端云混合最大的风险是"用户感知不到"：
 * 用户不知道哪句是手机算的、哪句是云端算的，就不会觉得端侧有价值，
 * 也不会在断网时意识到"原来我还能用"。
 * 所以把推理来源显式画在气泡上，是端侧功能的产品化必修课。
 */
enum class MessageSource(val label: String) {
    /** 云端大模型（qwen-plus） */
    CLOUD("云端"),

    /** 手机端侧模型（MediaPipe / Gemma） */
    DEVICE("端侧"),

    /** 用户自己发的，没有来源 */
    NONE(""),
}

/** UI 层的消息模型（多了"是不是我发的"和"是否出错"这类展示状态） */
data class UiMessage(
    val isUser: Boolean,
    val content: String,
    val isError: Boolean = false,
    val source: MessageSource = MessageSource.NONE,
    /** 端侧回答时附带的性能账，展示成小字（TTFT / 解码速度） */
    val deviceStats: String? = null,
)
