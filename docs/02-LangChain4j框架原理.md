# 02 - LangChain4j 框架原理：它到底替你干了什么

> W3 教学文档。前置：读完 docs/01（SSE 原理）、docs/00（代码框架总览）。
> 本周目标：**理解框架的价值边界** —— 什么时候该用，什么时候不如手写。

---

## 一、一句话理解 AI Services

**AI Services = 声明式编程**。你写一个接口描述"要什么"，框架用动态代理生成"怎么做"：

```
你写的：                          框架生成的（Proxy 代理对象）：

interface Assistant {             assistant.chat("你好") 实际执行：
  @SystemMessage("你是助手")        1. 读注解 → 组装 system prompt
  fun chat(                       2. 从 ChatMemory 取历史消息
    @MemoryId id: String,         3. 拼 messages: [system, ...历史, "你好"]
    @UserMessage msg: String,     4. 调 OpenAiStreamingChatModel 发请求
  ): TokenStream                  5. SSE 逐 token 回调
}                                 6. 完成后把完整回答写回 ChatMemory
```

这和 Room（DAO 接口）、Retrofit（Service 接口）是**同一个模式**：
注解描述意图 + 动态代理生成实现。Android 工程师应该秒懂——你天天在用。

## 二、对照实验：W1 手写 vs W3 框架

| W1 手写的代码 | W3 谁接管的 | 文件 |
|---|---|---|
| `ChatModels.kt` 全部协议模型（100行） | 框架 http-client 层序列化 | 模型层只剩 UI 用的 `UiMessage` |
| `SseChatClient` 逐行读 `data:` 帧 | 框架的 SSE 解析（`ServerSentEventParser`） | 保留在 network/ 作参考，不再接入 |
| ViewModel 里 `buildList { system + 历史 }` | **ChatMemory** | 从 ViewModel 中删除 |
| OkHttp 超时/连接管理 | `langchain4j-http-client-okhttp`（我们显式接入） | 框架内 |
| 回调拼接 full/delta | `TokenStream.onPartialResponse` | 框架内 |

**亲手做这个实验**（比读十遍都有用）：

```bash
git log --oneline          # 找到 W1 和 W3 的提交
git diff W1的commit W3的commit -- app/src/main/java/com/workbuddy/assistant/ui/ChatViewModel.kt
```

看着 ViewModel 的 diff：删了历史拼接、删了 SYSTEM_PROMPT 常量、换了客户端 ——
这就是"框架的价值"最直观的样子。

## 三、ChatMemory 原理：框架没有魔法

**模型依然是无状态的**（docs/01 思考题的答案永远成立）。ChatMemory 做的事
和 W1 你手写的完全一样：内部维护一个消息列表，每次请求带上全部历史。
区别只有三点：

1. **窗口管理**：`MessageWindowChatMemory.withMaxMessages(20)` = 保留最近 20 条。
   为什么不无限存？——历史越长，每次请求的 token 越多 = 越贵 + prefill 越慢。
   窗口是"性价比阀"。W2 优化 TTFT 时会验证这一点。
2. **自动写回**：回答完成后框架自动 append，你不会忘记（W1 手写时如果忘了
   append 回答，下一轮模型就会"失忆"——这是最常见的 bug 之一）。
3. **按 MemoryId 隔离**：`chatMemoryProvider { ... }` 每个 ID 独立记忆，
   多会话/多用户场景天然支持（我们现在只用 "main" 一个）。

## 四、回调 → 协程：那座桥

LangChain4j 的 `TokenStream` 是**回调风格**，我们的 `ChatClient` 是 **suspend 风格**。
`LangChain4jChatClient` 里用 `suspendCancellableCoroutine` 做了桥接：

- 协程在 `assistant.chat(...)` 后挂起
- `onCompleteResponse` → `cont.resume(全文)` → 协程恢复返回
- `onError` → `cont.resumeWithException(e)` → 走 ViewModel 的 catch 分支
- `onPartialResponse` 期间直接透传 `onDelta` 回调（不等结束）

这个"回调世界 ↔ 协程世界"的桥接模式在 Android 无处不在
（老 SDK 回调 API 包装成 suspend），**值得抄进你的个人代码库**。

## 五、LangChain4j 上 Android 的四道坎（面试素材）

| 坎 | 现象 | 解法（本项目已配好） |
|---|---|---|
| Java 17 标准库 API 缺失 | 运行时 NoSuchMethodError | desugaring + desugar_jdk_libs 2.1.5 |
| 默认 HTTP 底座是 JDK HttpClient | 构建模型即崩 `NoClassDefFoundError: Ljava/net/http/HttpClient`（1.0.0-beta2 起 OpenAI 模块迁移了 HTTP 层，官方 issue #2836） | **自写 OkHttp 适配器** `agent/OkHttpHttpClient.kt`（<100 行实现 HttpClient SPI）+ 模型 builder 显式 `.httpClientBuilder(...)` 注入 |
| 1.1.0 起的 Java 9 API：`ServiceLoader.findFirst()` | `AiServices.builder()` 必经之路上崩 `NoSuchMethodError: No virtual method findFirst()`（脱糖库不支持 java.util.ServiceLoader，SPI 也无法绕过）。真机实测撞过三个调用点：1.6.0+ 的可观测性 Registrar、**1.1.0+ 的护栏模块 GuardrailService.builder()（挂在 AiServiceContext 构造函数里）** | **版本锁定 1.0.0**（AiServices 路径上 findFirst 的最后干净版本，已解压 jar + javap 精确反编译核实），并 exclude 默认 `langchain4j-http-client-jdk` |
| R8 裁剪反射目标 | release 包运行时崩（debug 正常，最阴险） | proguard-rules.pro 的 keep 规则 |

**为什么不是用官方 OkHttp 适配（langchain4j-http-client-okhttp）？** 它从
1.13.0-beta23 才开始提供——和上面 findFirst 崩溃的版本窗口（1.1~1.13）正好
两头堵：用它就得升到 ≥1.13（撞 findFirst），停在 1.0 就没有它（撞 JDK HttpClient）。
**自己实现 SPI 是唯一同时满足两头的解**，而且只需实现两个方法：
同步 `execute(request)` 和流式 `execute(request, parser, listener)`——
拿到字节流交给框架的 `ServerSentEventParser`，剩下的（逐事件回调、打字机）
框架全包了。这也顺便回答了面试题"HTTP 客户端抽象层的价值是什么"。

**踩坑实录**（2026-09-25，五轮）：最初用 1.8.0 凭旧印象以为底座是 openai4j/OkHttp
→ 崩 JDK HttpClient；升级 1.19.3 + 官方 OkHttp 适配 → 崩 Kotlin 编译器内部
`source must not be null`（OkHttp 5 的 Kotlin 元数据比 2.0.20 编译器新，升级
Kotlin 2.2.21 + OkHttp 5.3.2 对齐解决）；→ 崩 `META-INF` 反复冲突（依赖图里
同时进了 okhttp Android 变体和 okhttp-jvm 变体，exclude 收敛解决）；→ 崩
`ServiceLoader.findFirst` → 降 1.8.0 照崩（误判可观测性 1.9.0 才引入，实际
1.8.0 发布于 2025-10-24 早包含了）→ 降 1.5.0 照崩，**换了个调用点**
（GuardrailService）——这里还有第二重误判：验证时直接 grep jar 二进制找
"findFirst"，但 jar 内容是 deflate 压缩的，压缩字节里根本搜不到明文字符串，
"1.5.0 零引用"是假阴性！第三次才用对方法：**解压 jar → javap -c 逐类反编译 →
精确匹配 `java/util/ServiceLoader.findFirst` 调用**，扫描 1.0.0~1.8.0 全部
四件套后确认：护栏 1.1.0 起、可观测性 1.6.0 起挂进 AiServices，**1.0.0 是
唯一干净版**。**最终版本钉死 1.0.0 + 自写适配器**——还没完：点发送又崩 `StringBuilder.isEmpty()`
（Java 15 API，API 33+，脱糖不覆盖），藏在框架的 SSE 解析器里。
解法：SPI 把"解析"这一步本来就交给了适配器，照框架源码逐行移植一个自写
SSE 解析器（见 `OkHttpHttpClient.parseSseStream`），顺带把发送路径上所有
Java 9~17 API 用 javap 批量反编译排雷（Optional.isEmpty 已被脱糖覆盖、
`List.of` 系 API 30+ 设备满足、`HexFormat` 不在路径上）。**五关全过。教训：**
**JVM 框架上 Android 的兼容性是"版本×API×依赖图"三维问题，每维都可能单独炸；**
**报错位置不可信、issue 时间线不可信、版本号直觉不可信、连验证工具本身都可能**
**给你假阴性——对验证方法的验证，也是排障的一部分。**

**选型判断逻辑**（比结论重要）：引入任何 JVM 框架前先看三件事——
①最低 Java 版本 vs 你的 desugaring 能力 ②HTTP 底座是什么 ③反射用在哪。
这套判断对 LangChain4j、Hilt、Room 全部通用。

## 六、本周动手作业

1. **跑通**：Sync → Run → 对话，确认打字机正常、TTFT 正常（日志标签还是 SSE_CHAT）
2. **记忆实验**：聊 15+ 轮，然后问"我们这轮对话最开始聊了什么？"——
   观察它是否还记得最早期内容（提示：窗口是 20 条，注意 system 占不占名额）
3. **debugger 实验**：在 `LangChain4jChatClient.onPartialResponse` 打断点，
   看看回调线程叫什么名字（不是 main！想想为什么 UI 没崩）
4. **源码定位**：在 IDE 里 Ctrl+点进 `AiServices.builder`，找到 Proxy 相关代码，
   截图留档——"我读过框架源码"和"我用过框架"是两个档次的面试表现
5. **思考题**：如果把 `chat()` 返回类型从 `TokenStream` 改成 `String`，
   框架行为会怎么变？哪个适合什么场景？

## 七、W4 预告

回答会自己写进 ChatMemory，但**手机上的日历/联系人它还看不见**。
W4 给 Assistant 加 `@Tool` 注解的本地工具 —— 那时 Agent 循环才真正开始：
模型决定调工具 → 框架在手机上执行 → 结果回填 → 模型继续。这是整个项目
"为什么必须是 App"的答案所在。
