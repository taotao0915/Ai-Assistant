# WorkBuddy AI Assistant

Android + LLM 端云混合 AI 助手 —— 求职核心作品项目。

**技术组合**（与 8 周计划对齐）：
- 主线：**路线 B 端云混合**（云端大模型/RAG + 端侧编排与工具）
- 加分：**路线 C 纯端侧**（MNN 跑 Qwen int4 断网兜底）
- W1-W2 先手写协议层（SSE），W3 起引入 LangChain4j 对比框架

## 当前进度：W1-W2 纯云流式对话 ✅

| 文件 | 职责 | 原理课 |
|---|---|---|
| `app/.../model/ChatModels.kt` | OpenAI 兼容协议模型 | 第1课：协议与无状态模型 |
| `app/.../network/SseChatClient.kt` | OkHttp SSE 流式客户端（含 TTFT 埋点） | 第2课：SSE |
| `app/.../ui/ChatViewModel.kt` | MVVM 状态管理 + token 聚合 | 第3课：状态驱动 UI |
| `app/.../ui/ChatScreen.kt` | Compose 聊天界面（打字机气泡） | — |
| `docs/01-SSE与流式对话原理.md` | 本阶段教学文档（先读这个） | — |

## 运行步骤

1. **Android Studio（Ladybug 或更新）打开本目录**：`File → Open → D:\Android\Projects\AI\Workbuddy`
   首次打开会自动下载 Gradle 8.9 和依赖（几分钟）
2. **申请 API Key**：[阿里云百炼控制台](https://bailian.console.aliyun.com/) → 开通 → 创建 API-KEY（有免费额度）
3. **填 Key**：在项目根目录 `local.properties` 中加一行（此文件已被 git 忽略，不会泄漏）：
   ```properties
   dashscope.apiKey=sk-xxxxxxxxxxxxxxxx
   ```
4. **运行**：真机或模拟器（minSdk 26 = Android 8.0+）→ 发消息 → 观察打字机效果和顶栏的 **首字延迟 TTFT**

> 注意：`local.properties` 里还有一行 `sdk.dir=...` 是 Android Studio 自动生成的，不要删。

## 版本说明

- AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM 2024.09 / OkHttp 4.12 / minSdk 26
- 如果你的 Android Studio 更新，AGP/Kotlin 版本可在 `build.gradle.kts` 中按提示升级
- 命令行构建需要 gradle wrapper jar：在项目根目录执行 `gradle wrapper --gradle-version 8.9`（或直接用 Android Studio 运行）

## 8 周路线图

| 周 | 里程碑 | 状态 |
|---|---|---|
| W1-W2 | 纯云对话：SSE 流式 + Compose 聊天 UI | ✅ 本仓库 |
| W3 | LangChain4j AI Services + 意图路由 | ⬜ |
| W4 | 本地工具（日历/联系人）→ 真 Agent | ⬜ |
| W5-W6 | FastAPI 后端：Key 收敛 + RAG | ⬜ |
| W7-W8 | MNN 端侧 Qwen int4 离线兜底 + 演示视频 | ⬜ |

## 每完成一步，回来找 Buddy 对齐下一步
