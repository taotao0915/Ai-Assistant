# 03 · Agent 与工具调用原理（W4）

> 上一个里程碑：W3 完成时，我们有一个**会聊天、有记忆**的 App。
> 本周结束时的目标：一个**能对手机"动手"**的 App —— 问电量它真查电量，
> 说"帮我记一下"它真写进存储。这就是 Agent。

---

## 一、什么是 Agent？一个公式说清

```
Agent = LLM（大脑） + Tools（手） + Loop（循环）
```

W1~W3 的架构里，模型是**纯粹的应答机**：输入文字 → 输出文字。
但"现在几点""电量多少""帮我记住xx"这类事，模型**永远不可能**回答对——

- 它没有时钟，"现在"对它是不存在的概念；
- 它不知道"这台手机"，训练数据里没有你此刻的电量；
- 它上一轮说过的话如果不在上下文里，就等于没说过。

**工具调用的本质：模型从"回答者"升级为"决策者+表达者"，脏活累活外包给真机代码。**

```
   ┌─────────────────────── Agent Loop ───────────────────────┐
   │                                                          │
   ▼                                                          │
 用户提问 ──▶ LLM 思考 ──┬─▶ "我知道了，直接回答" ──▶ 输出文字 │
                         │                                    │
                         └─▶ "我需要数据" ──▶ 提出工具调用请求 │
                                   │                          │
                                   ▼                          │
                          App 执行真机代码 ──▶ 把结果喂回 LLM ─┘
                          (getBatteryLevel → 87%)
```

注意两个角色的分工——**这是 Agent 的精髓**：

| 谁 | 干什么 | 凭什么 |
|---|---|---|
| 模型 | **决策**（要不要用工具、用哪个、参数填什么）+ **表达**（把结果组织成人话） | 工具的描述文字 |
| 真机代码 | **执行**（查电量、写存储……普通 Android 代码） | 系统服务、API |

---

## 二、协议层：Function Calling 到底发了什么

W1 我们逐字节看过 SSE 和 Chat Completion 协议，现在升级它。带工具的请求长这样：

**① 请求时多一个 `tools` 字段**（框架把我们 @Tool 方法反射成的 JSON Schema）：

```json
{
  "model": "qwen-plus",
  "messages": [ {"role": "user", "content": "现在电量够刷视频吗"} ],
  "tools": [{
    "type": "function",
    "function": {
      "name": "getBatteryLevel",
      "description": "查询手机当前电量百分比。当用户询问电量时使用",
      "parameters": { "type": "object", "properties": {}, "required": [] }
    }
  }]
}
```

**② 模型的回应可能不是文字，而是"调用请求"**：

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "tool_calls": [{
        "id": "call_abc",
        "function": { "name": "getBatteryLevel", "arguments": "{}" }
      }]
    }
  }],
  "finish_reason": "tool_calls"
}
```

**③ 我们的 App 执行后，把结果作为新消息喂回去**（注意 `role: "tool"`）：

```json
{
  "messages": [
    {"role": "user", "content": "现在电量够刷视频吗"},
    {"role": "assistant", "tool_calls": [ ... ]},
    {"role": "tool", "tool_call_id": "call_abc", "content": "当前电量为 87%"},
  ]
}
```

**④ 模型拿到数据，生成最终人话**："电量 87%，刷视频绰绰有余～"

**整个 ②③④ 就是 Agent Loop：模型要几次工具、执行几轮、循环几次，直到它不再发 tool_calls。** 关键认知：这些全是普通 HTTP 请求，没有任何魔法——W1 里面你见过的 `choices[0].message.content` 只是变成了 `tool_calls`。

---

## 三、框架层：LangChain4j 替你干了什么

对照表（延续 W1→W3 的风格）：

| 你不写的代码 | 谁干了 | 在哪看 |
|---|---|---|
| 手写 4 个工具的 JSON Schema | `ToolSpecifications` 反射扫 @Tool/@P 注解生成 | agent/tool/ToolSpecifications |
| 把 tools 挂进每次请求 | 模型客户端组装请求时自动带上 | model/openai 内部 |
| 判断模型是否发了 tool_calls、解析参数 JSON | `ToolService` + `ToolExecutionRequestUtil` | service/tool/ |
| 反射调用你的 Kotlin 方法、JSON 反序列化参数 | `DefaultToolExecutor` | service/tool/ |
| 执行完把 role=tool 消息塞回记忆、发起**第二轮**请求 | 流式：`AiServiceStreamingResponseHandler` | service/ |
| 把执行结果通知你 | `TokenStream.onToolExecuted` 回调 | 本项目已挂日志 |

所以你在 `LangChain4jChatClient` 里只加了一行：`.tools(DeviceTools(context))`。
**"一行接入"的代价是"一行黑盒"** —— 所以 docs 里才有这张表，断点打在
`DeviceTools` 的方法上，你就能看到框架替你调用的瞬间。

**一个容易忽略的细节**：流式模式下，第一轮模型如果决定调工具，
`onPartialResponse` 可能**一个字都不吐**（模型没在说话，它在要数据），
然后你能从 Logcat 看到"工具已执行"，接着第二轮回答才开始流式输出。
用户观感上"等了一会儿才出字"—— 那是在等工具，不是卡了。

---

## 四、Android 专项纪律（面试现成素材）

1. **线程**：工具方法跑在 OkHttp 后台线程（流式回调链上），**绝不能碰 UI**。
   要更新界面就发到 ViewModel/StateFlow，让单向数据流去干活。
2. **耗时**：工具时间 = 用户等待时间（串在 Loop 里）。查询类毫秒级没问题；
   千万别在工具里做网络重活/长循环。
3. **描述即选型**：模型选工具完全靠 `@Tool("...")` 的描述文字。描述写"什么时候用"，
   不写"怎么实现"。多个工具描述含糊或重叠，模型就会选错——这是 Agent 调优
   的第一杠杆（比换模型便宜）。
4. **参数抽取**：`@P` 描述告诉模型参数语义，"帮我记一下明天交周报"会被抽成
   `content="明天交周报"`。参数类型用简单类型（String/int/boolean），别上复杂对象。
5. **权限**：工具能触达什么，App 就需要什么权限。查电量无需权限；将来做
   定位/通讯录类工具，运行时权限申请要在**工具被调用前**就绪（模型不会帮你申请）。

---

## 五、本周动手作业

1. **跑通**：问"现在几点？""电量多少？""帮我记一下周六早上要跑步"→"我记过什么？"
   同时开 Logcat 过滤 `SSE_CHAT`，观察 `工具已执行: xxx` 日志的顺序。
2. **协议实验**：在 `OkHttpHttpClient.toOkHttpRequest()` 打断点（或临时 Log 请求体），
   亲眼看到请求体里的 `tools` 数组 —— 验证第二节不是纸上谈兵。
3. **Agent Loop 实验**：问一个需要"组合工具"的问题，例如
   "现在几点了？帮我把时间记下来" —— 观察 Loop 转了不止一圈
   （getCurrentTime → saveMemo → 最终回答，日志里两三次工具执行）。
4. **描述实验（进阶）**：故意把 saveMemo 的描述改成和 listMemos 几乎一样，
   看模型开始选错工具 —— 体会"描述即选型"。
5. **思考题**：saveMemo 写进 SharedPreferences，卸载 App 就没了。
   如果要"云同步的备忘录"，架构该怎么改？（提示：W5 的 FastAPI 后端 + 数据库；
   工具代码不变，只是执行体从本地存储换成 HTTP 调用 —— 工具抽象的价值就在这）

## W5 预告

给 App 造一个 FastAPI 后端：API Key 从 APK 撤下来（安全收敛）、备忘录上云
（真实数据库）、RAG 初体验。工具的"手"从本机伸向服务器。
