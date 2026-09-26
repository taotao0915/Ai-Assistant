# 04 — MCP：一次接入，全网工具

> 前置：读完 02（LangChain4j 框架原理）和 03（Agent 与工具调用）。
> W4.5 目标：手写一个 MCP 客户端，让 App 直连魔搭 MCP 广场的远程服务器，
> 动态获得成百上千个外部工具 —— **不引入任何 MCP SDK**。

---

## 1. MCP 解决什么问题（一句话记住）

没有 MCP 时：M 个 App 各自适配 N 个工具 → **M×N 个适配器**，谁也吃不消。
有了 MCP：工具方实现一次 MCP 服务器，接入方实现一次 MCP 客户端 → **M+N**。
MCP 就是"AI 工具的 USB-C 接口"——物理形状（协议）标准化，插什么都行。

W4 结束时我们发现的边界：`@Tool` 是**编译期**注册，写死在 APK 里，想加功能
就得发版。MCP 是**运行时**发现：连上服务器，`tools/list` 一下，模型立刻
多出一批新工具 —— App 一行代码不改。

## 2. 协议本体：JSON-RPC 2.0，就三个方法

MCP 的"协议"听着唬人，核心就是 JSON-RPC（`{"jsonrpc":"2.0","id":1,
"method":"...","params":{...}}`）加三个约定：

| 方法 | 方向 | 干什么 |
|---|---|---|
| `initialize` | 客户端→服务器 | 握手：交换协议版本、能力、名字 |
| `notifications/initialized` | 客户端→服务器 | 通知（无 id，不要响应）："我准备好了" |
| `tools/list` | 客户端→服务器 | "你有哪些工具？" → 返回名字+描述+JSON Schema |
| `tools/call` | 客户端→服务器 | "执行这个工具" → 返回 content 文本 |

**JSON Schema** 是工具的参数说明书，模型靠它知道该传什么参数：

```json
{
  "name": "fetch",
  "description": "抓取网页内容",
  "inputSchema": {
    "type": "object",
    "properties": {
      "url": { "type": "string", "description": "要抓取的网址" }
    },
    "required": ["url"]
  }
}
```

眼熟吗？这就是 W4 里框架替我们从 `@Tool` 方法反射生成的东西。MCP 服务器
直接把这份"说明书"发给你 —— 所以接入的本质是**翻译**：把 MCP 的 JSON
Schema 翻成 LangChain4j 的 `ToolSpecification`（见 McpToolProvider 的
`toJsonObjectSchema`），再把模型发起的调用翻成 MCP 的 `tools/call`。

## 3. 传输层：协议怎么"落地"

JSON-RPC 消息总得有个物理通道，MCP 规定了三种：

| 传输 | 场景 | Android 可用性 |
|---|---|---|
| **stdio** | 客户端把 MCP 服务器当本地子进程拉起（文件系统、数据库类） | ✗ 没有子进程场景 |
| **Streamable HTTP** | 新版：单 HTTP 端点，POST 进 GET 流出（可同一连接） | ✓ 但支持的服务较少 |
| **HTTP + SSE**（legacy） | 双通道：GET 长连接收响应，POST 发请求 | ✓ **魔搭广场用这个** |

### legacy SSE 时序（能画出来 = 面试加分）

```
客户端（App）                              MCP 服务器
   │ ── ① GET /sse  Accept: text/event-stream ──▶ │   建长连接
   │ ◀─ ② event: endpoint                          │
   │        data: /messages/?session_id=abc        │   "以后 POST 到这里"
   │ ── ③ POST /messages/?sid=abc {"initialize"} ─▶│
   │ ◀─ ④ (①的那条流) event: message data:{result} │   响应从流里回来
   │ ── ⑤ POST {tools/list} → ⑥ 流里收到工具清单    │
   │ ── ⑦ POST {tools/call} → ⑧ 流里收到执行结果    │
```

两个最容易懵的点（都是实现 McpClient 时的真问题）：

1. **POST 只管"发"**：响应 202 Accepted 后立刻结束，真正的响应从 ① 建立
   的 SSE 流里回来。所以客户端要维护 `id → 挂起协程` 的映射表
   （`pending: ConcurrentHashMap<Int, CompletableDeferred>`），流里收到
   message 事件按 id 分发唤醒 —— 这就是"回调→协程"桥接的分布式版。
2. **endpoint 可能是相对路径**：`data: /messages/?sid=abc` 要用
   `URI(endpoint).resolve(...)` 拼成绝对 URL。

对照 W1：你手写 SSE 解析时只有 `data:` 一种事件；MCP 的 SSE 多了
`event:` 行（endpoint / message / ping），解析骨架一模一样。

## 4. 我们的实现地图

```
agent/mcp/
├── McpClient.kt        传输层 + JSON-RPC（~250 行，含 SSE 读循环）
├── McpToolProvider.kt  桥接层：MCP 工具 ↔ LangChain4j ToolProvider SPI
└── McpServers.kt       配置：服务器 URL 清单（你唯一要动的文件）
```

### 关键设计决策（面试深挖区）

**Q：为什么用 `ToolProvider` 而不是把 MCP 工具转成 `@Tool`？**
@Tool 是注解处理器编译期反射静态方法，运行时发现的工具无法用它。
`AiServices.toolProvider(...)` 就是框架给动态工具留的口子：每次对话前回调
`provideTools()`，返回 `Map<ToolSpecification, ToolExecutor>`。

**Q：为什么预热 + 缓存，而不是 provideTools 里现连现查？**
`provideTools()` 的调用线程在框架内部（可能主线程）—— 在里面做网络 = ANR。
所以 ViewModel 启动时在 `Dispatchers.IO` 预热（连接 + tools/list + 翻译），
`provideTools()` 只读缓存，没就绪就返回空集。代价：App 刚启动的第一条
消息可能暂时没有 MCP 工具，之后每条都有 —— 拿体验换稳定，值得。

**Q：为什么工具名要加 `serverName_` 前缀？**
不同 MCP 服务器可能有同名工具（都叫 `search`）；OpenAI 协议还要求工具名
匹配 `^[a-zA-Z0-9_-]{1,64}$`。拼接前缀 + sanitize 一石二鸟。

**Q：MCP 调用失败会崩吗？**
不会。连接失败只是 Log.w + 该服务器跳过（`if (!client.connected) continue`）；
工具执行报错也会被 MCP 的 `isError` 标记捕获，以文本形式喂回模型 ——
模型会自己向用户解释失败原因。Agent 的容错哲学：**工具会失败，循环不能断**。

## 5. 魔搭 MCP 广场接入步骤

1. 打开 https://www.modelscope.cn/mcp ，登录
2. 搜索 `fetch`（网页抓取，免费无鉴权，最适合第一个试）→ 进详情页
3. 「服务配置」→「Remote」→ SSE 类型 → 复制专属 URL
   （形如 `https://mcp.api-inference.modelscope.cn/sse/xxxxx`，**24 小时有效**）
4. 编辑 `McpServers.kt`，取消示例注释、填入 URL
5. Rebuild → Run → Logcat 过滤 `SSE_CHAT`，看到：
   `MCP[fetch] 握手成功` → `MCP 工具装载完成: N 个`
6. 问它："帮我抓取 https://www.example.com 的内容"

## 6. 作业

1. **抓包视角**：在 `request()` 里加日志打印每条 JSON-RPC 全文，对着第 3
   节的时序图走一遍完整流程
2. **断线实验**：URL 过期后再发消息 —— 观察连接失败是否影响正常聊天
   （本地工具还在吗？答案：在，两级工具互不干扰）
3. **多服务器实验**：同时挂 fetch + 另一个服务，问一个需要两个工具配合
   的问题，观察模型怎么选
4. **思考**：为什么魔搭的 URL 是个人专属且 24h 过期？这种"URL 即令牌"
   的鉴权方式 vs Authorization 头，各有什么优劣？

## 7. 下一步（W5 预告）

MCP 直连是"点对点"，W5 我们把它升级为"中心化"：FastAPI 后端做 MCP 宿主，
统一管理连接、缓存、鉴权，Android 只跟后端说话。到时你能亲自对比两种
架构的取舍 —— 这正是"端云混合"路线的核心能力。
