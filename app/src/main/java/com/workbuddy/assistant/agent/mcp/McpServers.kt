package com.workbuddy.assistant.agent.mcp

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W4.5】MCP 服务器配置 —— 你在这里"插上"外部工具世界
 * ══════════════════════════════════════════════════════════════════
 *
 * 怎么拿到 URL（魔搭 MCP 广场，https://www.modelscope.cn/mcp）：
 *   1. 登录魔搭账号，进任意 Hosted 服务的详情页
 *   2. 切到「服务配置」→「Remote」→ 选 SSE 类型
 *   3. 复制生成的专属 URL（内含鉴权信息，24 小时有效，过期重新复制）
 *   4. 在下面加一行 McpServerConfig(...)
 *
 * ⚠️ name 只能用字母和数字（会拼进工具名发给模型，如 fetch_fetch）。
 * ⚠️ URL 含个人令牌，别提交到公开仓库（这个项目目前是本地私库，无妨）。
 */
data class McpServerConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

object McpServers {

    /**
     * 在这里挂你的 MCP 服务器。留空 = 纯本地工具模式（和 W4 一样）。
     * 建议从魔搭的 fetch（网页抓取）开始 —— 免费、无参数鉴权、效果直观：
     * 问"帮我抓取 xxx 网页的内容"看它连两跳工具调用。
     */
    val all: List<McpServerConfig> = listOf(
        // 示例 1：魔搭 fetch 服务（网页抓取）
        // McpServerConfig("fetch", "https://mcp.api-inference.modelscope.cn/sse/你的专属ID"),

        // 示例 2：需要额外鉴权头的服务
        // McpServerConfig("amap", "https://mcp.api-inference.modelscope.cn/sse/你的专属ID",
        //     headers = mapOf("Authorization" to "Bearer 你的Token")),

        McpServerConfig("fetch", "https://mcp.api-inference.modelscope.net/66139f3c300d43/sse"),
    )
}
