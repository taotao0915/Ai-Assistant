package com.workbuddy.assistant.agent

/**
 * 【W3 新增】对话客户端的抽象接口。
 *
 * 为什么要有这个接口？
 *   - ChatViewModel 只依赖这个接口，不关心底下是"手写 OkHttp"还是"LangChain4j"
 *   - 这是依赖倒置：将来换后端（W5 的 FastAPI 代理）、换端侧模型（W7 的 MNN），
 *     都只需新增一个实现类，ViewModel 一行不改
 *   - 【面试考点】对比 W1：接口的入参从 history 变成了单句 userText ——
 *     因为"拼历史"这件事被框架的 ChatMemory 接管了，这是 W3 最大的变化
 */
interface ChatClient {
    suspend fun streamChat(
        userText: String,
        onFirstToken: () -> Unit = {},
        onDelta: (String) -> Unit,
    ): String
}
