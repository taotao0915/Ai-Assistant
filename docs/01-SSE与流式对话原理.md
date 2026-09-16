# 第 1 课：SSE 与流式对话 —— 这个项目为什么从它开始

> 对应 8 周计划的 **W1-W2：纯云对话**。目标：不借助任何 AI 框架，手写协议层。
> 理由：后面 W3 上 LangChain4j、W5 上 RAG，全是"黑盒"——现在把黑盒拆开看一遍，
> 以后用框架时你调得出 bug，别人只能重启试试。

---

## 1. 一句话理解流式输出

大模型生成回答的本质是**逐个 token 预测**（类似你打字时输入法的下一个词预测）。
生成一个 500 字的回答可能要 10-30 秒。如果等全部生成完再返回：

- 用户盯着白屏 15 秒 → 体验灾难（产品视角）
- 首字延迟 TTFT 无法测量优化（工程视角）

所以所有大模型 API 都支持 `stream: true`：**边生成边推送**，一个 token（1~2个汉字）
到达就立刻转发给客户端。SSE 就是承载这种推送的 HTTP 标准协议。

## 2. SSE 协议：三条铁律

SSE（Server-Sent Events）= **服务器单向推送的 HTTP 长连接**。

| 铁律 | 内容 |
|---|---|
| 1 | 响应头 `Content-Type: text/event-stream`，连接保持打开 |
| 2 | 消息格式：`data: {...}\n\n`（每条消息以 data: 开头 + 空行分隔） |
| 3 | 客户端断线时浏览器/SSE 客户端可自动重连（`Last-Event-ID` 续传） |

### SSE vs WebSocket（面试必问）

| | SSE | WebSocket |
|---|---|---|
| 方向 | 单向（服务器→客户端） | 双向 |
| 协议 | 就是 HTTP | 需要协议升级（Upgrade: websocket） |
| 数据格式 | 文本（UTF-8） | 文本/二进制 |
| 网关/代理穿透 | 无障碍（普通 HTTP） | 部分企业网关会掐 |
| 适用场景 | 聊天流、进度推送、通知 | 协作编辑、游戏、实时音视频信令 |

**对话场景一问一答，只需要服务器推 → 用 SSE 足够，还更省事。**
豆包/Kimi/ChatGPT 的打字机效果全是 SSE。

## 3. OpenAI 兼容协议：行业的"普通话"

请求 POST `/v1/chat/completions`：

```json
{
  "model": "qwen-plus",
  "messages": [
    {"role": "system", "content": "你是手机上的AI助手"},
    {"role": "user", "content": "你好"},
    {"role": "assistant", "content": "你好！"},
    {"role": "user", "content": "今天天气如何"}
  ],
  "stream": true
}
```

三个关键认知：

1. **模型无状态**——它不记得上一轮。所谓多轮对话 = 每次全量重发历史。
   （W3 你会看到 LangChain4j 的 ChatMemory 干的就是"帮你攒历史"这件事）
2. **system 消息是行为说明书**，必须在最前，决定模型"人格"。
3. **流式响应是增量 delta**，最终要自己拼接：
   `data: {"choices":[{"delta":{"content":"今"}}]}` → `data: [DONE]`

## 4. OkHttp 怎么读 SSE（对应 SseChatClient.kt 逐行）

```kotlin
client.newCall(request).execute().use { response ->   // execute() 只等响应头
    val source = response.body!!.source()               // Okio 字节流
    while (!source.exhausted()) {                      // 连接还活着就继续读
        val line = source.readUtf8Line() ?: break       // 服务端每写一行，这里返回一行
        if (!line.startsWith("data:")) continue         // 跳过空行
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") break                 // 结束标记
        val chunk = json.decodeFromString<StreamResponse>(payload)
        onDelta(chunk.choices.first().delta.content)    // 增量交给 UI
    }
}
```

**三个易错点**（都在代码注释里标了）：
- `readTimeout(0)`：流式连接可能几分钟不断流，不能让 OkHttp 按总时长掐断
- 网络操作必须在 `Dispatchers.IO`，否则主线程 `NetworkOnMainThreadException`
- `ignoreUnknownKeys = true`：服务端字段比客户端模型多，不忽略会解析崩溃

## 5. 状态驱动 UI：打字机效果为什么"自动"发生

```
token到达 ─▶ onDelta() ─▶ _messages.value 更新 ─▶ Compose 检测到变化
        ─▶ 只重组最后一条气泡 ─▶ 你看到"多打了一个字"
```

ChatViewModel 里每秒可能更新几十次 `messages`，但 ChatScreen 里
**没有一行手动刷新代码**。这就是声明式 UI：UI = f(state)。

`UiMessage` 列表里最后一条"空占位消息"是经典技巧——气泡先出现（带 ▍光标），
然后每个 token 往里追加内容。

## 6. 自己动手（做完才算学会 W1）

1. **跑通**：申请通义 API Key → 填 local.properties → 真机运行 → 发第一句话
2. **换模型**：把 `MODEL` 改成 `qwen-turbo` / `deepseek-chat`，对比 TTFT 差异
3. **加功能**：加一个"清空对话"按钮（提示：操作 `_messages.value`）
4. **拆黑盒**：用手机抓包（或 Logcat 打印每一行 `line`），亲眼看 `data:` 帧
5. **思考题**（下次聊时告诉我答案）：
   - 为什么长对话越聊越慢、越贵？（提示：想想第 1 节"全量重发历史"）
   - 这也正是后面 RAG / ChatMemory 窗口策略 / 摘要压缩要解决的问题

---

**下一步（W3）预告**：把这套手写客户端替换成 LangChain4j 的 AI Services，
再加上日历/联系人两个本地工具——手机上的第一个真 Agent。
